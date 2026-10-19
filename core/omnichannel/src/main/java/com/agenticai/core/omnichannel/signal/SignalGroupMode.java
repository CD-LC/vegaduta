package com.agenticai.core.omnichannel.signal;

/** Whether the agent takes part in Signal group chats. */
public enum SignalGroupMode {
    /** Group messages are dropped before anything is stored (default). */
    IGNORE,
    /** The agent answers only when mentioned or when a message quotes one of its own. */
    MENTION,
    /** The agent answers every message in a paired group. */
    ALL
}
