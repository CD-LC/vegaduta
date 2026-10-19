package com.agenticai.core.providers;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;

/**
 * What the tenant's own call history says about each provider - the evidence
 * {@link ProviderCredentialResolver} ranks ACTIVE credentials by, so "any
 * usable provider" means one that has actually been answering, not merely
 * the one whose key was saved first.
 *
 * <p>An SPI because the history lives in {@code llm_call_log} (core/chat),
 * which this module cannot see. Absent bean (or a provider missing from the
 * returned map) means "no history": ranking then falls back to the old
 * oldest-activated-first order.
 */
public interface ProviderUsageSignal {

    /** History for the given providers in the CURRENT tenant; providers with no logged call may be omitted. */
    Map<ProviderName, Usage> recentUsage(Collection<ProviderName> providers);

    /**
     * The same history as a reportable {@link ProviderHealth} per provider (counts and the newest failure's
     * summary), for the providers listing. {@code cooldown} is how long a refused key stays paused for background
     * callers, so the answer can say until when. Providers may be omitted; the default reports nothing.
     */
    default Map<ProviderName, ProviderHealth> health(Collection<ProviderName> providers, java.time.Duration cooldown) {
        return Map.of();
    }

    /**
     * @param lastSuccessAt       when this provider last answered, or null
     * @param lastSuccessModel    the model of that answer, or null
     * @param lastFailureAt       when a call to this provider last failed, or null
     * @param lastFailureCredentialRejected true when that last failure was the provider refusing the
     *                            credential (HTTP 401/403, invalid key) rather than a transient or
     *                            request-shaped error
     */
    record Usage(
        Instant lastSuccessAt,
        String lastSuccessModel,
        Instant lastFailureAt,
        boolean lastFailureCredentialRejected
    ) {
        /** The newest logged call succeeded. */
        public boolean latestCallSucceeded() {
            return lastSuccessAt != null && (lastFailureAt == null || !lastFailureAt.isAfter(lastSuccessAt));
        }

        /**
         * The newest logged call was refused for its credential and nothing
         * has succeeded since: this key should not be anyone's default.
         */
        public boolean credentialSuspect() {
            return lastFailureAt != null && lastFailureCredentialRejected && !latestCallSucceeded();
        }
    }
}
