package com.agenticai.core.agents.tools.messaging;

/**
 * Agent-side outbound channel sends - the same cross-module inversion as {@link
 * ChannelMessageQueryPort} (see that interface's javadoc for why {@code core/agents} cannot depend
 * on {@code core/omnichannel}).
 *
 * <p>The implementation routes through {@code HumanApprovalGateService}, so an agent's {@code
 * channel_send_message} call lands in the exact same approval pipeline - same per-action-type
 * policies, same retry semantics, same audit trail - as a human clicking "Send" on a channel card
 * in the console. Agents get channel control; they do not get a shortcut around its governance.
 * That is the whole reason this port exists rather than a direct adapter call: the adapter path IS
 * the governed path.
 */
public interface ChannelSendPort {

    /**
     * Evaluates and (when the approval policy allows) dispatches an outbound channel message.
     *
     * @param channel channel key, case-insensitive. One of: {@code whatsapp} (provider-agnostic -
     *     the dispatch layer picks Meta / Twilio in its usual precedence), {@code telegram}, {@code
     *     slack}, {@code discord}, {@code teams}, {@code email}, {@code messenger}, {@code
     *     instagram}, {@code x}.
     * @param to recipient in the channel's own addressing form: a phone number for whatsapp, a chat
     *     id for telegram, a channel/DM id for slack/discord, a conversation id for teams, an email
     *     address for email, a PSID/IGSID for messenger/instagram, a DM conversation id for x.
     * @param subject email only; ignored by every other channel.
     * @param body the message text.
     * @param requestedBy attribution recorded on the approval request, e.g. {@code "agent:<uuid>"}
     *     - never a model-supplied name.
     * @return the gate's verdict. {@code approved=true} means the platform accepted the send for
     *     execution (its own pipeline carries it from here); {@code pendingRequestId} non-null
     *     means a human approval is outstanding and the message has NOT gone out.
     * @throws IllegalArgumentException on an unknown channel name.
     */
    ChannelSendResult send(
            String channel, String to, String subject, String body, String requestedBy);

    record ChannelSendResult(boolean approved, String pendingRequestId, String summary) {}
}
