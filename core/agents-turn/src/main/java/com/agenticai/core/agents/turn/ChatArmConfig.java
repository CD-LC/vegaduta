package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.rl.domain.PolicyDimension;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Readers for the RL policy-arm config JSON (MODEL_ROUTE / TOOL_SUBSET / PROMPT_VARIANT /
 * GEN_PARAMS / REASONING_DEPTH), extracted verbatim from {@link AgentChatService} (extract-only
 * split). {@code AgentChatService} keeps package-private static delegates so {@link
 * RlModelRouteArmValidator} and the tests keep their existing entry points.
 */
final class ChatArmConfig {

    private ChatArmConfig() {}

    private static final Logger log = LoggerFactory.getLogger(AgentChatService.class);

    // Reads {"model":"..."} out of a MODEL_ROUTE arm's config; static + shared,
    // ObjectMapper is thread-safe for reads.
    private static final ObjectMapper RL_ARM_MAPPER = JsonMappers.plain();

    /**
     * Reads the {@code "model"} string out of a MODEL_ROUTE arm's config JSON; null on
     * absent/blank/malformed.
     *
     * <p>Package-private, not private, so {@link RlModelRouteArmValidator} can validate a PROPOSED
     * arm with the exact reader that will later APPLY it. The write side previously had no reader
     * at all (arms were unreachable), and the failure mode of the two drifting apart is the
     * nastiest kind: an arm that validates, persists, gets selected, silently parses to null here,
     * and leaves the turn on the agent's default model while consuming exploration budget and
     * recording a reward against the wrong decision.
     */
    static String modelFromArmConfig(String armConfigJson) {
        if (armConfigJson == null || armConfigJson.isBlank()) {
            return null;
        }
        try {
            JsonNode model = RL_ARM_MAPPER.readTree(armConfigJson).get("model");
            return model == null || model.isNull() ? null : model.asText();
        } catch (Exception e) {
            // The javadoc above calls the silent-null outcome "the nastiest kind" of failure -
            // an arm that validates, persists, gets selected, parses to null here, and then
            // burns exploration budget while recording a reward against the agent's default
            // model instead. Returning null is still correct (the caller's fallback is the
            // default model), but it must leave a trace, otherwise that exact drift is
            // invisible in production. The same applies to the four sibling readers below
            // (toolNamesFromArmConfig / systemPromptFromArmConfig / genParamsFromArmConfig /
            // reasoningDepthFromArmConfig), whose catch blocks swallow identically.
            log.warn(
                    "MODEL_ROUTE arm config is not parseable JSON - ignoring the arm and "
                            + "falling back to the agent's default model; the reward will be recorded "
                            + "against a decision that never took effect. armConfigJson={}",
                    armConfigJson,
                    e);
            return null;
        }
    }

    /**
     * Reads {@code {"toolNames": ["...", ...]}} out of a TOOL_SUBSET arm's config JSON; null on
     * absent/blank/malformed/empty, same "never leave the turn on a value the config didn't
     * actually name" posture as {@link #modelFromArmConfig}.
     */
    static Set<String> toolNamesFromArmConfig(String armConfigJson) {
        if (armConfigJson == null || armConfigJson.isBlank()) {
            return null;
        }
        try {
            JsonNode names = RL_ARM_MAPPER.readTree(armConfigJson).get("toolNames");
            if (names == null || !names.isArray() || names.isEmpty()) {
                return null;
            }
            Set<String> result = new java.util.HashSet<>();
            names.forEach(
                    n -> {
                        if (n.isTextual() && !n.asText().isBlank()) {
                            result.add(n.asText());
                        }
                    });
            return result.isEmpty() ? null : result;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Reads the {@code "systemPrompt"} string out of a PROMPT_VARIANT arm's config JSON; null on
     * absent/blank/malformed. The literal alternate prompt text, not a version id - see {@link
     * PolicyDimension}'s javadoc for why (no versioned-prompt store exists in this codebase).
     */
    static String systemPromptFromArmConfig(String armConfigJson) {
        if (armConfigJson == null || armConfigJson.isBlank()) {
            return null;
        }
        try {
            JsonNode prompt = RL_ARM_MAPPER.readTree(armConfigJson).get("systemPrompt");
            return prompt == null || prompt.isNull() || prompt.asText().isBlank()
                    ? null
                    : prompt.asText();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Reads {@code {"temperature": 0.x, "topP": 0.x}} out of a GEN_PARAMS arm's config JSON; null
     * on absent/blank/malformed/out-of-range.
     */
    static AgentChatService.GenParams genParamsFromArmConfig(String armConfigJson) {
        if (armConfigJson == null || armConfigJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = RL_ARM_MAPPER.readTree(armConfigJson);
            JsonNode temp = node.get("temperature");
            JsonNode topP = node.get("topP");
            if (temp == null || topP == null || !temp.isNumber() || !topP.isNumber()) {
                return null;
            }
            double temperature = temp.asDouble();
            double p = topP.asDouble();
            if (temperature < 0 || temperature > 2 || p < 0 || p > 1) {
                return null;
            }
            return new AgentChatService.GenParams(temperature, p);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Reads the {@code "depth"} string out of a REASONING_DEPTH arm's config JSON as a {@link
     * ReasoningDepth}; null on absent/blank/malformed, OR when the arm names {@code PLANNED} - that
     * path has its own separate multi-step machinery ({@link #planThenRespond}) that a bandit arm
     * is not meant to select into (see {@code PolicyDimension}'s wiring notes).
     */
    static ReasoningDepth reasoningDepthFromArmConfig(String armConfigJson) {
        if (armConfigJson == null || armConfigJson.isBlank()) {
            return null;
        }
        try {
            JsonNode depth = RL_ARM_MAPPER.readTree(armConfigJson).get("depth");
            if (depth == null || depth.isNull()) {
                return null;
            }
            ReasoningDepth parsed =
                    ReasoningDepth.valueOf(
                            depth.asText().trim().toUpperCase(java.util.Locale.ROOT));
            return parsed == ReasoningDepth.PLANNED ? null : parsed;
        } catch (Exception e) {
            return null;
        }
    }
}
