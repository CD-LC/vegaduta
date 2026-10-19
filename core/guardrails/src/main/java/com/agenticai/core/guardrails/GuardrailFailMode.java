package com.agenticai.core.guardrails;

import java.util.Locale;

/**
 * What a guardrail does when its own machinery fails (the policy lookup throws, the injection judge
 * errors or answers nothing, the moderation service throws). This is not about a violation: a
 * violation always blocks. It is about an <i>infrastructure</i> failure.
 *
 * <p>{@link #CLOSED} (the default) refuses the call, tool result or turn, so an attacker who can
 * make a check fail (an oversized input, a poisoned lookup, a provider outage they can trigger)
 * cannot also switch the check off. {@link #OPEN} lets the work through, which keeps chat and tools
 * up during a guardrail outage at the cost of that bypass. A component that is simply not
 * configured (no gate bean, no {@code ChatClient}) is not a failure and still passes in both modes.
 *
 * <p>The mode is deployment-wide ({@code app.guardrails.fail-mode}, set once at startup by {@link
 * GuardrailFailModeConfiguration}), held in a static because the turn-path classes that consult it
 * are built with {@code new}, not injected.
 */
public enum GuardrailFailMode {
    OPEN,
    CLOSED;

    private static volatile GuardrailFailMode current = CLOSED;

    public static GuardrailFailMode current() {
        return current;
    }

    public static boolean isClosed() {
        return current == CLOSED;
    }

    /** Test and startup hook. Prefer the property in production. */
    public static void set(GuardrailFailMode mode) {
        current = mode == null ? CLOSED : mode;
    }

    /** {@code open} or {@code closed}, case-insensitive; anything else (including blank) is {@link #CLOSED}. */
    public static GuardrailFailMode parse(String value) {
        if (value != null && value.trim().toUpperCase(Locale.ROOT).equals("OPEN")) {
            return OPEN;
        }
        return CLOSED;
    }
}
