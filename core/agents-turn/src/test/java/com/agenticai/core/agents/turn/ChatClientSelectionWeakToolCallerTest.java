package com.agenticai.core.agents.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.hosted.HostedChainLink;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.providers.ToolCallGrade;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

/**
 * Which models the "reply stopped at an announced step" wording check applies to: a platform-hosted row in
 * small-model mode or measured at a text-only tool-calling grade, or a bring-your-own provider listed as a
 * local server. A frontier model's text-only reply is an answer.
 */
class ChatClientSelectionWeakToolCallerTest {

    private final ContinuationProperties properties = new ContinuationProperties();
    private final ChatClientSelection selection = new ChatClientSelection(null, null, properties);

    private static HostedChainLink row(boolean smallModel, ToolCallGrade grade) {
        return new HostedChainLink(
                mock(ChatClient.class), "k", "row", "model", 1, 0, 0, null, null, smallModel, grade);
    }

    private static Agent agentOn(ProviderName provider) {
        Agent agent = mock(Agent.class);
        when(agent.getProviderName()).thenReturn(provider);
        return agent;
    }

    @Test
    void aHostedRowInSmallModelModeIsAWeakToolCaller() {
        assertThat(selection.weakToolCaller(agentOn(ProviderName.PLATFORM_HOSTED), List.of(row(true, null)))).isTrue();
    }

    @Test
    void aHostedRowMeasuredAtATextOnlyGradeIsAWeakToolCaller() {
        assertThat(selection.weakToolCaller(agentOn(ProviderName.PLATFORM_HOSTED), List.of(row(false, ToolCallGrade.TEXT)))).isTrue();
        assertThat(selection.weakToolCaller(agentOn(ProviderName.PLATFORM_HOSTED), List.of(row(false, ToolCallGrade.NONE)))).isTrue();
    }

    @Test
    void aHostedRowWithStructuredToolCallsOrNoGradeIsNot() {
        assertThat(selection.weakToolCaller(agentOn(ProviderName.PLATFORM_HOSTED), List.of(row(false, ToolCallGrade.STRUCTURED)))).isFalse();
        assertThat(selection.weakToolCaller(agentOn(ProviderName.PLATFORM_HOSTED), List.of(row(false, null)))).isFalse();
    }

    @Test
    void theChainHeadDecidesLikeEveryOtherSmallModelRule() {
        assertThat(selection.weakToolCaller(agentOn(ProviderName.PLATFORM_HOSTED), List.of(row(false, null), row(true, ToolCallGrade.TEXT)))).isFalse();
    }

    @Test
    void aBringYourOwnLocalProviderIsAWeakToolCallerAndAFrontierProviderIsNot() {
        assertThat(selection.weakToolCaller(agentOn(ProviderName.OLLAMA), null)).isTrue();
        assertThat(selection.weakToolCaller(agentOn(ProviderName.LOCAL_OPENAI), null)).isTrue();
        assertThat(selection.weakToolCaller(agentOn(ProviderName.ANTHROPIC), null)).isFalse();
        assertThat(selection.weakToolCaller(agentOn(null), null)).isFalse();
    }

    @Test
    void theListOfLocalProvidersAndGradesIsConfiguration() {
        properties.setHeuristicProviders(java.util.Set.of(ProviderName.MISTRAL));
        properties.setHeuristicToolGrades(java.util.Set.of());

        assertThat(selection.weakToolCaller(agentOn(ProviderName.MISTRAL), null)).isTrue();
        assertThat(selection.weakToolCaller(agentOn(ProviderName.OLLAMA), null)).isFalse();
        assertThat(selection.weakToolCaller(agentOn(ProviderName.PLATFORM_HOSTED), List.of(row(false, ToolCallGrade.TEXT)))).isFalse();
    }
}
