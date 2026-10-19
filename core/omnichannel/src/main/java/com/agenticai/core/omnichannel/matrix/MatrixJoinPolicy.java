package com.agenticai.core.omnichannel.matrix;

/** What the bot does with a room invite. */
public enum MatrixJoinPolicy {
    /** Default: never join on invite. Rooms are joined by hand (or never). */
    OFF,
    /** Join only when the inviter matches the sender allow-list. */
    ALLOWLIST
}
