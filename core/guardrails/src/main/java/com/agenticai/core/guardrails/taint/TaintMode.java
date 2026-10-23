package com.agenticai.core.guardrails.taint;

import java.util.Locale;

/**
 * How the per-turn untrusted-content taint layer behaves ({@code app.agents.taint.mode}).
 *
 * <ul>
 *   <li>{@link #OFF} - the layer is inert: no state is kept, nothing is counted, tool output is
 *       never touched.
 *   <li>{@link #OBSERVE} (the base-profile default since 2026-10-23; the {@code personal} profile enforces) - taint is tracked and a call that WOULD have needed approval is
 *       counted, logged and audited, but the call runs exactly as it did before and the model sees
 *       exactly the bytes it saw before. Zero behaviour change; the evidence an operator needs to
 *       decide whether to flip to {@link #ENFORCE}.
 *   <li>{@link #ENFORCE} - untrusted tool output is delimited as data for the model, and once a
 *       turn is tainted every side-effecting tool call is routed through the human approval gate.
 * </ul>
 */
public enum TaintMode {
    OFF,
    OBSERVE,
    ENFORCE;

    /** Lenient parse; blank or unrecognised values fall back to {@link #OBSERVE}, the safe default. */
    public static TaintMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return OBSERVE;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return OBSERVE;
        }
    }
}
