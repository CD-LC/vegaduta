package com.agenticai.core.tenancy.dto;

import java.time.Instant;

/**
 * {@code GET /api/tenant/trial/status}'s body (event-triggered 7-day Premium
 * trial, PRICING-MONETIZATION-AUDIT-2026-07-31.md §5.4/B6) - what the web app
 * reads to decide whether to show a "start your free 7-day Premium trial"
 * offer at a gate moment.
 *
 * <ul>
 *   <li>{@code eligible} - a claim right now would succeed.</li>
 *   <li>{@code claimed} - this tenant already used its one trial (claimed and
 *       eligible are never both true).</li>
 *   <li>{@code expiresAt} - only when claimed: when the trial ends/ended
 *       (read from the durable {@code event_trial_expires_at}, so it survives
 *       the comp reverter dropping the tenant back to FREE).</li>
 *   <li>{@code reason} - only when NOT eligible: the human-readable reason a
 *       claim would 409, same string the claim endpoint would return.</li>
 * </ul>
 */
public record TrialStatusResponse(
    boolean eligible,
    boolean claimed,
    Instant expiresAt,
    String reason
) {
}
