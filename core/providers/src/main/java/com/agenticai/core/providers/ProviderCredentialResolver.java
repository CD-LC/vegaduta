package com.agenticai.core.providers;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.settings.SettingsResolver;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Turns a {@link ProviderName} into a decrypted, ready-to-use API key for
 * exactly one call. The decrypted string is never cached or logged by this
 * class — callers should use it immediately and let it go out of scope.
 */
@Component
public class ProviderCredentialResolver {

    private static final Logger log = LoggerFactory.getLogger(ProviderCredentialResolver.class);

    /**
     * How long a credential the provider refused stays off-limits to BACKGROUND callers
     * ({@link #resolveBest(boolean)}), counted from the refusal. After it, one call is allowed through as a probe.
     */
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

    private final LlmProviderCredentialRepository repository;
    private final EncryptionService encryptionService;
    private final Map<ProviderName, ExternalProviderCredentialSource> externalSources;
    private final ObjectProvider<ProviderUsageSignal> usageSignal;

    @Autowired
    public ProviderCredentialResolver(
        LlmProviderCredentialRepository repository,
        EncryptionService encryptionService,
        List<ExternalProviderCredentialSource> externalSources,
        ObjectProvider<ProviderUsageSignal> usageSignal
    ) {
        this.repository = repository;
        this.encryptionService = encryptionService;
        this.externalSources = indexByProvider(externalSources);
        this.usageSignal = usageSignal;
    }

    /** No usage signal: ranking degrades to oldest-activated-first. */
    public ProviderCredentialResolver(
        LlmProviderCredentialRepository repository,
        EncryptionService encryptionService,
        List<ExternalProviderCredentialSource> externalSources
    ) {
        this(repository, encryptionService, externalSources, null);
    }

    /** One source per provider; a duplicate is a wiring bug, so fail at startup. */
    static Map<ProviderName, ExternalProviderCredentialSource> indexByProvider(
        List<ExternalProviderCredentialSource> sources
    ) {
        Map<ProviderName, ExternalProviderCredentialSource> byProvider = new EnumMap<>(ProviderName.class);
        for (ExternalProviderCredentialSource source : sources) {
            ExternalProviderCredentialSource previous = byProvider.putIfAbsent(source.providerName(), source);
            if (previous != null) {
                throw new IllegalStateException("Two ExternalProviderCredentialSource beans claim "
                    + source.providerName() + ": " + previous.getClass().getName() + " and "
                    + source.getClass().getName());
            }
        }
        return byProvider;
    }

    /**
     * Every {@link ProviderName} resolves through {@link
     * LlmProviderCredentialRepository}, except providers whose vendor module
     * contributes an {@link ExternalProviderCredentialSource} (ADR-0011 phase
     * 2; formerly a hard-coded {@code SARVAM} branch). Sarvam is the one such
     * provider today: its BYOK key lives in its own {@code sarvam_credential}
     * table, shared with the non-chat Sarvam clients (translate/STT/TTS/etc.),
     * so a tenant activates Sarvam once, not once per capability. Its source
     * throws {@code SarvamNotConfiguredException} (mapped by {@code
     * GlobalExceptionHandler} the same way {@link ProviderNotActiveException}
     * is for every other provider) when nothing is active, so callers of this
     * method don't need to know which exception type to expect - both surface
     * as a clear "provider not configured" error either way.
     */
    public String resolveActiveKey(ProviderName providerName) {
        ExternalProviderCredentialSource external = externalSources.get(providerName);
        if (external != null) {
            return external.resolveActiveApiKey();
        }
        LlmProviderCredential credential = repository.findByProviderName(providerName)
            .filter(c -> c.getStatus() == ProviderStatus.ACTIVE)
            .orElseThrow(() -> new ProviderNotActiveException(providerName));
        return encryptionService.decrypt(credential.getEncryptedApiKey());
    }

    /**
     * For callers that don't care which provider, just that one is usable -
     * the shared BYOK fallthrough behind the internal "utility" LLM calls
     * (Ask-AI drafting, the AI workflow generator, code-tool generation,
     * session-summary folds, MCP self-heal, the eval judge). Every one of
     * those callers reaches this only AFTER {@code
     * TenantLimits#usesPlatformPool} has already routed platform-pool tenants
     * (the default for every tenant) to {@code FreeTierChatClientService}, so
     * this throwing means the tenant explicitly chose BYOK and has no active
     * key - a real configuration gap, not the pool's absence.
     *
     * <p>Picks the best-ranked ACTIVE credential ({@link #rankActiveProviders}).
     * It used to be "whichever was activated first", with no regard for
     * whether that key still worked: a workspace whose oldest key had been
     * revoked at the provider sent every judge / assist / generator call to
     * it and failed all of them while a working key sat one row down.
     */
    public ActiveProvider resolveAnyActiveKey() {
        ResolvedProvider best = resolveBest(false);
        return new ActiveProvider(best.providerName(), best.apiKey());
    }

    /**
     * {@link #resolveAnyActiveKey} with what the call history says about the choice, and - for BACKGROUND callers
     * ({@code background == true}: sweeps, self-heal, anything no person is waiting on) - a cooldown: a credential
     * the provider refused within the last {@code app.providers.rejected-cooldown-hours} (nothing succeeded since)
     * is skipped, and when that leaves nothing, {@link ProviderCoolingDownException} says so instead of calling
     * the refused provider again. A person-driven call ({@code false}) still gets the best-ranked key even if it
     * is suspect, so the provider's real error reaches them.
     */
    public ResolvedProvider resolveBest(boolean background) {
        List<RankedProvider> ranked = rankActiveProviders();
        if (ranked.isEmpty()) {
            throw new NoActiveProviderException();
        }
        RankedProvider chosen = ranked.get(0);
        if (background) {
            Duration cooldown = ProviderSettings.rejectedCooldown(settings, rejectedCooldownHours);
            Instant now = Instant.now();
            chosen = ranked.stream().filter(r -> !r.coolingDown(cooldown, now)).findFirst().orElse(null);
            if (chosen == null) {
                RankedProvider first = ranked.get(0);
                throw new ProviderCoolingDownException(first.providerName(), first.usage().lastFailureAt(), cooldown);
            }
        }
        LlmProviderCredential credential = chosen.credential();
        return new ResolvedProvider(credential.getProviderName(),
            encryptionService.decrypt(credential.getEncryptedApiKey()), chosen.lastWorkingModel(), chosen.credentialSuspect());
    }

    /** The provider chosen for a call: key (decrypted - never log it), its last working model, and whether it is suspect. */
    public record ResolvedProvider(ProviderName providerName, String apiKey, String lastWorkingModel,
                                   boolean credentialSuspect) {}

    /**
     * The tenant's ACTIVE credentials, most trustworthy first, judged by the
     * tenant's own call history ({@link ProviderUsageSignal}):
     * <ol>
     *   <li>newest call succeeded;</li>
     *   <li>has succeeded before, newest call failed for a reason other than
     *       the credential (rate limit, bad request...);</li>
     *   <li>never called;</li>
     *   <li>only ever failed, but not for the credential;</li>
     *   <li>newest call was refused for its credential and nothing has
     *       succeeded since ({@link RankedProvider#credentialSuspect}).</li>
     * </ol>
     * Ties keep oldest-activated-first, the previous stable order - on purpose
     * NOT most-recent-success-first: the eval judge resolves through this, and
     * a judge that hopped between two healthy providers with every chat turn
     * would make run-over-run scores incomparable. A suspect
     * credential is still returned (last): it is the tenant's only option
     * when nothing else is active, and the call then fails with the
     * provider's real error instead of a made-up "no provider".
     */
    public List<RankedProvider> rankActiveProviders() {
        List<LlmProviderCredential> active = repository.findByStatusOrderByCreatedAtAsc(ProviderStatus.ACTIVE);
        if (active == null || active.isEmpty()) {
            return List.of();
        }
        Map<ProviderName, ProviderUsageSignal.Usage> usage = Map.of();
        ProviderUsageSignal signal = usageSignal == null ? null : usageSignal.getIfAvailable();
        if (signal != null) {
            try {
                Map<ProviderName, ProviderUsageSignal.Usage> found =
                    signal.recentUsage(active.stream().map(LlmProviderCredential::getProviderName).toList());
                if (found != null) {
                    usage = found;
                }
            } catch (RuntimeException e) {
                // History is advisory; never let it break credential resolution.
                log.warn("Provider usage signal failed - ranking by activation order only ({})", e.toString());
            }
        }
        List<RankedProvider> ranked = new ArrayList<>(active.size());
        for (LlmProviderCredential credential : active) {
            ranked.add(new RankedProvider(credential, usage.get(credential.getProviderName())));
        }
        // Stable sort: equal ranks keep the repository's createdAt-ascending order.
        ranked.sort(Comparator.comparingInt(RankedProvider::tier));
        return ranked;
    }

    public record ActiveProvider(ProviderName providerName, String apiKey) {}

    /** One ACTIVE credential with what the call history says about it ({@code usage} null = never called). */
    public record RankedProvider(LlmProviderCredential credential, ProviderUsageSignal.Usage usage) {

        public ProviderName providerName() {
            return credential.getProviderName();
        }

        /** The provider refused this key on its newest call and nothing has succeeded since. */
        public boolean credentialSuspect() {
            return usage != null && usage.credentialSuspect();
        }

        /**
         * The provider refused this key within {@code cooldown} of {@code now} and nothing has succeeded since:
         * background callers leave it alone until the cooldown passes.
         */
        public boolean coolingDown(Duration cooldown, Instant now) {
            return credentialSuspect() && usage.lastFailureAt() != null
                && now.isBefore(usage.lastFailureAt().plus(cooldown));
        }

        /** The model of this provider's most recent successful call, or null. */
        public String lastWorkingModel() {
            return usage == null ? null : usage.lastSuccessModel();
        }

        /** The newest logged call to this provider succeeded. */
        public boolean proven() {
            return usage != null && usage.latestCallSucceeded();
        }

        int tier() {
            if (usage == null || (usage.lastSuccessAt() == null && usage.lastFailureAt() == null)) {
                return 2;
            }
            if (usage.latestCallSucceeded()) {
                return 0;
            }
            if (usage.credentialSuspect()) {
                return 4;
            }
            return usage.lastSuccessAt() != null ? 1 : 3;
        }
    }
}
