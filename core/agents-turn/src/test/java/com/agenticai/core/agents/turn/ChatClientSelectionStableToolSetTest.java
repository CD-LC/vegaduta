package com.agenticai.core.agents.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.hosted.HostedChainLink;
import com.agenticai.core.providers.ProviderName;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

/**
 * Which turns keep their tool set unchanged from one message to the next: a model served on the person's own machine
 * (the install's local pool row or a bring-your-own local provider), unless it runs in small-model mode, whose short
 * list matters more on a slow machine. A hosted model keeps the per-message selection.
 */
class ChatClientSelectionStableToolSetTest {

    private final LocalModelTurnProperties properties = new LocalModelTurnProperties();
    private final ChatClientSelection selection =
            new ChatClientSelection(null, null, new ContinuationProperties(), properties);

    private static HostedChainLink row(boolean localModel, boolean smallModel) {
        return new HostedChainLink(
                mock(ChatClient.class), "k", "row", "model", 1, 0, 0, null, null, smallModel, null, localModel);
    }

    private static Agent agentOn(ProviderName provider) {
        Agent agent = mock(Agent.class);
        when(agent.getProviderName()).thenReturn(provider);
        return agent;
    }

    @Test
    void theInstallsLocalPoolRowKeepsItsToolsStable() {
        assertThat(selection.stableToolSet(agentOn(ProviderName.PLATFORM_HOSTED), List.of(row(true, false)))).isTrue();
    }

    @Test
    void aLocalRowInSmallModelModeKeepsItsShortPerMessageList() {
        assertThat(selection.stableToolSet(agentOn(ProviderName.PLATFORM_HOSTED), List.of(row(true, true)))).isFalse();
    }

    @Test
    void aHostedRowKeepsPerMessageSelection() {
        assertThat(selection.stableToolSet(agentOn(ProviderName.PLATFORM_HOSTED), List.of(row(false, false)))).isFalse();
    }

    @Test
    void theChainHeadDecides() {
        assertThat(selection.stableToolSet(agentOn(ProviderName.PLATFORM_HOSTED), List.of(row(false, false), row(true, false))))
                .isFalse();
        assertThat(selection.stableToolSet(agentOn(ProviderName.PLATFORM_HOSTED), List.of(row(true, false), row(false, false))))
                .isTrue();
    }

    @Test
    void aBringYourOwnLocalProviderKeepsItsToolsStableAndAFrontierOneDoesNot() {
        assertThat(selection.stableToolSet(agentOn(ProviderName.OLLAMA), null)).isTrue();
        assertThat(selection.stableToolSet(agentOn(ProviderName.LOCAL_OPENAI), List.of())).isTrue();
        assertThat(selection.stableToolSet(agentOn(ProviderName.ANTHROPIC), null)).isFalse();
        assertThat(selection.stableToolSet(agentOn(null), null)).isFalse();
        assertThat(selection.stableToolSet(null, null)).isFalse();
    }

    @Test
    void theSwitchAndTheProviderListAreConfiguration() {
        properties.setProviders(Set.of("mistral"));
        assertThat(selection.stableToolSet(agentOn(ProviderName.MISTRAL), null)).isTrue();
        assertThat(selection.stableToolSet(agentOn(ProviderName.OLLAMA), null)).isFalse();

        properties.setStableTools(false);
        assertThat(selection.stableToolSet(agentOn(ProviderName.MISTRAL), null)).isFalse();
        assertThat(selection.stableToolSet(agentOn(ProviderName.PLATFORM_HOSTED), List.of(row(true, false)))).isFalse();
    }
}
