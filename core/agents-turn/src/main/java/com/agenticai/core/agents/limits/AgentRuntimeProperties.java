package com.agenticai.core.agents.limits;

import com.agenticai.core.agents.runtime.RuntimeSetting;
import com.agenticai.core.agents.runtime.RuntimeLimits;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.agent-runtime.*}: how the per-tenant and per-agent runtime limits ({@link RuntimeLimits}) are
 * bounded, and the platform defaults of the settings that had no key of their own before.
 *
 * <p><b>Where each platform default lives.</b> {@code AgentRuntimeSettingsResolver} reads the platform layer
 * from the keys the limits always had, so nothing was renamed and no operator's file changes meaning:
 * <ul>
 *   <li>{@code maxToolSteps}: {@code app.guardrails.max-tool-calls-per-turn}, scaled by the autonomy tier with
 *       {@code app.guardrails.step-limit.*};
 *   <li>{@code turnTimeoutSeconds}: {@code app.llm.http.buffered-turn-timeout};
 *   <li>{@code toolOutputMaxChars}: {@code app.tools.output.max-chars};
 *   <li>{@code continuationPasses}: {@code app.chat.continuation.max-passes};
 *   <li>{@code toolTimeoutSeconds}, {@code retrievalTopK}, {@code retrievalMinScore}: {@link #getDefaults()}
 *       below (new keys; their defaults keep the behaviour from before the setting existed).
 * </ul>
 *
 * <p><b>Bounds.</b> {@link #getBounds()} is the least and most a tenant or an agent may set each value to. A
 * write outside the range is refused with a message naming the setting; a value stored earlier is clamped into
 * the range when it is read, so tightening a bound tightens the overrides already stored. The platform layer
 * is the operator's own and is never clamped.
 *
 * <p>Registered by {@link AgentRuntimeConfiguration}.
 */
@ConfigurationProperties(prefix = "app.agent-runtime")
public class AgentRuntimeProperties {

    /**
     * How long a tenant's runtime defaults are cached before they are read again. A change made on one
     * replica reaches the others within this time; the replica that took the change sees it at once.
     */
    private Duration cacheTtl = Duration.ofSeconds(10);

    /**
     * What the model is told when a tool call outlives the turn's tool timeout and is stopped; {@code {tool}} is
     * the tool name and {@code {seconds}} the limit. Model-facing text, so configuration rather than a literal.
     */
    private String toolTimeoutMessage =
            "Tool '{tool}' did not finish within {seconds} seconds and was stopped. Whether it had any effect is"
                    + " unknown, so do not assume it succeeded. Say so to the user, or try a narrower request.";

    private final Defaults defaults = new Defaults();

    private final Bounds bounds = new Bounds();

    /** Platform defaults of the settings that had no key of their own. */
    public static class Defaults {

        /** Seconds one tool call may run before it is stopped; 0 = no limit (each tool keeps its own timeout). */
        private int toolTimeoutSeconds = 0;

        /** Knowledge-base chunks one search returns. */
        private int retrievalTopK = 3;

        /** Least relevance a returned chunk needs; 0 = return every hit. */
        private double retrievalMinScore = 0.0;

        public int getToolTimeoutSeconds() {
            return toolTimeoutSeconds;
        }

        public void setToolTimeoutSeconds(int toolTimeoutSeconds) {
            this.toolTimeoutSeconds = Math.max(0, toolTimeoutSeconds);
        }

        public int getRetrievalTopK() {
            return retrievalTopK;
        }

        public void setRetrievalTopK(int retrievalTopK) {
            this.retrievalTopK = Math.max(1, retrievalTopK);
        }

        public double getRetrievalMinScore() {
            return retrievalMinScore;
        }

        public void setRetrievalMinScore(double retrievalMinScore) {
            this.retrievalMinScore = Math.max(0.0, retrievalMinScore);
        }
    }

    /** Inclusive range one setting may take. */
    public static class Range {

        private double min;
        private double max;

        public Range() {}

        public Range(double min, double max) {
            this.min = min;
            this.max = max;
        }

        public double getMin() {
            return min;
        }

        public void setMin(double min) {
            this.min = min;
        }

        public double getMax() {
            return max;
        }

        public void setMax(double max) {
            this.max = max;
        }
    }

    /** One range per {@link RuntimeSetting}, keyed in YAML by {@link RuntimeSetting#configName()}. */
    public static class Bounds {

        private Range maxToolSteps = new Range(1, 500);
        private Range turnTimeoutSeconds = new Range(10, 3600);
        private Range toolTimeoutSeconds = new Range(1, 1800);
        private Range toolOutputMaxChars = new Range(500, 200_000);
        private Range retrievalTopK = new Range(1, 50);
        private Range retrievalMinScore = new Range(0, 1);
        private Range continuationPasses = new Range(0, 16);

        /** The range for {@code setting}. */
        public Range of(RuntimeSetting setting) {
            return switch (setting) {
                case MAX_TOOL_STEPS -> maxToolSteps;
                case TURN_TIMEOUT_SECONDS -> turnTimeoutSeconds;
                case TOOL_TIMEOUT_SECONDS -> toolTimeoutSeconds;
                case TOOL_OUTPUT_MAX_CHARS -> toolOutputMaxChars;
                case RETRIEVAL_TOP_K -> retrievalTopK;
                case RETRIEVAL_MIN_SCORE -> retrievalMinScore;
                case CONTINUATION_PASSES -> continuationPasses;
            };
        }

        public Range getMaxToolSteps() {
            return maxToolSteps;
        }

        public void setMaxToolSteps(Range maxToolSteps) {
            this.maxToolSteps = maxToolSteps;
        }

        public Range getTurnTimeoutSeconds() {
            return turnTimeoutSeconds;
        }

        public void setTurnTimeoutSeconds(Range turnTimeoutSeconds) {
            this.turnTimeoutSeconds = turnTimeoutSeconds;
        }

        public Range getToolTimeoutSeconds() {
            return toolTimeoutSeconds;
        }

        public void setToolTimeoutSeconds(Range toolTimeoutSeconds) {
            this.toolTimeoutSeconds = toolTimeoutSeconds;
        }

        public Range getToolOutputMaxChars() {
            return toolOutputMaxChars;
        }

        public void setToolOutputMaxChars(Range toolOutputMaxChars) {
            this.toolOutputMaxChars = toolOutputMaxChars;
        }

        public Range getRetrievalTopK() {
            return retrievalTopK;
        }

        public void setRetrievalTopK(Range retrievalTopK) {
            this.retrievalTopK = retrievalTopK;
        }

        public Range getRetrievalMinScore() {
            return retrievalMinScore;
        }

        public void setRetrievalMinScore(Range retrievalMinScore) {
            this.retrievalMinScore = retrievalMinScore;
        }

        public Range getContinuationPasses() {
            return continuationPasses;
        }

        public void setContinuationPasses(Range continuationPasses) {
            this.continuationPasses = continuationPasses;
        }
    }

    public Duration getCacheTtl() {
        return cacheTtl;
    }

    public void setCacheTtl(Duration cacheTtl) {
        this.cacheTtl = cacheTtl == null || cacheTtl.isNegative() ? Duration.ZERO : cacheTtl;
    }

    public String getToolTimeoutMessage() {
        return toolTimeoutMessage;
    }

    public void setToolTimeoutMessage(String toolTimeoutMessage) {
        if (toolTimeoutMessage != null && !toolTimeoutMessage.isBlank()) {
            this.toolTimeoutMessage = toolTimeoutMessage;
        }
    }

    public Defaults getDefaults() {
        return defaults;
    }

    public Bounds getBounds() {
        return bounds;
    }
}
