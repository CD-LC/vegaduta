package com.agenticai.core.omnichannel.session;

import java.util.Locale;

/**
 * The three modes of {@code channel_default_agent.inbound_safety_mode}
 * (migration V242) - what an inbound channel message flagged by the
 * jailbreak/prompt-injection scan should trigger. Stored as VARCHAR (not an
 * enum-mapped column) so a value the running code doesn't know yet can
 * never crash row hydration - {@link #parse} degrades unknowns to {@link
 * #FLAG} rather than throwing.
 */
public enum InboundSafetyMode {
    /** No scan at all - the tenant opted this channel out. */
    OFF,
    /** Scan and report: the session is escalated (AWAITING_HUMAN) into the operator inbox but the agent still answers. */
    FLAG,
    /** Scan and suppress: a flagged message puts the conversation under a system safety hold until an operator releases it. */
    HOLD;

    public static String parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return FLAG.name();
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException e) {
            return FLAG.name();
        }
    }
}
