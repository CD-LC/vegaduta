package com.agenticai.core.prompts;

import java.util.ArrayList;
import java.util.List;

/**
 * The hook for token awareness: the assembler adds each block it is about to send and reads back
 * whether the standing text is over the configured budget ({@code
 * app.prompts.system-prompt-token-budget}, 0 = no budget). Nothing is dropped or truncated here:
 * the platform does not silently cut a safety directive to fit a number; the preview flags it and
 * an operator decides. Not thread-safe; one instance per assembly.
 */
public final class PromptBudget {

    /** One block as counted. */
    public record Entry(String key, int estimatedTokens) {}

    private final int limitTokens;
    private final double charsPerToken;
    private final List<Entry> entries = new ArrayList<>();
    private int used;

    public PromptBudget(int limitTokens, double charsPerToken) {
        this.limitTokens = Math.max(0, limitTokens);
        this.charsPerToken = charsPerToken > 0 ? charsPerToken : 4.0;
    }

    public static PromptBudget unlimited() {
        return new PromptBudget(0, 4.0);
    }

    /** A rule-of-thumb token count for {@code text}. */
    public int estimate(String text) {
        return text == null || text.isEmpty() ? 0 : (int) Math.ceil(text.length() / charsPerToken);
    }

    /** Counts {@code text} against the budget under {@code label}; returns its estimate. */
    public int add(String label, String text) {
        int tokens = estimate(text);
        entries.add(new Entry(label, tokens));
        used += tokens;
        return tokens;
    }

    public int used() {
        return used;
    }

    /** 0 = no budget. */
    public int limit() {
        return limitTokens;
    }

    public boolean overBudget() {
        return limitTokens > 0 && used > limitTokens;
    }

    public List<Entry> entries() {
        return List.copyOf(entries);
    }
}
