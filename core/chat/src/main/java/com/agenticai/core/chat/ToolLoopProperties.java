package com.agenticai.core.chat;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.chat.tools.*}: the limits and wording of the tool loop that Spring AI runs inside every
 * chat model invocation ({@link ArgumentSanitizingToolCallingManager}).
 *
 * <p><b>Rounds and calls.</b> A <em>round</em> is one model-then-tools iteration inside ONE model
 * invocation; a <em>call</em> is one tool invocation. The budget a turn actually gets is in calls:
 * {@code app.guardrails.max-tool-calls-per-turn} (scaled by the agent's autonomy tier), counted by
 * {@code GuardedToolCallback} across every model pass of the turn. {@link #getMaxRoundsPerInvocation()}
 * is only a backstop for loops that make no countable calls (a model inventing tool names, calls
 * refused before they run). Every executed round makes at least one call, so a round cap below the call
 * ceiling would silently end turns the call ceiling was configured to allow; that is why {@code 0}, the
 * default, means "derive it from the call ceiling" (see {@link #effectiveMaxRounds}) rather than a
 * number of its own.
 *
 * <p>The texts are model-facing or user-facing, so they are configuration rather than Java literals;
 * defaults are read from {@code /chat/tool-loop.properties} (the same text is in {@code
 * application.yml}). Registered by {@link ToolLoopConfiguration}.
 */
@ConfigurationProperties(prefix = "app.chat.tools")
public class ToolLoopProperties {

    private static final String DEFAULTS_RESOURCE = "/chat/tool-loop.properties";

    private static final Properties DEFAULTS = loadDefaults();

    /**
     * Tool rounds one model invocation may run before the soft stop. {@code 0} (default) = derived: the
     * highest per-turn call ceiling ({@code max-tool-calls-per-turn} times the autonomous multiplier)
     * plus the step-limit grace, so the call policy is what ends a turn; a positive number is an
     * explicit cap and wins even when it is lower than that. A run whose passes carry their earlier
     * tool rounds ({@link ToolLoopTranscript#roundsSpentBefore}) is held to the cap across the whole
     * run, so a continuation pass cannot refill it.
     */
    private int maxRoundsPerInvocation = Integer.parseInt(DEFAULTS.getProperty("max-rounds-per-invocation"));

    /** Calls to tool names that were never offered that one model invocation may make before the soft stop. */
    private int maxUnknownCallsPerInvocation =
        Integer.parseInt(DEFAULTS.getProperty("max-unknown-calls-per-invocation"));

    /**
     * Cumulative prompt tokens one run's tool loop may send before the soft stop
     * ({@code app.chat.tools.max-prompt-tokens-per-turn}); 0 = uncapped. Applied per run through the
     * run's transcript ({@link ToolLoopTranscript#promptTokenBudget}), so a tenant or agent override
     * wins over the platform value.
     */
    private long maxPromptTokensPerTurn =
        Long.parseLong(DEFAULTS.getProperty("max-prompt-tokens-per-turn"));

    /** The tool result for every call after the round cap; {@code {rounds}} is how many rounds were used. */
    private String budgetExhaustedMessage = DEFAULTS.getProperty("budget-exhausted-message");

    /** The closing reply when the model keeps calling tools after {@link #budgetExhaustedMessage}. */
    private String hardStopReply = DEFAULTS.getProperty("hard-stop-reply");

    private ResultClearing resultClearing = new ResultClearing();

    private static Properties loadDefaults() {
        Properties defaults = new Properties();
        try (InputStream in = ToolLoopProperties.class.getResourceAsStream(DEFAULTS_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(DEFAULTS_RESOURCE + " is missing from the chat module");
            }
            defaults.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return defaults;
    }

    /**
     * The round cap to enforce: the explicit {@link #getMaxRoundsPerInvocation()} when set, otherwise
     * enough rounds that the CALL policy always gets to run its course first - the highest per-turn call
     * ceiling, plus the calls answered with the budget-exhausted notice past it, plus one for the call
     * that is stopped. A round makes at least one call, so no turn can reach this many rounds without
     * having met the call policy; the round cap stays a backstop for loops that make no countable calls.
     *
     * @param callsPerTurn {@code app.guardrails.max-tool-calls-per-turn}
     * @param autonomousMultiplier {@code app.guardrails.step-limit.autonomous-multiplier}
     * @param graceCalls {@code app.guardrails.step-limit.grace-calls}
     */
    public int effectiveMaxRounds(int callsPerTurn, int autonomousMultiplier, int graceCalls) {
        if (maxRoundsPerInvocation > 0) {
            return maxRoundsPerInvocation;
        }
        return Math.max(1, callsPerTurn) * Math.max(1, autonomousMultiplier) + Math.max(0, graceCalls) + 1;
    }

    public int getMaxRoundsPerInvocation() {
        return maxRoundsPerInvocation;
    }

    public void setMaxRoundsPerInvocation(int maxRoundsPerInvocation) {
        this.maxRoundsPerInvocation = Math.max(0, maxRoundsPerInvocation);
    }

    public int getMaxUnknownCallsPerInvocation() {
        return maxUnknownCallsPerInvocation;
    }

    public void setMaxUnknownCallsPerInvocation(int maxUnknownCallsPerInvocation) {
        this.maxUnknownCallsPerInvocation = maxUnknownCallsPerInvocation;
    }

    public long getMaxPromptTokensPerTurn() {
        return maxPromptTokensPerTurn;
    }

    public void setMaxPromptTokensPerTurn(long maxPromptTokensPerTurn) {
        this.maxPromptTokensPerTurn = Math.max(0, maxPromptTokensPerTurn);
    }

    public String getBudgetExhaustedMessage() {
        return budgetExhaustedMessage;
    }

    public void setBudgetExhaustedMessage(String budgetExhaustedMessage) {
        this.budgetExhaustedMessage = budgetExhaustedMessage;
    }

    public String getHardStopReply() {
        return hardStopReply;
    }

    public void setHardStopReply(String hardStopReply) {
        this.hardStopReply = hardStopReply;
    }

    public ResultClearing getResultClearing() {
        return resultClearing;
    }

    public void setResultClearing(ResultClearing resultClearing) {
        this.resultClearing = resultClearing == null ? new ResultClearing() : resultClearing;
    }

    /** {@code app.chat.tools.result-clearing.*}: see {@link ToolResultClearing}. */
    public static class ResultClearing {

        /** Kill switch: false resends every earlier tool result on each loop iteration. */
        private boolean enabled = true;

        /** Tool results of one reply above this many characters in total start being cleared. */
        private int thresholdChars = ToolResultClearing.DEFAULT_THRESHOLD_CHARS;

        /** The newest this many tool results are never cleared. */
        private int keepLast = ToolResultClearing.DEFAULT_KEEP_LAST;

        /** The {@code restore} field of the placeholder that replaces a cleared result (model-facing). */
        private String restoreHint = DEFAULTS.getProperty("restore-hint");

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getThresholdChars() {
            return thresholdChars;
        }

        public void setThresholdChars(int thresholdChars) {
            this.thresholdChars = thresholdChars;
        }

        public int getKeepLast() {
            return keepLast;
        }

        public void setKeepLast(int keepLast) {
            this.keepLast = keepLast;
        }

        public String getRestoreHint() {
            return restoreHint;
        }

        public void setRestoreHint(String restoreHint) {
            this.restoreHint = restoreHint;
        }
    }
}
