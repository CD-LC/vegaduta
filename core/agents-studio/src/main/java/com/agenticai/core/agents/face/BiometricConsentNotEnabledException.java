package com.agenticai.core.agents.face;

/**
 * {@code Agent.biometricConsentEnabled} is false for the agent a face-
 * enrollment/list call was made against. Mapped to HTTP 403 in {@code
 * GlobalExceptionHandler} - same "actionable by a tenant admin, not a
 * platform outage" posture as {@code VisionNotAllowedForPlanException} -
 * turning the flag on via {@code PATCH /api/agents/{id}/biometric-consent}
 * is the remedy.
 */
public class BiometricConsentNotEnabledException extends RuntimeException {

    public BiometricConsentNotEnabledException(String message) {
        super(message);
    }
}
