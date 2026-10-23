package com.agenticai.core.agents.runtime;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.Objects;

/**
 * One layer of agent-runtime overrides: every field is nullable and {@code null} means "not set here,
 * the layer below decides". The same value object is the agent's override (embedded in {@code Agent},
 * columns on the {@code agent} table), the tenant's defaults (the same columns on {@code
 * tenant_runtime_limits}) and the API body of both. Immutable: the {@code with...} methods return
 * copies, so Hibernate's dirty check sees every change.
 *
 * <p>Meaning, bounds and defaults of each field are in {@link RuntimeSetting}, {@code
 * docs/configuration.md} ("Agent runtime limits") and {@code app.agent-runtime.bounds.*}.
 */
@Embeddable
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RuntimeLimits {

    private static final RuntimeLimits NONE = new RuntimeLimits();

    @Column(name = "max_tool_steps")
    private Integer maxToolSteps;

    @Column(name = "turn_timeout_seconds")
    private Integer turnTimeoutSeconds;

    @Column(name = "tool_timeout_seconds")
    private Integer toolTimeoutSeconds;

    @Column(name = "tool_output_max_chars")
    private Integer toolOutputMaxChars;

    @Column(name = "retrieval_top_k")
    private Integer retrievalTopK;

    @Column(name = "retrieval_min_score")
    private Double retrievalMinScore;

    @Column(name = "continuation_passes")
    private Integer continuationPasses;

    /** JPA, and the "nothing overridden" starting state. */
    protected RuntimeLimits() {}

    @JsonCreator
    public RuntimeLimits(
            @JsonProperty("maxToolSteps") Integer maxToolSteps,
            @JsonProperty("turnTimeoutSeconds") Integer turnTimeoutSeconds,
            @JsonProperty("toolTimeoutSeconds") Integer toolTimeoutSeconds,
            @JsonProperty("toolOutputMaxChars") Integer toolOutputMaxChars,
            @JsonProperty("retrievalTopK") Integer retrievalTopK,
            @JsonProperty("retrievalMinScore") Double retrievalMinScore,
            @JsonProperty("continuationPasses") Integer continuationPasses) {
        this.maxToolSteps = maxToolSteps;
        this.turnTimeoutSeconds = turnTimeoutSeconds;
        this.toolTimeoutSeconds = toolTimeoutSeconds;
        this.toolOutputMaxChars = toolOutputMaxChars;
        this.retrievalTopK = retrievalTopK;
        this.retrievalMinScore = retrievalMinScore;
        this.continuationPasses = continuationPasses;
    }

    /** No setting overridden. */
    public static RuntimeLimits none() {
        return NONE;
    }

    /** True when no setting is overridden. */
    @JsonIgnore
    public boolean isEmpty() {
        return maxToolSteps == null
                && turnTimeoutSeconds == null
                && toolTimeoutSeconds == null
                && toolOutputMaxChars == null
                && retrievalTopK == null
                && retrievalMinScore == null
                && continuationPasses == null;
    }

    /** The value this layer sets for {@code setting}, or null. */
    public Number get(RuntimeSetting setting) {
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

    /** A copy with {@code setting} set to {@code value} (null clears it). */
    public RuntimeLimits with(RuntimeSetting setting, Number value) {
        Integer asInt = value == null ? null : value.intValue();
        return switch (setting) {
            case MAX_TOOL_STEPS -> new RuntimeLimits(asInt, turnTimeoutSeconds, toolTimeoutSeconds,
                    toolOutputMaxChars, retrievalTopK, retrievalMinScore, continuationPasses);
            case TURN_TIMEOUT_SECONDS -> new RuntimeLimits(maxToolSteps, asInt, toolTimeoutSeconds,
                    toolOutputMaxChars, retrievalTopK, retrievalMinScore, continuationPasses);
            case TOOL_TIMEOUT_SECONDS -> new RuntimeLimits(maxToolSteps, turnTimeoutSeconds, asInt,
                    toolOutputMaxChars, retrievalTopK, retrievalMinScore, continuationPasses);
            case TOOL_OUTPUT_MAX_CHARS -> new RuntimeLimits(maxToolSteps, turnTimeoutSeconds,
                    toolTimeoutSeconds, asInt, retrievalTopK, retrievalMinScore, continuationPasses);
            case RETRIEVAL_TOP_K -> new RuntimeLimits(maxToolSteps, turnTimeoutSeconds,
                    toolTimeoutSeconds, toolOutputMaxChars, asInt, retrievalMinScore, continuationPasses);
            case RETRIEVAL_MIN_SCORE -> new RuntimeLimits(maxToolSteps, turnTimeoutSeconds,
                    toolTimeoutSeconds, toolOutputMaxChars, retrievalTopK,
                    value == null ? null : value.doubleValue(), continuationPasses);
            case CONTINUATION_PASSES -> new RuntimeLimits(maxToolSteps, turnTimeoutSeconds,
                    toolTimeoutSeconds, toolOutputMaxChars, retrievalTopK, retrievalMinScore, asInt);
        };
    }

    /** A request-layer cap that only limits tool calls (an agent job's remaining budget). */
    public static RuntimeLimits capToolSteps(int steps) {
        return NONE.with(RuntimeSetting.MAX_TOOL_STEPS, steps);
    }

    public Integer getMaxToolSteps() {
        return maxToolSteps;
    }

    public Integer getTurnTimeoutSeconds() {
        return turnTimeoutSeconds;
    }

    public Integer getToolTimeoutSeconds() {
        return toolTimeoutSeconds;
    }

    public Integer getToolOutputMaxChars() {
        return toolOutputMaxChars;
    }

    public Integer getRetrievalTopK() {
        return retrievalTopK;
    }

    public Double getRetrievalMinScore() {
        return retrievalMinScore;
    }

    public Integer getContinuationPasses() {
        return continuationPasses;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof RuntimeLimits other
                && Objects.equals(maxToolSteps, other.maxToolSteps)
                && Objects.equals(turnTimeoutSeconds, other.turnTimeoutSeconds)
                && Objects.equals(toolTimeoutSeconds, other.toolTimeoutSeconds)
                && Objects.equals(toolOutputMaxChars, other.toolOutputMaxChars)
                && Objects.equals(retrievalTopK, other.retrievalTopK)
                && Objects.equals(retrievalMinScore, other.retrievalMinScore)
                && Objects.equals(continuationPasses, other.continuationPasses);
    }

    @Override
    public int hashCode() {
        return Objects.hash(maxToolSteps, turnTimeoutSeconds, toolTimeoutSeconds, toolOutputMaxChars,
                retrievalTopK, retrievalMinScore, continuationPasses);
    }

    @Override
    public String toString() {
        return "RuntimeLimits[maxToolSteps=" + maxToolSteps + ", turnTimeoutSeconds=" + turnTimeoutSeconds
                + ", toolTimeoutSeconds=" + toolTimeoutSeconds + ", toolOutputMaxChars=" + toolOutputMaxChars
                + ", retrievalTopK=" + retrievalTopK + ", retrievalMinScore=" + retrievalMinScore
                + ", continuationPasses=" + continuationPasses + "]";
    }
}
