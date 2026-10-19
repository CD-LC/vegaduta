package com.agenticai.core.providers;

import java.time.Instant;

/**
 * What this workspace's own call history says about one provider key - the read-only evidence behind the
 * {@code health} field of {@code GET /api/providers}. No schema, no probe call: it is derived from
 * {@code llm_call_log} over the last {@code windowDays} days.
 *
 * <ul>
 *   <li>{@code status} {@code "rejected"}: the provider's newest answer was a refusal of this credential (HTTP
 *       401/403, invalid key) and nothing has succeeded since - the key needs attention, and background AI calls
 *       are paused for it until {@code backgroundPausedUntil};</li>
 *   <li>{@code "ok"}: otherwise, when there is any call history;</li>
 *   <li>{@code "unknown"}: no call in the window - nothing to go on.</li>
 * </ul>
 * {@code lastError} is a short, secret-free summary of the newest failure (null when there was none).
 */
public record ProviderHealth(
    String status,
    int windowDays,
    int failures,
    int successes,
    Instant lastSuccessAt,
    Instant lastFailureAt,
    String lastError,
    Instant backgroundPausedUntil
) {
    public static final String OK = "ok";
    public static final String REJECTED = "rejected";
    public static final String UNKNOWN = "unknown";

    public static ProviderHealth unknownFor(int windowDays) {
        return new ProviderHealth(UNKNOWN, windowDays, 0, 0, null, null, null, null);
    }
}
