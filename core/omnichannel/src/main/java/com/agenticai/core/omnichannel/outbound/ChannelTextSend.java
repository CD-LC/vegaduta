package com.agenticai.core.omnichannel.outbound;

import com.agenticai.core.omnichannel.session.ChannelType;

/**
 * How one channel sends a plain text message through the approval gate: the {@code
 * ApprovalActionExecutor} action type its adapter is registered under, and that adapter's own
 * {@code SendPayload} record. One implementation per channel, in the channel's package; {@code
 * agentcontrol.ChannelSendPortImpl} (agent sends) and {@code handoff.OperatorInboxService}
 * (operator replies) look them up by {@link #channel()}, so neither imports a channel adapter
 * (ADR-0011 rule a). The payload is serialized by the caller exactly as before, so the executor on
 * the other side sees the same JSON.
 */
public interface ChannelTextSend {

    ChannelType channel();

    /** The channel adapter's {@code ACTION_TYPE}, e.g. {@code channel.slack.send}. */
    String actionType();

    /** Approval-queue summary, e.g. {@code "Send Slack message to " + to}. */
    String summary(String to);

    /**
     * The channel adapter's {@code SendPayload} for a text send.
     *
     * @param subject used only by channels whose payload has one (email); ignored by the rest
     * @throws RecipientNotAddressableException when the channel cannot address {@code to} yet
     */
    Object payload(String to, String subject, String body);

    /**
     * The channel knows no route to this recipient yet (e.g. a Teams conversation that never
     * messaged the bot).
     */
    class RecipientNotAddressableException extends RuntimeException {
        public RecipientNotAddressableException(String message) {
            super(message);
        }
    }
}
