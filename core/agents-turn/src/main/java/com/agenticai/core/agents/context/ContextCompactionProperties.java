package com.agenticai.core.agents.context;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.context.compaction.*}, the part that used to be constants: how the engine estimates prompt tokens
 * (per message, per image, per tool call), the context-window fallback by model name, and how much of a
 * restored tool result is returned. The other keys of this prefix ({@code enabled}, {@code clear-at}, {@code
 * compact-at}, ...) are still read by {@link ChatContextCompaction}; their code defaults are the {@code
 * DEFAULT_*} constants here, so each number is defined once.
 *
 * <p>The defaults are the old numbers. {@code application.yml} repeats them and {@code
 * ContextCompactionDefaultsTest} pins the two together. Registered by {@link ContextCompactionConfiguration}.
 */
@ConfigurationProperties(prefix = "app.context.compaction")
public class ContextCompactionProperties {

    /** Fraction of the window above which old tool results are cleared. */
    public static final double DEFAULT_CLEAR_AT = 0.6;

    /** Fraction of the window above which the oldest conversation is summarised. */
    public static final double DEFAULT_COMPACT_AT = 0.85;

    /** Newest tool results that are never cleared. */
    public static final int DEFAULT_KEEP_RECENT_TOOL_RESULTS = 3;

    /** Token budget of one compaction summary. */
    public static final int DEFAULT_SUMMARY_TOKENS = 800;

    /** Window of a model neither the catalog nor a name rule knows. */
    public static final int DEFAULT_WINDOW_TOKENS = 128_000;

    /** Estimated tokens of one cleared-result placeholder. */
    public static final int DEFAULT_PLACEHOLDER_TOKENS = 48;

    /** Characters of one restored tool result; safely under the output validator's cap. */
    public static final int DEFAULT_RESTORE_RESULT_MAX_CHARS = 16 * 1024;

    /** Tokens every message costs beyond its text (role markers, framing). */
    private int messageOverheadTokens = 4;

    /** Tokens one attached image, audio or video costs. */
    private int mediaTokens = 1000;

    /** Tokens a tool call or tool response costs beyond its name, arguments and text. */
    private int responseOverheadTokens = 6;

    /** Tokens of a summary message beyond its body: the opening line and the per-message overhead. */
    private int summaryFrameTokens = 40;

    /** Estimated tokens of one cleared-result placeholder; the planner will not clear a result smaller than twice this. */
    private int placeholderTokens = DEFAULT_PLACEHOLDER_TOKENS;

    /** Characters of one tool result {@code restore_tool_result} returns; the rest is cut with a marker. */
    private int restoreResultMaxChars = DEFAULT_RESTORE_RESULT_MAX_CHARS;

    /**
     * Context window by model name, for a model the provider catalog gives no window. The first rule that
     * matches the lower-cased model id wins; a model no rule matches gets {@code default-window-tokens}. Setting
     * this list replaces the shipped one.
     */
    private List<WindowRule> windowRules = defaultWindowRules();

    /** A model-name rule: matches when the id contains any {@code contains} text or starts with any {@code starts-with} text. */
    public static class WindowRule {

        private List<String> contains = new ArrayList<>();
        private List<String> startsWith = new ArrayList<>();
        private int tokens = DEFAULT_WINDOW_TOKENS;

        public List<String> getContains() {
            return contains;
        }

        public void setContains(List<String> contains) {
            this.contains = contains == null ? new ArrayList<>() : new ArrayList<>(contains);
        }

        public List<String> getStartsWith() {
            return startsWith;
        }

        public void setStartsWith(List<String> startsWith) {
            this.startsWith = startsWith == null ? new ArrayList<>() : new ArrayList<>(startsWith);
        }

        public int getTokens() {
            return tokens;
        }

        public void setTokens(int tokens) {
            this.tokens = Math.max(1, tokens);
        }

        /** True when {@code lowerCaseModelId} satisfies this rule. */
        public boolean matches(String lowerCaseModelId) {
            for (String text : contains) {
                if (text != null && !text.isEmpty() && lowerCaseModelId.contains(text.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
            for (String text : startsWith) {
                if (text != null && !text.isEmpty() && lowerCaseModelId.startsWith(text.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
            return false;
        }

        static WindowRule of(int tokens, List<String> contains, List<String> startsWith) {
            WindowRule rule = new WindowRule();
            rule.setTokens(tokens);
            rule.setContains(contains);
            rule.setStartsWith(startsWith);
            return rule;
        }
    }

    /**
     * Conservative floors, not vendor maximums: an underestimate only makes clearing start a little earlier on a
     * huge-window model, while an overestimate would let a run hit the real limit before any compaction fires.
     */
    private static List<WindowRule> defaultWindowRules() {
        List<WindowRule> rules = new ArrayList<>();
        rules.add(WindowRule.of(200_000, List.of("claude"), List.of()));
        rules.add(WindowRule.of(200_000, List.of("gemini"), List.of()));
        rules.add(WindowRule.of(
                128_000,
                List.of("gpt-5", "gpt-4o", "gpt-4.1", "gpt-4-turbo", "/o1", "/o3", "/o4"),
                List.of("o1", "o3", "o4")));
        return rules;
    }

    /** The shipped settings, for code that is handed none. */
    public static ContextCompactionProperties shipped() {
        return SHIPPED;
    }

    private static final ContextCompactionProperties SHIPPED = new ContextCompactionProperties();

    public int getMessageOverheadTokens() {
        return messageOverheadTokens;
    }

    public void setMessageOverheadTokens(int messageOverheadTokens) {
        this.messageOverheadTokens = Math.max(0, messageOverheadTokens);
    }

    public int getMediaTokens() {
        return mediaTokens;
    }

    public void setMediaTokens(int mediaTokens) {
        this.mediaTokens = Math.max(0, mediaTokens);
    }

    public int getResponseOverheadTokens() {
        return responseOverheadTokens;
    }

    public void setResponseOverheadTokens(int responseOverheadTokens) {
        this.responseOverheadTokens = Math.max(0, responseOverheadTokens);
    }

    public int getSummaryFrameTokens() {
        return summaryFrameTokens;
    }

    public void setSummaryFrameTokens(int summaryFrameTokens) {
        this.summaryFrameTokens = Math.max(0, summaryFrameTokens);
    }

    public int getPlaceholderTokens() {
        return placeholderTokens;
    }

    public void setPlaceholderTokens(int placeholderTokens) {
        this.placeholderTokens = Math.max(1, placeholderTokens);
    }

    public int getRestoreResultMaxChars() {
        return restoreResultMaxChars;
    }

    public void setRestoreResultMaxChars(int restoreResultMaxChars) {
        this.restoreResultMaxChars = Math.max(1, restoreResultMaxChars);
    }

    public List<WindowRule> getWindowRules() {
        return windowRules;
    }

    public void setWindowRules(List<WindowRule> windowRules) {
        this.windowRules = windowRules == null ? new ArrayList<>() : new ArrayList<>(windowRules);
    }
}
