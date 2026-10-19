package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.tools.AgentStepEventEmitter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The Gemini thought_signature workaround re-runs a turn with every tool removed. That answer must
 * not pass as a normal turn: it is counted, and a step-event consumer is told.
 */
class ChatTurnExecutorDegradedRetryTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private ChatTurnExecutor executor() {
        ChatTurnExecutor executor =
                new ChatTurnExecutor(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        Duration.ofSeconds(30));
        executor.setMeterRegistry(meters);
        return executor;
    }

    @Test
    void theToolsDisabledRetryIsCountedAndAnnouncedOnTheStepStream() {
        AgentStepEventEmitter stepEvents = mock(AgentStepEventEmitter.class);
        when(stepEvents.isEnabled()).thenReturn(true);
        Agent agent = mock(Agent.class);

        executor().flagToolsDisabledRetry(agent, stepEvents);

        assertEquals(
                1.0,
                meters.counter(
                                ChatTurnExecutor.DEGRADED_TOOLS_DISABLED_METRIC,
                                "provider",
                                "unknown",
                                "reason",
                                "gemini_thought_signature")
                        .count());
        verify(stepEvents).guardrail(eq("tools"), contains("tools disabled"));
    }

    @Test
    void aTurnWithoutStepEventsIsStillCounted() {
        AgentStepEventEmitter stepEvents = mock(AgentStepEventEmitter.class);
        when(stepEvents.isEnabled()).thenReturn(false);

        executor().flagToolsDisabledRetry(mock(Agent.class), stepEvents);

        assertEquals(
                1.0,
                meters.find(ChatTurnExecutor.DEGRADED_TOOLS_DISABLED_METRIC).counter().count());
        verify(stepEvents, never()).guardrail(anyString(), anyString());
    }
}
