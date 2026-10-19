package com.agenticai.core.agents.vision;

/**
 * The tenant's plan lacks the {@code plan_limits.live_vision_allowed}
 * entitlement (V180) needed for a continuous WATCH/LIVE vision session.
 * Mapped to HTTP 403 in {@code GlobalExceptionHandler} with the same
 * client-actionable "upgrade your plan" semantics as
 * {@code TenantLimitExceededException} / {@code
 * GuardrailNotAvailableForPlanException}.
 */
public class VisionNotAllowedForPlanException extends RuntimeException {

    public VisionNotAllowedForPlanException(String message) {
        super(message);
    }
}
