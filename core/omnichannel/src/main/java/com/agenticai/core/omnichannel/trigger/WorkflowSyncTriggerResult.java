package com.agenticai.core.omnichannel.trigger;

/**
 * The result of {@link WorkflowTriggerPort#triggerSync} (and, since the
 * voice-menu fast-follow, {@link WorkflowTriggerPort#collectVoiceMenuDigits}
 * too) - a caller-facing summary of a synchronous workflow run, NOT the full
 * {@code WorkflowRunResponse} DTO ({@code core-workflow} owns that type; this
 * module can't reference it without creating the same module cycle {@link
 * WorkflowTriggerChannel}'s javadoc explains {@link WorkflowTriggerPort}
 * exists to avoid). {@code outputText} is the run's last completed step's
 * summary (or its error message on failure) - whatever a caller like {@code
 * TwilioVoiceWebhookController} needs to read back to a human, nothing more.
 *
 * <p>{@code pendingVoiceMenu} is the one exception to "the run already
 * finished" that {@code succeeded}/{@code outputText} otherwise assume: a
 * non-null value means the run is NOT done - it halted mid-graph on a
 * {@code voice_menu} node waiting for the caller to press digits, and the
 * caller (today, only {@code TwilioVoiceWebhookController}) must render a
 * DTMF {@code <Gather>} for {@link PendingVoiceMenu#promptText()} and post
 * whatever comes back to {@link WorkflowTriggerPort#collectVoiceMenuDigits}
 * with {@link PendingVoiceMenu#requestId()} rather than treating {@code
 * outputText} as a final answer to speak. The two-arg constructor below
 * exists so every pre-existing caller (which only ever produced a genuinely
 * finished result) keeps compiling unchanged.
 */
public record WorkflowSyncTriggerResult(boolean succeeded, String outputText, PendingVoiceMenu pendingVoiceMenu) {

    public WorkflowSyncTriggerResult(boolean succeeded, String outputText) {
        this(succeeded, outputText, null);
    }

    /**
     * Everything a channel controller needs to render the next DTMF gather.
     * {@code core-workflow}'s {@code voice_menu} node type is out of reach
     * from this module (see class javadoc), so this is a plain,
     * omnichannel-owned mirror of the handful of its config fields a caller
     * actually needs, snapshotted at halt time by {@code
     * WorkflowVoiceMenuRequestService}.
     *
     * @param requestId      the pending {@code workflow_voice_menu_request}
     *                       row's id - echo this back to {@link
     *                       WorkflowTriggerPort#collectVoiceMenuDigits} so the
     *                       right paused run resumes.
     * @param promptText     what to speak before gathering (already the
     *                       correct one of the node's {@code prompt}/{@code
     *                       invalidPrompt} for this attempt - the caller does
     *                       not need to know this is a retry).
     * @param numDigits      digit count to collect, or {@code null} for a
     *                       variable-length run terminated by {@code
     *                       finishOnKey} (mirrors Twilio's own {@code
     *                       <Gather numDigits>} semantics).
     * @param finishOnKey    the key that ends a variable-length gather.
     * @param timeoutSeconds how long to wait for the first digit.
     */
    public record PendingVoiceMenu(
        String requestId, String promptText, Integer numDigits, String finishOnKey, int timeoutSeconds
    ) {
    }
}
