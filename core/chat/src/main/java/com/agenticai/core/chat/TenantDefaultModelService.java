package com.agenticai.core.chat;

import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.providers.ProviderModelCatalogService;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.providers.ExternalProviderCredentialSource;
import com.agenticai.core.providers.ProviderStatus;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The provider + model a NEW agent in this workspace should start on: one the
 * workspace can actually use right now.
 *
 * <ul>
 *   <li>Workspace on the platform pool: the pool ({@code PLATFORM_HOSTED}).</li>
 *   <li>Bring-your-own-key: the best-ranked ACTIVE credential (see {@link
 *       ProviderCredentialResolver#rankActiveProviders}) - the provider whose
 *       calls have been succeeding most recently - on the model of that
 *       provider's most recent successful call, else the catalog's default
 *       model for it.</li>
 *   <li>No usable credential and no pool: nothing, with a message telling the
 *       person to connect a provider.</li>
 * </ul>
 *
 * <p>Before this existed the create form simply took the first row of
 * {@code GET /api/providers}. When that row was a key the provider had started
 * refusing, every "create agent, test it" in the workspace answered "The AI provider rejected our credentials" although its
 * OpenRouter key was working the whole time.
 */
@Service
public class TenantDefaultModelService {

    private static final Logger log = LoggerFactory.getLogger(TenantDefaultModelService.class);

    /** Descriptive placeholder {@code AgentService#create} stores for pool agents; the pool picks the real model per call. */
    static final String PLATFORM_POOL_MODEL = "platform-pool";

    public enum Source {
        /** The workspace runs on the shared platform pool. */
        PLATFORM_POOL,
        /** A key of the workspace's own that has answered recently. */
        WORKING_PROVIDER,
        /** A key of the workspace's own with no recent successful call to go on. */
        ACTIVE_PROVIDER,
        /** Nothing usable - connect a provider. */
        NONE
    }

    /**
     * @param providerName   null only when {@code source == NONE}
     * @param model          null when the provider's catalog is empty (the form then asks for one) or source is NONE
     * @param needsAttention the chosen key is the only option and its newest call was refused, or nothing is usable
     * @param message        what to tell the person when {@code needsAttention}; null otherwise
     */
    public record DefaultModel(
        ProviderName providerName,
        String model,
        Source source,
        boolean needsAttention,
        String message
    ) {
    }

    private final TenantLimits tenantLimits;
    private final ProviderCredentialResolver credentialResolver;
    private final ProviderModelCatalogService providerModelCatalogService;
    private final List<ExternalProviderCredentialSource> externalCredentialSources;

    public TenantDefaultModelService(
        TenantLimits tenantLimits,
        ProviderCredentialResolver credentialResolver,
        ProviderModelCatalogService providerModelCatalogService,
        List<ExternalProviderCredentialSource> externalCredentialSources
    ) {
        this.tenantLimits = tenantLimits;
        this.credentialResolver = credentialResolver;
        this.providerModelCatalogService = providerModelCatalogService;
        this.externalCredentialSources = List.copyOf(externalCredentialSources);
    }

    @Transactional(readOnly = true)
    public DefaultModel resolve() {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        if (tenantId != null && tenantLimits.usesPlatformPool(tenantId)) {
            return new DefaultModel(ProviderName.PLATFORM_HOSTED, PLATFORM_POOL_MODEL, Source.PLATFORM_POOL, false, null);
        }

        List<ProviderCredentialResolver.RankedProvider> ranked = credentialResolver.rankActiveProviders();
        if (ranked.isEmpty()) {
            // Vendor-owned keys (e.g. Sarvam's own table, ADR-0011) are never in the
            // ranked list; an ACTIVE one is still a usable starting point.
            for (ExternalProviderCredentialSource source : externalCredentialSources) {
                boolean active = source.currentCredential()
                    .map(c -> c.status() == ProviderStatus.ACTIVE)
                    .orElse(false);
                if (active) {
                    ProviderName external = source.providerName();
                    return new DefaultModel(external, catalogDefault(external), Source.ACTIVE_PROVIDER, false, null);
                }
            }
            return new DefaultModel(null, null, Source.NONE, true,
                "No model provider is connected. Connect a model provider in Providers settings before "
                    + "chatting with this agent.");
        }

        // Among the keys whose newest call succeeded, the one used most recently
        // is the workspace's current working choice; otherwise the best-ranked.
        ProviderCredentialResolver.RankedProvider best = ranked.stream()
            .filter(ProviderCredentialResolver.RankedProvider::proven)
            .max(Comparator.comparing(r -> r.usage().lastSuccessAt()))
            .orElse(ranked.get(0));
        ProviderName provider = best.providerName();
        String model = best.lastWorkingModel() != null ? best.lastWorkingModel() : catalogDefault(provider);
        if (best.credentialSuspect()) {
            return new DefaultModel(provider, model, Source.ACTIVE_PROVIDER, true,
                "The last call to " + provider + " was refused for its credentials and no other provider is "
                    + "connected. Check that key in Providers settings, or connect another model provider.");
        }
        return new DefaultModel(provider, model, best.proven() ? Source.WORKING_PROVIDER : Source.ACTIVE_PROVIDER, false, null);
    }

    private String catalogDefault(ProviderName provider) {
        try {
            return providerModelCatalogService.defaultModelFor(provider).orElse(null);
        } catch (RuntimeException e) {
            // A catalog hiccup must not take the create form down; the form asks for a model instead.
            log.warn("No catalog default model for {} ({})", provider, e.toString());
            return null;
        }
    }
}
