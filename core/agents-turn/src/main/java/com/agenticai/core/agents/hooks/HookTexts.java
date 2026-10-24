package com.agenticai.core.agents.hooks;

import com.agenticai.core.prompts.PromptContext;
import com.agenticai.core.prompts.PromptResolver;
import java.util.Map;
import java.util.UUID;

/**
 * The words the hook engine writes: what a tool result says when a workspace hook stopped the call
 * or withheld its output, the reasons used when a hook author gave none, and the lines a person
 * reads when a hook blocks a request or a reply. Every text is a prompt-registry key {@code
 * message.hook.*} with its shipped default under {@code prompts/en/}; an agent, tenant or platform
 * override applies. A hook author's own {@code reason} / {@code message} / {@code note} is data
 * the hook carries and always wins over the default wording.
 *
 * <p>Lookups run inside the hook context's tenant scope (the engine is called from tool threads),
 * and never throw: a text that cannot be resolved is the shipped one.
 */
final class HookTexts {

    static final HookTexts DEFAULTS = new HookTexts(PromptResolver.defaultsOnly());

    private static final String PREFIX = "message.hook.";

    static final String TOOL_DENIED = PREFIX + "tool_denied";
    static final String TOOL_OUTPUT_WITHHELD = PREFIX + "tool_output_withheld";
    static final String DEFAULT_BLOCK_REASON = PREFIX + "default_block_reason";
    static final String DEFAULT_LIMIT_REASON = PREFIX + "default_limit_reason";
    static final String ARGS_NOT_REDACTABLE = PREFIX + "args_not_redactable";
    static final String OUTPUT_NOT_REDACTABLE = PREFIX + "output_not_redactable";
    static final String CHECK_INCOMPLETE = PREFIX + "check_incomplete";
    static final String REQUEST_BLOCKED = PREFIX + "request_blocked";
    static final String RATE_LIMITED = PREFIX + "rate_limited";
    static final String REPLY_WITHHELD = PREFIX + "reply_withheld";
    static final String REPLY_NOT_REDACTABLE = PREFIX + "reply_not_redactable";
    static final String CONTEXT_NOTE = PREFIX + "context_note";
    static final String TOOL_RESULT_NOTE = PREFIX + "tool_result_note";

    private final PromptResolver resolver;

    HookTexts(PromptResolver resolver) {
        this.resolver = resolver == null ? PromptResolver.defaultsOnly() : resolver;
    }

    /** The text of {@code key} for the hook context's agent, with {@code variables} filled in. */
    String text(HookCtx ctx, String key, Map<String, String> variables) {
        String resolved = null;
        try {
            UUID agentId = ctx == null ? null : ctx.agentId();
            PromptContext prompt = PromptContext.forAgent(agentId).withVariables(variables);
            resolved =
                    ctx == null || ctx.scope() == null
                            ? resolver.text(key, prompt)
                            : ctx.scope().get(() -> resolver.text(key, prompt));
        } catch (RuntimeException e) {
            // fall through to the shipped text
        }
        if (resolved != null) {
            return resolved;
        }
        return PromptResolver.defaultsOnly().text(key, PromptContext.none().withVariables(variables));
    }

    String text(HookCtx ctx, String key) {
        return text(ctx, key, Map.of());
    }

    /** The result the model reads when a hook stopped {@code tool}. */
    String toolDenied(HookCtx ctx, String tool, String reason) {
        // Registered as the platform's answer, so the effect and turn journals do not read the (configurable) wording.
        return com.agenticai.core.guardrails.ToolResultMarker.mark(
                text(
                        ctx,
                        TOOL_DENIED,
                        Map.of("tool", String.valueOf(tool), "reason", noTrailingPeriod(authoredOr(reason, text(ctx, DEFAULT_BLOCK_REASON))))),
                com.agenticai.core.guardrails.ToolResultMarker.Kind.BLOCKED);
    }

    /** The result the model reads when a hook withheld what {@code tool} returned. */
    String withheldResult(HookCtx ctx, String tool, String reason) {
        return com.agenticai.core.guardrails.ToolResultMarker.mark(
                text(
                        ctx,
                        TOOL_OUTPUT_WITHHELD,
                        Map.of("tool", String.valueOf(tool), "reason", noTrailingPeriod(authoredOr(reason, text(ctx, DEFAULT_BLOCK_REASON))))),
                com.agenticai.core.guardrails.ToolResultMarker.Kind.WITHHELD);
    }

    static String noTrailingPeriod(String text) {
        String t = text;
        while (t.endsWith(".")) {
            t = t.substring(0, t.length() - 1);
        }
        return t.isEmpty() ? text : t;
    }

    static String authoredOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
