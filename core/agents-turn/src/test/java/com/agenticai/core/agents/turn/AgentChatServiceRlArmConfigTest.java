package com.agenticai.core.agents.turn;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.core.ReasoningDepth;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The arm-config readers for the 4 RL dimensions wired into {@link
 * AgentChatService#chat} on 2026-08-15 ({@code toolNamesFromArmConfig},
 * {@code systemPromptFromArmConfig}, {@code genParamsFromArmConfig},
 * {@code reasoningDepthFromArmConfig}) - the same "never leave the turn on a
 * value the config didn't actually name, swallow malformed JSON to null
 * rather than throwing" contract {@code modelFromArmConfig} already
 * established for MODEL_ROUTE (see {@link RlModelRouteArmValidatorTest} for
 * that one). A null return is exactly what makes the caller in {@code chat()}
 * treat the arm as stale/unusable and drop it from the episode too - these
 * tests pin the boundary of what counts as "usable" for each dimension.
 */
class AgentChatServiceRlArmConfigTest {

    // --- TOOL_SUBSET ---

    @Test
    void toolNamesFromArmConfigReadsTheArray() {
        assertThat(ChatArmConfig.toolNamesFromArmConfig("{\"toolNames\":[\"search_web\",\"send_email\"]}"))
            .containsExactlyInAnyOrder("search_web", "send_email");
    }

    @Test
    void toolNamesFromArmConfigIsNullForAnEmptyArray() {
        // An arm that offers NOTHING is indistinguishable from a malformed
        // one here on purpose - chat()'s empty-intersection fallback treats
        // both the same way (ignore the arm, use the full tool set).
        assertThat(ChatArmConfig.toolNamesFromArmConfig("{\"toolNames\":[]}")).isNull();
    }

    @Test
    void toolNamesFromArmConfigIgnoresNonStringEntries() {
        assertThat(ChatArmConfig.toolNamesFromArmConfig("{\"toolNames\":[\"a\", 1, null, \"b\"]}"))
            .containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void toolNamesFromArmConfigIsNullForMissingFieldOrMalformedJson() {
        assertThat(ChatArmConfig.toolNamesFromArmConfig(null)).isNull();
        assertThat(ChatArmConfig.toolNamesFromArmConfig("")).isNull();
        assertThat(ChatArmConfig.toolNamesFromArmConfig("{\"model\":\"gpt-4o\"}")).isNull();
        assertThat(ChatArmConfig.toolNamesFromArmConfig("{\"toolNames\": ")).isNull();
    }

    // --- PROMPT_VARIANT ---

    @Test
    void systemPromptFromArmConfigReadsTheLiteralText() {
        assertThat(ChatArmConfig.systemPromptFromArmConfig("{\"systemPrompt\":\"You are terse.\"}"))
            .isEqualTo("You are terse.");
    }

    @Test
    void systemPromptFromArmConfigIsNullForBlankOrMissingOrMalformed() {
        assertThat(ChatArmConfig.systemPromptFromArmConfig(null)).isNull();
        assertThat(ChatArmConfig.systemPromptFromArmConfig("{\"systemPrompt\":\"   \"}")).isNull();
        assertThat(ChatArmConfig.systemPromptFromArmConfig("{\"model\":\"gpt-4o\"}")).isNull();
        assertThat(ChatArmConfig.systemPromptFromArmConfig("not json")).isNull();
    }

    // --- GEN_PARAMS ---

    @Test
    void genParamsFromArmConfigReadsBothFields() {
        AgentChatService.GenParams params =
            ChatArmConfig.genParamsFromArmConfig("{\"temperature\":0.3,\"topP\":0.9}");

        assertThat(params).isNotNull();
        assertThat(params.temperature()).isEqualTo(0.3);
        assertThat(params.topP()).isEqualTo(0.9);
    }

    @Test
    void genParamsFromArmConfigIsNullWhenEitherFieldIsMissing() {
        assertThat(ChatArmConfig.genParamsFromArmConfig("{\"temperature\":0.3}")).isNull();
        assertThat(ChatArmConfig.genParamsFromArmConfig("{\"topP\":0.9}")).isNull();
    }

    @Test
    void genParamsFromArmConfigIsNullWhenOutOfRange() {
        // temperature is a Spring AI ChatOptions value in [0,2]; topP in [0,1] -
        // same ranges the agent's own create/update DTOs already validate, so
        // an out-of-range arm is exactly as malformed as a missing field.
        assertThat(ChatArmConfig.genParamsFromArmConfig("{\"temperature\":2.5,\"topP\":0.5}")).isNull();
        assertThat(ChatArmConfig.genParamsFromArmConfig("{\"temperature\":0.5,\"topP\":1.5}")).isNull();
        assertThat(ChatArmConfig.genParamsFromArmConfig("{\"temperature\":-0.1,\"topP\":0.5}")).isNull();
    }

    @Test
    void genParamsFromArmConfigIsNullForMissingOrMalformed() {
        assertThat(ChatArmConfig.genParamsFromArmConfig(null)).isNull();
        assertThat(ChatArmConfig.genParamsFromArmConfig("{\"toolNames\":[]}")).isNull();
        assertThat(ChatArmConfig.genParamsFromArmConfig("{\"temperature\": ")).isNull();
    }

    // --- REASONING_DEPTH ---

    @Test
    void reasoningDepthFromArmConfigReadsEachNonPlannedValue() {
        assertThat(ChatArmConfig.reasoningDepthFromArmConfig("{\"depth\":\"FAST\"}"))
            .isEqualTo(ReasoningDepth.FAST);
        assertThat(ChatArmConfig.reasoningDepthFromArmConfig("{\"depth\":\"careful\"}"))
            .isEqualTo(ReasoningDepth.CAREFUL);
        assertThat(ChatArmConfig.reasoningDepthFromArmConfig("{\"depth\":\"Rigorous\"}"))
            .isEqualTo(ReasoningDepth.RIGOROUS);
    }

    @Test
    void reasoningDepthFromArmConfigRejectsPlanned() {
        // PLANNED has its own separate multi-step machinery a bandit arm must
        // never select into - see the method's own javadoc.
        assertThat(ChatArmConfig.reasoningDepthFromArmConfig("{\"depth\":\"PLANNED\"}")).isNull();
    }

    @Test
    void reasoningDepthFromArmConfigIsNullForMissingOrUnknownOrMalformed() {
        assertThat(ChatArmConfig.reasoningDepthFromArmConfig(null)).isNull();
        assertThat(ChatArmConfig.reasoningDepthFromArmConfig("{\"model\":\"gpt-4o\"}")).isNull();
        assertThat(ChatArmConfig.reasoningDepthFromArmConfig("{\"depth\":\"SLOW\"}")).isNull();
        assertThat(ChatArmConfig.reasoningDepthFromArmConfig("{\"depth\": ")).isNull();
    }
}
