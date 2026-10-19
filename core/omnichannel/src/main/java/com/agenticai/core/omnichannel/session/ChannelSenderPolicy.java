package com.agenticai.core.omnichannel.session;

/**
 * Decides, BEFORE any session row, transcript row or LLM slot exists, whether an inbound sender may
 * enter a channel's normal flow at all. A channel that speaks to strangers over an open network
 * (Matrix, and any peer-to-peer style channel after it) puts one of these in front of {@link
 * ChannelSessionService#resolveOrCreate}.
 *
 * <p>This is a gate in front of the pairing flow, never a replacement for it: {@link
 * Decision#ADMIT} means "may proceed to the pairing gate and, once an admin approved the
 * conversation, to the agent". It never means "approved". {@link Decision#DROP} means the sender is
 * ignored without a reply, a session or a log row, so a stranger cannot make the install write
 * anything or learn that it is there.
 *
 * <p>Default-deny is the shape of every implementation: nothing reaches an agent without either the
 * allow-list (when the operator chose that mode) or the admin approval of the pairing flow.
 */
public interface ChannelSenderPolicy {

    /** Outcome of {@link #evaluate}. */
    enum Decision {
        /** May continue to session resolution and the pairing gate. */
        ADMIT,
        /** Ignore silently: no reply, no session, no log row. */
        DROP
    }

    /**
     * @param senderId the channel's own stable id for the sender (for Matrix the MXID)
     */
    Decision evaluate(String senderId);
}
