package com.agenticai.core.omnichannel.trigger;

/**
 * "Consumers should be able to select the channel from where it should be
 * triggered" (#422) - WEBHOOK is the default (a per-node token or, newer, an
 * OAuth2/OIDC bearer token; see {@code WorkflowDefinition} and {@code
 * WebhookAuthMode} in the {@code core-workflow} module). Every other value
 * instead binds a specific TRIGGER NODE (within a workflow's graph) to
 * inbound messages on that channel via {@link WorkflowTriggerNode} (each
 * Trigger node in a workflow's graph can be independently bound to its own
 * channel - previously {@code ChannelWorkflowTrigger} modeled this at the
 * whole-workflow level, one row per channel pointing at a workflow id; see
 * {@code V175__per_node_workflow_triggers.sql} for that migration). For
 * WHATSAPP and TELEGRAM, a workflow's {@code Output} node with
 * no explicit destination replies to whoever triggered the run - see {@code
 * InboundTriggerContext}. MESSENGER/INSTAGRAM/SLACK/DISCORD/X carry the same
 * {@link InboundTriggerContext} on trigger today (EMAIL does not - see
 * {@code EmailInboundPollingJob}, which follows SMS's simpler contextless
 * {@code triggerAsync} overload instead), but none of the six has an {@code
 * Output} node case wired to actually consume it yet - the same "trigger
 * works, reply-to-sender Output routing is a separate follow-up" gap SMS has
 * always had.
 *
 * <p><b>Why this lives in {@code core-omnichannel} and not {@code
 * core-workflow}, even though it's mostly a "workflow" concept:</b> {@code
 * core-workflow} already depends on {@code core-omnichannel} (for reusing
 * {@code TwilioCredentialService} in the WhatsApp output node). The inbound
 * side of this feature needs the reverse direction too - {@code
 * TwilioWebhookController} (omnichannel) has to look up "is a workflow bound
 * to WHATSAPP" and run it - which would make omnichannel depend on workflow
 * and create a module cycle. Putting the shared enum/entity/lookup table
 * here (a "dumb" channel-to-workflow-id mapping, agnostic of what a workflow
 * actually is) and the actual execution behind {@link WorkflowTriggerPort}
 * (an interface also defined here, implemented over in {@code core-workflow}
 * and wired by Spring at runtime) breaks the cycle without either module
 * needing to know the other's internals.
 *
 * <p><b>Stale-doc fix:</b> this javadoc used to claim CALL had "no backing
 * implementation FOR WORKFLOW TRIGGERING specifically" and that {@code
 * WorkflowService#setTriggerChannel} "rejects it outright" - neither is
 * true. CALL IS fully backed: {@code TwilioVoiceWebhookController#inboundCall}
 * checks {@link WorkflowTriggerNode} first and, when a workflow is bound,
 * hands the caller a {@code <Gather input="speech">} prompt instead of the
 * default Conversation Relay handoff, then {@code #collectSpeech} runs that
 * workflow SYNCHRONOUSLY via {@link WorkflowTriggerPort#triggerSync} (a
 * caller is live on the line waiting, unlike every other channel's
 * fire-and-forget {@code triggerAsync}) and speaks the result back before
 * hanging up. {@code WorkflowService} itself has no per-channel allowlist at
 * all - it accepts any non-WEBHOOK value generically (requiring only that
 * the workflow is already ACTIVE) and delegates straight to {@link
 * WorkflowTriggerNode}'s per-node-per-channel table, so every value added to
 * this enum is automatically bindable with no further change needed there.
 */
public enum WorkflowTriggerChannel {
    WEBHOOK,
    WHATSAPP,
    TELEGRAM,
    SMS,
    CALL,
    MESSENGER,
    INSTAGRAM,
    SLACK,
    DISCORD,
    X,
    EMAIL,
    // Microsoft Teams (migration V240) - carries an InboundTriggerContext whose
    // replyToAddress packs "serviceUrl|conversationId" (the two parts a Bot
    // Framework reply needs, separated by a character neither can contain),
    // decoded by the "teams" Output node case in WorkflowNodeExecutor.
    TEAMS
}
