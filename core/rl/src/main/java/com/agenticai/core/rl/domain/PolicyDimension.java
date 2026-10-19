package com.agenticai.core.rl.domain;

/**
 * The decision dimensions an {@link AgentPolicy} may optimize with a
 * contextual bandit. Every value here is deliberately a SAFE, non-side-
 * effecting choice - selecting one arm over another changes only how the
 * agent thinks/answers, never what irreversible action it takes in the world.
 *
 * <p><b>A tool EXECUTION / side-effecting choice is never modelled as a
 * bandit arm.</b> {@link #TOOL_SUBSET} governs only which read/known tools are
 * OFFERED to the model for a turn (a capability-scoping choice), not whether a
 * consequential tool is actually invoked - that always stays under the
 * existing approval/guardrail path, outside this subsystem.
 */
public enum PolicyDimension {
    /** Which provider/model handles the turn (arm_config_json = {"model": "..."}). */
    MODEL_ROUTE,
    /** FAST vs CAREFUL vs RIGOROUS reasoning depth. */
    REASONING_DEPTH,
    /** Which system-prompt variant to use (arm_config_json references a prompt version id). */
    PROMPT_VARIANT,
    /** Which subset of read/known tools to OFFER the model (never which tool to execute). */
    TOOL_SUBSET,
    /** Generation params (temperature/topP/maxTokens) preset. */
    GEN_PARAMS
}
