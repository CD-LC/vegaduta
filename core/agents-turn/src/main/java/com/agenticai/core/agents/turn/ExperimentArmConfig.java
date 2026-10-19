package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.core.ReasoningDepth;
import java.util.Set;

/**
 * Immutable view over one RL policy arm's config JSON (MODEL_ROUTE / TOOL_SUBSET / PROMPT_VARIANT /
 * GEN_PARAMS / REASONING_DEPTH). Each accessor reads the one field its dimension owns via {@link
 * ChatArmConfig}, so a malformed config still degrades to {@code null} (and logs) exactly as the
 * static readers always did; {@link RlArmSelection} asks only for the dimension it selected, so
 * nothing is parsed (or logged) that was not parsed before.
 *
 * @param json the arm's {@code armConfigJson}; may be null or blank (every accessor then yields
 *     null)
 */
record ExperimentArmConfig(String json) {

    static ExperimentArmConfig of(String armConfigJson) {
        return new ExperimentArmConfig(armConfigJson);
    }

    /** MODEL_ROUTE: the model id, or null. */
    String model() {
        return ChatArmConfig.modelFromArmConfig(json);
    }

    /** TOOL_SUBSET: the allowed tool names, or null when unusable. */
    Set<String> toolNames() {
        return ChatArmConfig.toolNamesFromArmConfig(json);
    }

    /** PROMPT_VARIANT: the system-prompt override, or null when unusable. */
    String systemPrompt() {
        return ChatArmConfig.systemPromptFromArmConfig(json);
    }

    /** GEN_PARAMS: the temperature/topP preset, or null when unusable. */
    AgentChatService.GenParams genParams() {
        return ChatArmConfig.genParamsFromArmConfig(json);
    }

    /** REASONING_DEPTH: the depth, or null when unusable. */
    ReasoningDepth reasoningDepth() {
        return ChatArmConfig.reasoningDepthFromArmConfig(json);
    }
}
