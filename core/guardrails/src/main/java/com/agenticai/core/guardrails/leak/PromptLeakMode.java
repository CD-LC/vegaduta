package com.agenticai.core.guardrails.leak;

import java.util.Locale;

/** {@code app.guardrails.prompt-leak.mode}: how much of the leak control runs. */
public enum PromptLeakMode {
    /** Nothing runs: no canary, no scan, no allocation per turn. */
    OFF,
    /** Canary is added and output is scanned; detections are counted and audited, output is never altered. */
    WARN,
    /** As {@link #WARN}, and the configured {@link PromptLeakAction} is applied to the output. */
    ENFORCE;

    /** Lenient parse for configuration values; anything unknown falls back to {@link #WARN}. */
    public static PromptLeakMode parse(String value) {
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
