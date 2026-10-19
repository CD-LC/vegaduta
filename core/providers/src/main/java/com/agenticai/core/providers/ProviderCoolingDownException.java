package com.agenticai.core.providers;

import java.time.Duration;
import java.time.Instant;

/**
 * Thrown to BACKGROUND callers (sweeps, self-heal, anything no person is waiting on) when every active credential
 * the workspace has was refused by its provider recently and nothing has succeeded since, so calling again would
 * only fail again. The credential is marked as needing attention for a cooldown period; after it, one call is let
 * through to find out whether the key was fixed.
 *
 * <p>Prod 2026-10-05: an hourly sweep kept calling a Vertex key the provider rejected - 24 failed calls a day for
 * two weeks, in a workspace whose other key worked, visible only on the Insights page.
 *
 * <p>Extends {@link NoActiveProviderException} (HTTP 400 if one ever reaches a request thread); background callers
 * catch it, log one rate-limited line and skip the work.
 */
public class ProviderCoolingDownException extends NoActiveProviderException {

    private final ProviderName provider;
    private final Instant retryAfter;

    public ProviderCoolingDownException(ProviderName provider, Instant lastFailureAt, Duration cooldown) {
        super("The " + provider + " provider rejected this workspace's credentials at " + lastFailureAt
            + "; background AI calls are paused until " + lastFailureAt.plus(cooldown)
            + ". Fix that key in Providers settings, or connect another model provider.");
        this.provider = provider;
        this.retryAfter = lastFailureAt.plus(cooldown);
    }

    public ProviderName provider() {
        return provider;
    }

    public Instant retryAfter() {
        return retryAfter;
    }
}
