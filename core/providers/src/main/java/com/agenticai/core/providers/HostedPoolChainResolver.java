package com.agenticai.core.providers;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.providers.PlatformHostedLlmProviderService.HostedProviderSelection;
import com.agenticai.core.providers.validation.ProviderValidationException;
import com.agenticai.core.security.net.SsrfGuard;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The read side of the hosted LLM pool: builds the priority-ordered chain of usable rows (master
 * switch, enabled flag, SSRF guard, decryptable key) that FREE-tier chat falls through. A row that
 * fails a check is left out and reported once. Split out of {@link
 * PlatformHostedLlmProviderService}, whose public resolve methods delegate here; logs under that
 * class's logger so the "logged once per row" lines keep their category.
 */
final class HostedPoolChainResolver {

    private static final Logger log =
            LoggerFactory.getLogger(PlatformHostedLlmProviderService.class);

    /**
     * Rows already reported as unusable ({@code <id>:key} / {@code <id>:url}). The pool is resolved
     * on every FREE-tier turn, so without this a bad row would log a WARN per turn. An entry is
     * dropped when the row is seen healthy again, so a row that breaks a second time is reported
     * again.
     */
    private final Set<String> reportedBadRows = ConcurrentHashMap.newKeySet();

    private final PlatformHostedLlmProviderRepository repository;
    private final EncryptionService encryptionService;
    private final BooleanSupplier poolEnabled;
    private final Supplier<SsrfGuard> ssrfGuard;

    HostedPoolChainResolver(
            PlatformHostedLlmProviderRepository repository,
            EncryptionService encryptionService,
            BooleanSupplier poolEnabled,
            Supplier<SsrfGuard> ssrfGuard) {
        this.repository = repository;
        this.encryptionService = encryptionService;
        this.poolEnabled = poolEnabled;
        this.ssrfGuard = ssrfGuard;
    }

    /** See {@link PlatformHostedLlmProviderService#resolveEnabledChain}. */
    List<HostedProviderSelection> enabledChain() {
        if (!poolEnabled.getAsBoolean()) {
            return List.of();
        }
        return HostedPoolToolCallGrading.withoutChatOnlyRowsWhenAvoidable(
                        repository.findAllByEnabledTrueOrderByPriorityAsc())
                .stream()
                .filter(this::passesUrlGuardOrLogAndSkip)
                .flatMap(provider -> selectionOrLogAndSkip(provider).stream())
                .toList();
    }

    /** See {@link PlatformHostedLlmProviderService#resolveEnabledSelectionById}. */
    Optional<HostedProviderSelection> enabledSelectionById(Long id) {
        if (!poolEnabled.getAsBoolean()) {
            return Optional.empty();
        }
        return enabledSelectionByIdIgnoringPoolSwitch(id);
    }

    /**
     * Same checks as {@link #enabledSelectionById} minus the pool's master switch: whether the row
     * could serve if the pool were on. Lets a repair decide whether switching the pool on would
     * help before it touches the admin's switch.
     */
    Optional<HostedProviderSelection> enabledSelectionByIdIgnoringPoolSwitch(Long id) {
        return repository
                .findById(id)
                .filter(PlatformHostedLlmProvider::isEnabled)
                .filter(this::passesUrlGuardOrLogAndSkip)
                .flatMap(provider -> selectionOrLogAndSkip(provider));
    }

    /**
     * The selection for {@code provider}, or empty (logged) when its stored key cannot be
     * decrypted: a placeholder written outside the API, or a value encrypted under an encryption
     * key this deployment no longer has. One such row must not fail every FREE-tier chat; it is
     * left out of the pool, the same fail-closed posture as {@link #passesUrlGuardOrLogAndSkip},
     * until an admin re-enters the key.
     */
    private Optional<HostedProviderSelection> selectionOrLogAndSkip(
            PlatformHostedLlmProvider provider) {
        try {
            HostedProviderSelection selection =
                    new HostedProviderSelection(provider, decryptSecretOrNull(provider));
            reportedBadRows.remove(provider.getId() + ":key");
            return Optional.of(selection);
        } catch (IllegalStateException e) {
            if (reportedBadRows.add(provider.getId() + ":key")) {
                log.warn(
                        "Hosted LLM provider '{}' (id={}) has an API key that cannot be decrypted"
                                + " - excluding it from the pool until an admin re-enters it"
                                + " (logged once per row until it is fixed): {}",
                        provider.getLabel(),
                        provider.getId(),
                        e.getMessage());
            }
            return Optional.empty();
        }
    }

    /**
     * {@code encryptedApiKey} is nullable since V169 - a {@code VERTEX_ADC} row (see {@link
     * PlatformHostedLlmAuthType}) stores no secret at all, so calling {@link
     * EncryptionService#decrypt} unconditionally (the pre-V169 behaviour) would throw for every
     * such row. {@code HostedLlmChatClientFactory}'s Vertex branch never reads {@code
     * decryptedApiKey} for an ADC row anyway.
     */
    String decryptSecretOrNull(PlatformHostedLlmProvider provider) {
        String encrypted = provider.getEncryptedApiKey();
        return encrypted == null ? null : encryptionService.decrypt(encrypted);
    }

    /**
     * Second seam for the SSRF guard (see {@link HostedLlmBaseUrlGuard}'s javadoc) - {@link
     * PlatformHostedLlmProviderService#create}/{@code update} already block a bad {@code baseUrl}
     * from ever being saved, but this covers any row that predates the guard, or was written
     * directly against the database outside the API. A row that now fails the check is silently
     * excluded from the chain (logged once) rather than thrown - this method's whole contract is
     * "every row here is actually usable", and a failing row degrading to "not offered" is exactly
     * the same fail-closed posture the master-switch/enabled-flag checks right above already apply,
     * not a new error path a caller has to handle.
     */
    private boolean passesUrlGuardOrLogAndSkip(PlatformHostedLlmProvider provider) {
        try {
            HostedLlmBaseUrlGuard.checkAllowedWithoutDns(provider.getBaseUrl(), ssrfGuard.get());
            reportedBadRows.remove(provider.getId() + ":url");
            return true;
        } catch (ProviderValidationException e) {
            if (reportedBadRows.add(provider.getId() + ":url")) {
                log.warn(
                        "Hosted LLM provider '{}' (id={}) has a baseUrl that fails the SSRF guard"
                                + " - excluding it from the pool until an admin fixes it (logged"
                                + " once per row until it is fixed): {}",
                        provider.getLabel(),
                        provider.getId(),
                        e.getMessage());
            }
            return false;
        }
    }
}
