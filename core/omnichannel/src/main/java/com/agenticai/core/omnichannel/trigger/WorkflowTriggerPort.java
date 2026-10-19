package com.agenticai.core.omnichannel.trigger;

import java.util.UUID;

/**
 * The seam that lets an inbound channel controller (omnichannel module)
 * actually RUN a workflow (workflow module) without omnichannel depending on
 * workflow - see {@link WorkflowTriggerChannel}'s javadoc for the full
 * rationale. Defined here, implemented by {@code WorkflowTriggerPortImpl} in
 * {@code core-workflow} (a plain {@code @Service}, wired into this
 * interface type by Spring at runtime - the two modules never reference
 * each other's concrete classes).
 */
public interface WorkflowTriggerPort {

    /**
     * Fire-and-forget: the implementation runs the workflow on a background
     * thread, capturing the CALLING thread's current {@code TenantContext}
     * before the async hop (a plain {@code ThreadLocal} wouldn't otherwise
     * survive onto a new thread) and clearing it again afterward. Errors are
     * logged, never thrown back to the caller - an inbound webhook (Twilio)
     * needs to return its empty-body 200 quickly; nothing is synchronously
     * waiting on this workflow's result the way a direct webhook-trigger
     * caller waits on {@code WorkflowWebhookController}'s response. Any
     * reply back to the channel is the workflow's own concern (e.g. an
     * Output(channel=whatsapp) node in its graph), not this method's.
     *
     * <p>Delegates to {@link #triggerAsync(UUID, String, String, String,
     * InboundTriggerContext)} with no context - use this overload for triggers
     * that have no meaningful "reply to sender" address (e.g. the plain webhook
     * trigger).
     */
    default void triggerAsync(UUID workflowId, String startNodeId, String input, String requestedBy) {
        triggerAsync(workflowId, startNodeId, input, requestedBy, null);
    }

    /**
     * As {@link #triggerAsync(UUID, String, String, String)}, but additionally
     * carries the inbound message's {@link InboundTriggerContext} (e.g. the
     * sender's WhatsApp number) so a workflow's {@code Output(channel=whatsapp)}
     * node with no explicit destination can reply to whoever triggered the run.
     * Pass {@code null} for {@code context} when there is no such address.
     *
     * @param startNodeId the id of the specific Trigger node (within {@code
     *                    workflowId}'s graph) this run was bound to and should
     *                    start from - per-node triggers (each Trigger node in a
     *                    workflow's graph can be independently bound to its own
     *                    inbound channel) mean a run can no longer assume "start
     *                    from THE trigger node", there may be more than one.
     */
    void triggerAsync(UUID workflowId, String startNodeId, String input, String requestedBy, InboundTriggerContext context);

    /**
     * Synchronous variant for a channel where the caller is live and
     * waiting on a result - today, only the CALL trigger ({@code
     * TwilioVoiceWebhookController}: a caller on the line needs to hear
     * something back before the webhook response completes). Runs on the
     * CALLING thread (no background hop, unlike {@link #triggerAsync}) -
     * the calling thread already has {@link com.agenticai.core.security.TenantContext}
     * set (it's a request thread), so there is nothing to capture/restore
     * the way the async path needs to. A run failure is reported back via
     * {@link WorkflowSyncTriggerResult#succeeded()}, never thrown - same
     * "the channel decides how to present a failure" posture the async
     * variant's own javadoc establishes.
     *
     * @param startNodeId see {@link #triggerAsync(UUID, String, String, String,
     *                    InboundTriggerContext)}'s parameter of the same name.
     */
    WorkflowSyncTriggerResult triggerSync(UUID workflowId, String startNodeId, String input, String requestedBy, InboundTriggerContext context);

    /**
     * Resolves one pending {@code voice_menu} node halt with the digits a
     * caller pressed and resumes that run on the CALLING thread - the
     * DTMF-collect half of {@link #triggerSync}'s live-call contract (see
     * {@link WorkflowSyncTriggerResult#pendingVoiceMenu()}). {@code digits}
     * is Twilio's raw {@code Digits} form field, which is legitimately blank
     * (the caller pressed nothing before the gather timed out) - that is not
     * an error, it is exactly the "timeout" branch the {@code voice_menu}
     * node type's javadoc documents.
     *
     * <p>Returns the SAME result type {@link #triggerSync} does, because the
     * exact same three outcomes are possible on resume: the run finished
     * (succeeded/failed - {@code pendingVoiceMenu() == null}), it hit
     * ANOTHER {@code voice_menu} node further down the same graph and halted
     * again (a fresh {@link WorkflowSyncTriggerResult.PendingVoiceMenu} for
     * the caller to gather again), or the resumed graph reached a halt this
     * one call cannot service (e.g. an Approval/Human-Input node) - the
     * implementation reports that as a normal failed-with-explanation result
     * rather than throwing, same "the channel decides how to present a
     * failure" posture {@link #triggerSync}'s own javadoc establishes.
     *
     * @param voiceMenuRequestId the pending request id from the {@code
     *                           PendingVoiceMenu} this caller is resolving.
     */
    WorkflowSyncTriggerResult collectVoiceMenuDigits(String voiceMenuRequestId, String digits, String requestedBy);
}
