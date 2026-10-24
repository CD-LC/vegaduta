package com.agenticai.core.providers;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.providers.dto.ProviderActivationRequest;
import com.agenticai.core.providers.dto.ProviderAvailabilityResponse;
import com.agenticai.core.providers.dto.ProviderResponse;
import com.agenticai.core.providers.validation.ProviderNotSupportedException;
import com.agenticai.core.providers.validation.ProviderValidator;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.settings.SettingsResolver;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProviderService {

    private final LlmProviderCredentialRepository repository;
    private final EncryptionService encryptionService;
    private final Map<ProviderName, ProviderValidator> validatorsByProvider;
    private final List<ExternalProviderCredentialSource> externalCredentialSources;
    private final TenantLimits tenantLimits;
    private final PlatformHostedLlmProviderService platformHostedLlmProviderService;
    private final ObjectProvider<ProviderUsageSignal> usageSignal;

    /** The cooldown reported as "background calls paused until"; same property the resolver applies. */
    @Value("${app.providers.rejected-cooldown-hours:6}")
    long rejectedCooldownHours = 6;

    private SettingsResolver settings;

    /**
     * The settings catalog ({@code providers.credential.rejectedCooldown}): a workspace's cooldown replaces the
     * property value. Setter-injected; without it the property applies.
     */
    @Autowired(required = false)
    void setSettings(SettingsResolver settings) {
        this.settings = settings;
    }

    public ProviderService(
        LlmProviderCredentialRepository repository,
        EncryptionService encryptionService,
        List<ProviderValidator> validators,
        List<ExternalProviderCredentialSource> externalCredentialSources,
        TenantLimits tenantLimits,
        PlatformHostedLlmProviderService platformHostedLlmProviderService
    ) {
        this(repository, encryptionService, validators, externalCredentialSources, tenantLimits,
            platformHostedLlmProviderService, null);
    }

    @Autowired
    public ProviderService(
        LlmProviderCredentialRepository repository,
        EncryptionService encryptionService,
        List<ProviderValidator> validators,
        List<ExternalProviderCredentialSource> externalCredentialSources,
        TenantLimits tenantLimits,
        PlatformHostedLlmProviderService platformHostedLlmProviderService,
        ObjectProvider<ProviderUsageSignal> usageSignal
    ) {
        this.usageSignal = usageSignal;
        this.repository = repository;
        this.encryptionService = encryptionService;
        this.validatorsByProvider = validators.stream()
            .collect(Collectors.toMap(ProviderValidator::supportedProvider, Function.identity()));
        this.externalCredentialSources = List.copyOf(externalCredentialSources);
        this.tenantLimits = tenantLimits;
        this.platformHostedLlmProviderService = platformHostedLlmProviderService;
    }

    /**
     * Direct feedback (issue report): "even provider page should not be
     * editable" for a FREE-tier tenant - enforced here, not just hidden in
     * the UI (see ProvidersPanel.js's own isFreePlan gating), since this is
     * the one write path (POST /api/providers) a FREE tenant could still
     * reach directly. FREE tenants never need a BYOK provider at all - they
     * always route through the platform-hosted pool (see AgentChatService's
     * TenantPlan.FREE branch) - so this isn't a soft cap that can be worked
     * around, it's "this plan doesn't do this," same class of error {@link
     * TenantLimitExceededException} already models for agent/seat ceilings.
     *
     * <p>Gated on the data-driven {@code byokAllowed} entitlement rather than
     * a hardcoded {@code plan == FREE} check, so a platform-admin editing
     * {@code plan_limits.byok_allowed} in the web-admin editor actually
     * controls who can bring a key (previously that toggle was inert). FREE
     * seeds {@code byokAllowed=false} so behaviour is unchanged for it.
     */
    @Transactional
    public ProviderResponse activate(ProviderActivationRequest request) {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        if (tenantId != null && !tenantLimits.byokAllowed(tenantId)) {
            throw new TenantLimitExceededException(
                "Your plan automatically uses the platform-hosted LLM and does not support activating your own "
                    + "provider - upgrade your plan to bring your own key."
            );
        }

        ProviderValidator validator = validatorsByProvider.get(request.providerName());
        if (validator == null) {
            throw new ProviderNotSupportedException(request.providerName());
        }

        // Throws ProviderValidationException on a rejected/unverifiable key —
        // nothing is persisted in that case.
        validator.validate(request.apiKey());

        String encryptedKey = encryptionService.encrypt(request.apiKey());

        LlmProviderCredential credential = repository.findByProviderName(request.providerName())
            .map(existing -> {
                existing.reactivate(encryptedKey, ProviderStatus.ACTIVE);
                return existing;
            })
            .orElseGet(() -> new LlmProviderCredential(request.providerName(), encryptedKey, ProviderStatus.ACTIVE));

        LlmProviderCredential saved = repository.save(credential);
        return ProviderResponse.from(saved);
    }

    /**
     * "Give option to edit, delete the added key in add providers page" -
     * direct feedback. Mirrors {@code SarvamCredentialService#deactivate}/
     * {@code E2bCredentialService#deactivate}: marks the row {@link
     * ProviderStatus#INACTIVE} rather than deleting it, so {@link #listAll}
     * can still show what was configured (same "keep the row, flip the
     * status" posture every other BYOK credential in this codebase uses). A
     * no-op if this tenant never activated the provider - deactivating
     * something that was never active isn't an error, it's already the
     * desired end state.
     */
    @Transactional
    public void deactivate(ProviderName providerName) {
        repository.findByProviderName(providerName).ifPresent(credential -> {
            credential.deactivate();
            repository.save(credential);
        });
    }

    /**
     * "Sarvam not in dropdown" - direct feedback. Sarvam's BYOK credential
     * lives in its own {@code sarvam_credential} table (see {@link
     * ProviderCredentialResolver}'s javadoc), not {@link
     * LlmProviderCredentialRepository}, so it was structurally impossible
     * for it to show up here no matter how a tenant activated it - this
     * wasn't a filtering bug, Sarvam was simply never in the list this
     * method builds. Merged in the same way the web console's Agent
     * creation dropdown expects every other provider to look (see {@code
     * sarvam.SarvamProviderCredentialSource#toProviderResponse}) - only added
     * when a Sarvam credential actually exists, same as every other provider
     * only appearing here once a tenant has touched it. Since ADR-0011 phase 2
     * the merge iterates {@link ExternalProviderCredentialSource} beans rather
     * than naming Sarvam.
     */
    @Transactional(readOnly = true)
    public List<ProviderResponse> listAll() {
        List<ProviderResponse> all = listWithoutHealth();
        attachHealth(all);
        return all;
    }

    private List<ProviderResponse> listWithoutHealth() {
        List<ProviderResponse> all = new ArrayList<>(
            repository.findAll().stream().map(ProviderResponse::from).toList()
        );
        externalCredentialSources.forEach(source -> source.currentCredential().ifPresent(all::add));
        return all;
    }

    /**
     * Adds {@code health} (from the workspace's own call log, read-only) to every ACTIVE credential so the web can
     * warn about a key the provider refuses before someone picks it. Never throws: health is advice, not a gate.
     */
    private void attachHealth(List<ProviderResponse> responses) {
        ProviderUsageSignal signal = usageSignal == null ? null : usageSignal.getIfAvailable();
        if (signal == null) {
            return;
        }
        try {
            List<ProviderName> active = responses.stream()
                .filter(r -> r.status() == ProviderStatus.ACTIVE).map(ProviderResponse::providerName).toList();
            Map<ProviderName, ProviderHealth> health = signal.health(active, ProviderSettings.rejectedCooldown(settings, rejectedCooldownHours));
            if (health == null || health.isEmpty()) {
                return;
            }
            for (int i = 0; i < responses.size(); i++) {
                ProviderResponse r = responses.get(i);
                if (r.status() == ProviderStatus.ACTIVE && health.get(r.providerName()) != null) {
                    responses.set(i, r.withHealth(health.get(r.providerName())));
                }
            }
        } catch (RuntimeException e) {
            // advice only
        }
    }

    /**
     * Backs {@code GET /api/providers/availability} - see {@link
     * ProviderAvailabilityResponse}'s javadoc for why {@link #listAll}'s
     * length alone is the wrong signal for "can this tenant create/chat
     * with an agent." {@code platformHostedAvailable} mirrors {@code
     * AgentChatService#buildChatClientFor}'s own routing condition exactly
     * by calling it - {@link TenantLimits#usesPlatformPool(String, TenantPlan)}
     * - so this never drifts from what actually happens at chat time - a tenant this method says can
     * create an agent is a tenant that will actually get a working chat
     * client, not just a UI gate that happens to agree today.
     */
    @Transactional(readOnly = true)
    public ProviderAvailabilityResponse checkAvailability() {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        boolean hasByokProvider = !listWithoutHealth().isEmpty();
        boolean eligibleForPlatformPool = tenantLimits.usesPlatformPool(tenantId);
        boolean platformHostedAvailable = eligibleForPlatformPool
            && platformHostedLlmProviderService.resolveTopPriorityEnabled().isPresent();
        return ProviderAvailabilityResponse.of(hasByokProvider, platformHostedAvailable);
    }
}
