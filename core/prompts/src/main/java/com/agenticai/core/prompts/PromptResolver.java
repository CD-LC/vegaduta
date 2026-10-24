package com.agenticai.core.prompts;

import java.util.Map;

/**
 * THE lookup for model-facing and behavior-steering text. Every call site that sends text the
 * platform wrote asks here, by key, and never holds the text itself.
 *
 * <p>Order: the agent's override, the tenant's, the platform's, then the default: the classpath file, or the
 * text of a deprecated {@code app.*} property that seeds it ({@link PromptDefaultAlias}). It
 * fails safe: a missing key, a database error or a broken row yields the default text (or, for an
 * unknown key, an inactive {@link ResolvedPrompt}) and a log line, never an exception. A prompt
 * problem must not break a turn.
 *
 * <p>How to get one: inject {@code PromptResolver} (a Spring bean from the {@code prompts} module).
 * Code that is built by hand (tests, static helpers) uses {@link #defaultsOnly()}.
 */
public interface PromptResolver {

    ResolvedPrompt resolve(String key, PromptContext context);

    default ResolvedPrompt resolve(String key) {
        return resolve(key, PromptContext.none());
    }

    /**
     * As {@link #resolve(String, PromptContext)}, and counts the resolved text against {@code budget}
     * (the hook for token awareness; nothing is truncated here, see {@link PromptBudget}).
     */
    default ResolvedPrompt resolve(String key, PromptContext context, PromptBudget budget) {
        ResolvedPrompt resolved = resolve(key, context);
        if (budget != null && resolved.active()) {
            budget.add(key, resolved.text());
        }
        return resolved;
    }

    /** The text to send for {@code key}, or null when it is switched off or unknown. */
    default String text(String key, PromptContext context) {
        return resolve(key, context).textOrNull();
    }

    default String text(String key) {
        return text(key, PromptContext.none());
    }

    /**
     * Every registered key resolved for {@code context}, sorted by key. Placeholders are
     * substituted only from the context's variables, so a key whose value the context lacks keeps
     * its {@code {token}}.
     */
    Map<String, ResolvedPrompt> resolveAll(PromptContext context);

    /** The registered keys and their defaults. */
    PromptCatalog catalog();

    /** A resolver with the classpath defaults only (no overrides, no database). */
    static PromptResolver defaultsOnly() {
        return DefaultsOnly.INSTANCE;
    }
}
