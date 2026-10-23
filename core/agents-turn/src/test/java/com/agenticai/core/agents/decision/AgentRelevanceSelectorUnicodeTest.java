package com.agenticai.core.agents.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.dto.AgentResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Quick Task picks the agent whose name and prompt share the most words with the task. A word was a run
 * of {@code [a-z0-9]}, so a task written in Russian, Hindi or Chinese had no words and every agent scored
 * 0: the first agent in the list was chosen whatever the task said.
 */
class AgentRelevanceSelectorUnicodeTest {

    private final AgentRelevanceSelector selector = new AgentRelevanceSelector((DecisionEngine) null, false);

    private static AgentResponse agent(String name, String prompt) {
        AgentResponse a = mock(AgentResponse.class);
        when(a.id()).thenReturn(UUID.randomUUID());
        when(a.name()).thenReturn(name);
        when(a.systemPrompt()).thenReturn(prompt);
        return a;
    }

    @Test
    void aRussianTaskPicksTheAgentThatSharesItsWordsNotTheFirstInTheList() {
        AgentResponse weather = agent("Погода", "Отвечает на вопросы о погоде и прогнозах");
        AgentResponse billing = agent("Счета", "Отправляет счёт клиенту и проверяет оплату счетов");

        AgentResponse picked = selector.selectBest("Отправь счёт клиенту", List.of(weather, billing));

        assertThat(picked).isSameAs(billing);
    }

    @Test
    void aChineseTaskPicksTheMatchingAgent() {
        AgentResponse weather = agent("天气", "回答关于天气和预报的问题");
        AgentResponse billing = agent("发票", "给客户发送发票并检查付款");

        AgentResponse picked = selector.selectBest("发送发票给客户", List.of(weather, billing));

        assertThat(picked).isSameAs(billing);
    }

    @Test
    void additionalAgentsAreFoundForANonLatinTask() {
        AgentResponse primary = agent("Погода", "Отвечает на вопросы о погоде");
        AgentResponse billing = agent("Счета", "Отправляет счёт клиенту");

        List<AgentResponse> extra = selector.selectAdditionalRelevant("Отправь счёт клиенту", List.of(primary, billing), primary, 2);

        assertThat(extra).containsExactly(billing);
    }
}
