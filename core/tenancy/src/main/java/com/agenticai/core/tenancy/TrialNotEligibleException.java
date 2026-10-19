package com.agenticai.core.tenancy;

/**
 * Event-triggered 7-day Premium trial (PRICING-MONETIZATION-AUDIT-2026-07-31.md
 * §5.4/B6) - thrown by {@link TrialGrantService#claim} when the tenant fails an
 * eligibility rule (not FREE, has/had a paid subscription, upgrade in flight, or
 * already claimed its one trial). Mapped to 409 CONFLICT by {@code
 * TrialGrantController} itself (caught there, not routed through
 * GlobalExceptionHandler - that file is outside core/tenancy and is a known
 * cross-agent collision point this session): the request is well-formed and the
 * caller is authorized, but the tenant's current state conflicts with claiming a
 * trial - same 409 reasoning as {@code AgentInUseException}. The message is
 * written for end users and safe to surface verbatim.
 */
public class TrialNotEligibleException extends RuntimeException {

    public TrialNotEligibleException(String message) {
        super(message);
    }
}
