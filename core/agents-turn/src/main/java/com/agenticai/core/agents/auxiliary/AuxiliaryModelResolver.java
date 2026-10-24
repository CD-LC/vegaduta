package com.agenticai.core.agents.auxiliary;

import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.providers.ProviderModelCatalogEmptyException;
import com.agenticai.core.providers.ProviderModelCatalogService;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.settings.SettingContext;
import com.agenticai.core.settings.SettingsResolver;
import com.agenticai.core.tenancy.AiModelPreference;
import com.agenticai.core.tenancy.TenantAiModelPreferenceService;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The one place that decides which provider and model a background call runs on (context compaction, the
 * session summary, the "Ask AI" assist, judges) for a tenant that uses its own provider key. The callers used
 * to take {@code listModels(provider).get(0)} of {@code resolveAnyActiveKey()}: the first ALPHABETICAL model of
 * whichever credential came first, so what the call cost and how well it summarised followed sort order.
 *
 * <p>The choice is explicit and logged, first usable layer wins:
 *
 * <ol>
 *   <li>{@link Source#TENANT_PREFERENCE}: the tenant's own AI Model Control row for the purpose ({@link
 *       AuxiliaryPurpose#featureKey()});
 *   <li>{@link Source#PLATFORM_SETTING}: {@code app.auxiliary-model.<purpose>.*};
 *   <li>{@link Source#AGENT_MODEL}: the provider and model of the agent the call is made for;
 *   <li>{@link Source#CATALOG_DEFAULT}: the best credential and that provider's catalog default model (the
 *       explicit, reviewable choice new agents get), never the first alphabetical entry.
 * </ol>
 *
 * A layer whose provider has no active credential, or whose agent cannot be loaded, is skipped with a log line;
 * nothing here is ever an arbitrary pick. Tenants on the platform pool never reach this class: the pool picks
 * its own model.
 */
@Component
public class AuxiliaryModelResolver {

    private static final Logger log = LoggerFactory.getLogger(AuxiliaryModelResolver.class);

    /** Which layer chose the model. */
    public enum Source {
        TENANT_PREFERENCE,
        PLATFORM_SETTING,
        AGENT_MODEL,
        CATALOG_DEFAULT
    }

    /** The provider, its key (decrypted: never log it), the model and the layer that chose them. */
    public record Selection(ProviderName provider, String apiKey, String model, Source source) {}

    private final AuxiliaryModelProperties properties;
    private final ProviderCredentialResolver credentialResolver;
    private final ProviderModelCatalogService catalog;
    private final AuxiliaryAgentModels agentModels;
    private final TenantAiModelPreferenceService tenantPreferences;
    private final String anthropicFallbackModel;
    private final Set<String> warnedOnce = ConcurrentHashMap.newKeySet();

    public AuxiliaryModelResolver(
            AuxiliaryModelProperties properties,
            ProviderCredentialResolver credentialResolver,
            ProviderModelCatalogService catalog,
            AuxiliaryAgentModels agentModels,
            ObjectProvider<TenantAiModelPreferenceService> tenantPreferences,
            @Value("${app.agents.default-model:claude-sonnet-4-5}") String anthropicFallbackModel) {
        this.properties = properties;
        this.credentialResolver = credentialResolver;
        this.catalog = catalog;
        this.agentModels = agentModels;
        this.tenantPreferences = tenantPreferences.getIfAvailable();
        this.anthropicFallbackModel = anthropicFallbackModel;
    }

    private volatile SettingsResolver settings;

    /**
     * The settings catalog, so a tenant's {@code aux.useAgentModel} replaces {@code app.auxiliary-model.use-agent-model}
     * for the background calls made in its name. Absent (hand-built instances): the platform property. The choice is made
     * on the calling thread, where the tenant is set (the tenant preference above is read from it too).
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSettingsResolver(SettingsResolver resolver) {
        this.settings = resolver;
    }

    /** Whether the agent's own model is the fallback, for the calling thread's tenant. */
    private boolean useAgentModel() {
        SettingsResolver catalog = settings;
        if (catalog == null) {
            return properties.isUseAgentModel();
        }
        try {
            return catalog.getBool(USE_AGENT_MODEL_KEY, SettingContext.tenant());
        } catch (RuntimeException e) {
            log.warn("Could not read {}; using app.auxiliary-model.use-agent-model: {}", USE_AGENT_MODEL_KEY, e.toString());
            return properties.isUseAgentModel();
        }
    }

    static final String USE_AGENT_MODEL_KEY = "aux.useAgentModel";

    /**
     * @param agentId the agent the call is made for, or null when there is none
     * @param background true for a caller no person waits on: a credential the provider refused recently is skipped
     * @throws com.agenticai.core.providers.NoActiveProviderException when no layer is usable and the tenant has no
     *     active credential
     * @throws ProviderModelCatalogEmptyException when the chosen provider's catalog is empty
     */
    public Selection select(AuxiliaryPurpose purpose, UUID agentId, boolean background) {
        Optional<Selection> chosen = tenantPreference(purpose);
        if (chosen.isEmpty()) {
            chosen = platformSetting(purpose);
        }
        if (chosen.isEmpty() && agentId != null && useAgentModel()) {
            chosen = agentModel(purpose, agentId);
        }
        Selection selection = chosen.orElseGet(() -> catalogDefault(background));
        log.info(
                "Auxiliary model for {}: {} {} (chosen by {})",
                purpose.configKey(),
                selection.provider(),
                selection.model(),
                selection.source());
        return selection;
    }

    private Optional<Selection> tenantPreference(AuxiliaryPurpose purpose) {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        if (tenantPreferences == null || tenantId == null) {
            return Optional.empty();
        }
        AiModelPreference preference;
        try {
            preference = tenantPreferences.getAll(tenantId).get(purpose.featureKey());
        } catch (RuntimeException e) {
            log.warn("Could not read the tenant's AI Model Control preference for {}: {}", purpose.featureKey(), e.toString());
            return Optional.empty();
        }
        if (preference == null || preference.providerName() == null) {
            return Optional.empty();
        }
        ProviderName provider;
        try {
            provider = ProviderName.valueOf(preference.providerName());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return usable(purpose, Source.TENANT_PREFERENCE, provider, preference.model());
    }

    private Optional<Selection> platformSetting(AuxiliaryPurpose purpose) {
        AuxiliaryModelProperties.Choice choice = properties.choiceFor(purpose);
        if (choice.isEmpty()) {
            return Optional.empty();
        }
        if (choice.getProvider() == null) {
            warnOnce(
                    purpose.configKey() + ":model-only",
                    "app.auxiliary-model." + purpose.configKey() + ".model is set without a provider: a model id means"
                            + " nothing without its provider, so it is ignored");
            return Optional.empty();
        }
        return usable(purpose, Source.PLATFORM_SETTING, choice.getProvider(), choice.getModel());
    }

    private Optional<Selection> agentModel(AuxiliaryPurpose purpose, UUID agentId) {
        AuxiliaryAgentModels.AgentModel agent;
        try {
            agent = agentModels.modelOf(agentId).orElse(null);
        } catch (RuntimeException e) {
            log.warn("Auxiliary model for {}: agent {} could not be loaded ({}); using the catalog default", purpose.configKey(), agentId, e.toString());
            return Optional.empty();
        }
        if (agent == null) {
            return Optional.empty();
        }
        ProviderName provider = agent.provider();
        if (provider == null || provider == ProviderName.PLATFORM_HOSTED) {
            // The platform pool is not a credential of the tenant's: nothing to call it with here.
            return Optional.empty();
        }
        String model = agent.model();
        if (model != null && !model.isBlank()) {
            model = catalog.replacementForRetiredModel(model).orElse(model);
        }
        return usable(purpose, Source.AGENT_MODEL, provider, model);
    }

    /** The key of {@code provider} and {@code model} (the provider's catalog default when blank), or empty. */
    private Optional<Selection> usable(AuxiliaryPurpose purpose, Source source, ProviderName provider, String model) {
        String key;
        try {
            key = credentialResolver.resolveActiveKey(provider);
        } catch (RuntimeException e) {
            log.warn(
                    "Auxiliary model for {}: {} ({}) has no usable credential ({}); trying the next choice",
                    purpose.configKey(),
                    provider,
                    source,
                    e.toString());
            return Optional.empty();
        }
        String effectiveModel = model != null && !model.isBlank() ? model : modelFor(provider);
        return Optional.of(new Selection(provider, key, effectiveModel, source));
    }

    private Selection catalogDefault(boolean background) {
        ProviderCredentialResolver.ResolvedProvider best = credentialResolver.resolveBest(background);
        return new Selection(best.providerName(), best.apiKey(), modelFor(best.providerName()), Source.CATALOG_DEFAULT);
    }

    /** The provider's explicit catalog default; the configured default model only for Anthropic. */
    private String modelFor(ProviderName provider) {
        Optional<String> picked = catalog.defaultModelFor(provider);
        if (picked.isPresent()) {
            return picked.get();
        }
        if (provider == ProviderName.ANTHROPIC && anthropicFallbackModel != null && !anthropicFallbackModel.isBlank()) {
            return anthropicFallbackModel;
        }
        throw new ProviderModelCatalogEmptyException(provider);
    }

    private void warnOnce(String key, String message) {
        if (warnedOnce.add(key)) {
            log.warn(message);
        }
    }
}
