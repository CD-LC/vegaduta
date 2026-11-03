package com.agenticai.core.guardrails.leak;

import java.util.Locale;

/** {@code app.guardrails.prompt-leak.action}: what {@link PromptLeakMode#ENFORCE} does to a leaking reply. */
public enum PromptLeakAction {
    /** Report only (the default). */
    WARN,
    /** Replace the leaking span with a marker and let the rest of the reply through. */
    REDACT,
    /** Replace the reply from the first leaking span on with a fixed message and stop streaming. */
    BLOCK;

    public static PromptLeakAction parse(String value) {
        if (value == null) {
            return WARN;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return WARN;
        }
    }
}
