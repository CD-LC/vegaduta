package com.agenticai.core.chat;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 *
 * <p>The placeholder is a JSON OBJECT ({@code {"context_cleared":true,"tool":...}}, the same shape the
 * token-budget compaction writes), not prose: Spring AI's Gemini adapter parses every tool result as JSON
 * when it builds the next request ({@code GoogleGenAiChatModel#parseJsonToMap}) and fails the whole turn on
 * anything else, and the hosted pool is Gemini-first. The earlier plain-text form
 * ({@code [tool result cleared to save context: ...]}) is still recognised as already cleared.
 */
public final class ToolResultClearing {

    public static final int DEFAULT_THRESHOLD_CHARS = 24_000;
    public static final int DEFAULT_KEEP_LAST = 3;

    /** The plain-text placeholder this class wrote before it wrote JSON; recognised, never written. */
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
    // The parked-turn marker is likewise preserved: the loop limiter reads it to end an invocation
    // whose model keeps calling tools while the turn waits on a human approval.
    private static final String PARKED_MARKER = "approval_parked";

    private static final ObjectMapper JSON = JsonMappers.plain();

    private final boolean enabled;
    private final int thresholdChars;
    private final int keepLast;
    private final String restoreHint;

    public ToolResultClearing(boolean enabled, int thresholdChars, int keepLast) {
        this(enabled, thresholdChars, keepLast, new ToolLoopProperties.ResultClearing().getRestoreHint());
    }

    /** @param restoreHint the placeholder's {@code restore} text ({@code app.chat.tools.result-clearing.restore-hint}) */
    public ToolResultClearing(boolean enabled, int thresholdChars, int keepLast, String restoreHint) {
        this.enabled = enabled;
        this.thresholdChars = thresholdChars > 0 ? thresholdChars : DEFAULT_THRESHOLD_CHARS;
        this.keepLast = Math.max(keepLast, 0);
        this.restoreHint = restoreHint == null ? "" : restoreHint;
    }

    /** Built from {@code app.chat.tools.result-clearing.*}. */
    public static ToolResultClearing from(ToolLoopProperties.ResultClearing properties) {
        return new ToolResultClearing(
            properties.isEnabled(), properties.getThresholdChars(), properties.getKeepLast(),
            properties.getRestoreHint());
    }

    /**
     * This clearing with a different switch and/or threshold (null keeps the current one): how one run applies its own
     * tenant's and agent's settings over the process-wide ones. The keep-last count and the placeholder wording stay.
     */
    ToolResultClearing withOverrides(Boolean enabledOverride, Integer thresholdCharsOverride) {
        if (enabledOverride == null && thresholdCharsOverride == null) {
            return this;
        }
        return new ToolResultClearing(
            enabledOverride != null ? enabledOverride : enabled,
            thresholdCharsOverride != null ? thresholdCharsOverride : thresholdChars,
            keepLast,
            restoreHint);
    }

    public static ToolResultClearing disabled() {
        return new ToolResultClearing(false, DEFAULT_THRESHOLD_CHARS, DEFAULT_KEEP_LAST);
    }

    /** The JSON stand-in for a cleared result of {@code chars} characters from {@code toolName}. */
    String placeholder(String toolName, int chars) {
        String tool = toolName == null ? "tool" : toolName;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("context_cleared", true);
        body.put("tool", tool);
        body.put("chars", chars);
        body.put("restore", restoreHint);
        try {
            return JSON.writeValueAsString(body);
        } catch (Exception e) {
            // A String/Integer/Boolean map always serialises; keep the marker valid anyway.
            return CONTEXT_CLEARED_JSON_PREFIX + ",\"tool\":\"" + tool.replace("\"", "'") + "\"}";
        }
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
        return data != null && !alreadyCleared && !data.contains(BUDGET_MARKER) && !data.contains(PARKED_MARKER);
    }

    private static int length(String s) {
        return s == null ? 0 : s.length();
    }
}
