package com.agenticai.core.agents.hooks;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * The built-in hook actions. There is deliberately no scripting or code action:
 * a hook can only pick one of these and fill its parameters, so the set of things
 * a hook can ever do is closed and reviewable.
 *
 * <p>Wire form is the lower-case name ({@code require_approval}).
 */
public enum HookActionType {

    /** Deny a tool call (or the turn / a result / a reply) with a reason the model or user sees. */
    BLOCK,
    /** Route a matching tool call through the existing human-approval gate. */
    REQUIRE_APPROVAL,
    /** Regex / PII-pattern redaction of tool arguments, a tool result, or the model's reply. */
    REDACT,
    /** Cap matching tool calls per run / per minute, or model calls per minute. */
    LIMIT,
    /** Append a short static note after a matching tool result, or to the turn's per-turn context. */
    APPEND_CONTEXT,
    /** POST a JSON event to an allow-listed https URL; the reply may only say allow or deny (and why). */
    WEBHOOK,
    /** Audit only. */
    LOG;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** @return the matching action, or {@code null} when the text names none. */
    public static HookActionType parse(String text) {
        if (text == null) {
            return null;
        }
        try {
            return valueOf(text.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The actions that make sense at a point. Everything else is rejected at save time. */
    public static Set<HookActionType> allowedAt(HookPoint point) {
        return switch (point) {
            case BEFORE_MODEL -> EnumSet.of(BLOCK, LIMIT, APPEND_CONTEXT, WEBHOOK, LOG);
            case AFTER_MODEL -> EnumSet.of(BLOCK, REDACT, WEBHOOK, LOG);
            case BEFORE_TOOL -> EnumSet.of(BLOCK, REQUIRE_APPROVAL, REDACT, LIMIT, WEBHOOK, LOG);
            case AFTER_TOOL -> EnumSet.of(BLOCK, REDACT, APPEND_CONTEXT, WEBHOOK, LOG);
            case ON_ERROR, ON_RUN_END -> EnumSet.of(WEBHOOK, LOG);
        };
    }
}
