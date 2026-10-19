package com.agenticai.core.workflow.voicemenu;

/**
 * Lifecycle of a {@link WorkflowVoiceMenuRequest}: {@code PENDING} while the
 * run sits {@code AWAITING_VOICE_INPUT}; {@code RESOLVED} once a digit (or a
 * retry-exhausted timeout/no-match) resumes the run. Mirrors {@code
 * WorkflowInputRequestStatus}'s reasoning exactly, including the deliberate
 * absence of an EXPIRED/CANCELLED state - a run cancelled or that simply
 * hangs up (Twilio never calls the action URL again) while a request is
 * pending leaves the row PENDING, which is honest: nothing ever resolved it.
 */
public enum WorkflowVoiceMenuRequestStatus {
    PENDING,
    RESOLVED
}
