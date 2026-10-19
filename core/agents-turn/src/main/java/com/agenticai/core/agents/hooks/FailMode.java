package com.agenticai.core.agents.hooks;

import java.util.Locale;

/**
 * What a hook that could not reach a verdict (webhook timeout, bad reply, host
 * not allowed, internal error) does to the call it guards.
 *
 * <p>{@link #CLOSED} treats the failure as a deny - the safe default for
 * {@code BEFORE_TOOL} and {@code BEFORE_MODEL}. {@link #OPEN} lets the call
 * continue and records the failure. Only the {@code webhook} action can fail in a
 * way this mode governs: {@code redact} is always fail-closed (a redaction that
 * did not happen must never be read as "nothing to redact").
 */
public enum FailMode {
    OPEN,
    CLOSED;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** @return the matching mode, or {@code null} when the text names none. */
    public static FailMode parse(String text) {
        if (text == null) {
            return null;
        }
        try {
            return valueOf(text.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
