package com.agenticai.core.omnichannel.matrix;

/** Who may start a conversation with the bot. Neither mode skips the admin pairing approval. */
public enum MatrixSenderMode {
    /** Default: any sender reaches the pairing flow ("an admin needs to approve this chat"). */
    PAIRING,
    /** Only allow-listed senders reach the pairing flow; everyone else is ignored silently. */
    ALLOWLIST
}
