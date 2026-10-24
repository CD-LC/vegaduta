package com.agenticai.core.prompts;

import java.util.regex.Pattern;

/**
 * The key naming convention. A key is two or more lower-case segments joined by dots: {@code
 * <family>.<name>[.<variant>]}, for example {@code directive.tool_trust}, {@code
 * directive.tool_trust.compact}, {@code nudge.continue_unfinished}, {@code refusal.guardrail.policy},
 * {@code summarizer.compaction}, {@code judge.tool_call_verifier}, {@code toolresult.park_pending},
 * {@code tool.description.generate_document}.
 *
 * <p>Families in use: {@code directive} (standing system-prompt blocks), {@code nudge} (platform
 * notes that steer the next model call), {@code toolresult} (text a tool call returns to the
 * model), {@code refusal} (text the user reads when a turn is stopped), {@code message} (other
 * user-visible fallbacks), {@code summarizer}, {@code judge}, {@code router}, {@code planner},
 * {@code memory}, {@code skills}, {@code delegation}, {@code workflow}, {@code rag}, {@code
 * learning}, {@code shim} and {@code tool.description}. A new family needs no registration: the key
 * only has to match {@link #PATTERN}. A key never changes meaning once released; retire it ({@code
 * retired: true}) and add a new one.
 */
public final class PromptKeys {

    private PromptKeys() {}

    public static final int MAX_LENGTH = 128;

    public static final Pattern PATTERN = Pattern.compile("[a-z][a-z0-9_-]*(\\.[a-z0-9][a-z0-9_-]*)+");

    public static boolean isValid(String key) {
        return key != null && key.length() <= MAX_LENGTH && PATTERN.matcher(key).matches();
    }
}
