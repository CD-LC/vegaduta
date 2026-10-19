package com.agenticai.core.omnichannel.session;

/**
 * What a channel does with a direct message from a sender the platform has never seen and the
 * operator has not allow-listed. Shared by every channel that offers the choice (Signal, iMessage)
 * so the console, the SDKs and the stored values mean one thing. Default-deny either way: the agent
 * never answers an unapproved sender.
 */
public enum UnknownSenderPolicy {
    /** Reply once with a pairing code; the owner approves the conversation in the console. */
    PAIR,
    /** Say nothing and store nothing, unless the sender is already a session, contact or allow-listed. */
    IGNORE
}
