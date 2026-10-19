package com.agenticai.core.tenancy.demo;

/** Lifecycle of one {@link DemoChannelLink}. Mirrored by V128's {@code chk_demo_channel_link_state}. */
public enum DemoChannelLinkState {

    /** A pairing code has been issued and is showing on {@code /try}; nobody has redeemed it. */
    PENDING,

    /**
     * Live. For {@link DemoChannelType#TELEGRAM} that means an external chat
     * is bound and being answered; for {@link DemoChannelType#WHATSAPP_PREVIEW}
     * it means only that the visitor used the preview at least once.
     */
    LINKED,

    /**
     * Disconnected - by the visitor, by session expiry, or because the same
     * external chat re-paired to a newer sandbox. Terminal until the visitor
     * asks for a fresh code, which resets the same row back to
     * {@link #PENDING} (V128 keeps one row per session per channel on
     * purpose).
     */
    REVOKED
}
