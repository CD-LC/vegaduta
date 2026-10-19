package com.agenticai.core.chat;

import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * In-turn tool-result clearing (gap analysis 2026-10-01, item 4). The Spring AI
 * tool loop resends every earlier tool result on each iteration; on long
 * research or coding runs that is the dominant token cost. Once the tool
 * results accumulated in the CURRENT invocation (everything after the last
 * {@link UserMessage}) exceed {@code thresholdChars}, results older than the
 * last {@code keepLast} are replaced by a short placeholder. Results in the
 * latest round (the last {@link ToolResponseMessage}) are never cleared, and
 * budget-exhausted markers are kept because the loop limiter reads them.
 * Idempotent; returns the input list untouched when nothing is cleared.
 */
public final class ToolResultClearing {

    public static final int DEFAULT_THRESHOLD_CHARS = 24_000;
    public static final int DEFAULT_KEEP_LAST = 3;

    static final String PLACEHOLDER_PREFIX = "[tool result cleared to save context: ";
    /**
     * Start of the JSON placeholder the token-budget compaction (core/agents {@code context} package,
     * {@code app.context.compaction.*}) writes. Recognised here so this character-threshold clearing
     * never overwrites a placeholder that carries a restore pointer with its own pointer-less one.
     */
    public static final String CONTEXT_CLEARED_JSON_PREFIX = "{\"context_cleared\":true";
    /**
     * A genuine placeholder is a few hundred characters at most. A tool output that merely STARTS with a
     * placeholder prefix but is longer than this is hostile or coincidental content, not a placeholder, and
     * must stay clearable - otherwise any tool could exempt its own output from the context budget.
     */
    public static final int MAX_PLACEHOLDER_CHARS = 1_000;
    private static final String BUDGET_MARKER = "tool_budget_exhausted";

    private final boolean enabled;
    private final int thresholdChars;
    private final int keepLast;

    public ToolResultClearing(boolean enabled, int thresholdChars, int keepLast) {
        this.enabled = enabled;
        this.thresholdChars = thresholdChars > 0 ? thresholdChars : DEFAULT_THRESHOLD_CHARS;
        this.keepLast = Math.max(keepLast, 0);
    }

    public static ToolResultClearing disabled() {
        return new ToolResultClearing(false, DEFAULT_THRESHOLD_CHARS, DEFAULT_KEEP_LAST);
    }

    static String placeholder(String toolName, int chars) {
        return PLACEHOLDER_PREFIX + toolName + " " + chars + " chars; re-call if needed]";
    }

    public List<Message> apply(List<Message> history) {
        if (!enabled || history == null || history.isEmpty()) {
            return history;
        }
        int start = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i) instanceof UserMessage) {
                start = i + 1;
                break;
            }
        }
        int lastToolMessage = -1;
        long total = 0;
        int responseCount = 0;
        for (int i = start; i < history.size(); i++) {
            if (history.get(i) instanceof ToolResponseMessage trm) {
                lastToolMessage = i;
                for (ToolResponseMessage.ToolResponse r : trm.getResponses()) {
                    total += length(r.responseData());
                    responseCount++;
                }
            }
        }
        if (total <= thresholdChars || responseCount <= keepLast) {
            return history;
        }
        // Responses (not messages) are the unit: the newest keepLast stay.
        int toClear = responseCount - keepLast;
        List<Message> out = new ArrayList<>(history);
        boolean changed = false;
        int seen = 0;
        for (int i = start; i < lastToolMessage && seen < toClear; i++) {
            if (!(history.get(i) instanceof ToolResponseMessage trm)) {
                continue;
            }
            List<ToolResponseMessage.ToolResponse> rebuilt = new ArrayList<>(trm.getResponses().size());
            boolean msgChanged = false;
            for (ToolResponseMessage.ToolResponse r : trm.getResponses()) {
                if (seen < toClear && clearable(r.responseData())) {
                    rebuilt.add(new ToolResponseMessage.ToolResponse(
                        r.id(), r.name(), placeholder(r.name(), r.responseData().length())));
                    msgChanged = true;
                } else {
                    rebuilt.add(r);
                }
                seen++;
            }
            if (msgChanged) {
                out.set(i, ToolResponseMessage.builder().responses(rebuilt).metadata(trm.getMetadata()).build());
                changed = true;
            }
        }
        return changed ? out : history;
    }

    private static boolean clearable(String data) {
        boolean alreadyCleared = data != null && data.length() <= MAX_PLACEHOLDER_CHARS
            && (data.startsWith(PLACEHOLDER_PREFIX) || data.startsWith(CONTEXT_CLEARED_JSON_PREFIX));
        return data != null && !alreadyCleared && !data.contains(BUDGET_MARKER);
    }

    private static int length(String s) {
        return s == null ? 0 : s.length();
    }
}
