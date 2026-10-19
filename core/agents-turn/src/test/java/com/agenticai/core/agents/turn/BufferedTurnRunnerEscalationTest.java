package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

/** L4 helpers in BufferedTurnRunner: which schema binds a turn, and which attempt a re-plan check looks at. */
class BufferedTurnRunnerEscalationTest {

    private static final String SCHEMA = "{\"type\":\"object\",\"required\":[\"a\"]}";

    @Test
    void plainChatTurnHasNoSchemaEvenWhenTheAgentDeclaresAJobSchema() {
        Agent agent = mock(Agent.class);
        when(agent.getResultSchemaJson()).thenReturn(SCHEMA);
        assertNull(BufferedTurnRunner.turnOutputSchema(agent, "what is the capital of France?"));
        assertNull(BufferedTurnRunner.turnOutputSchema(agent, null));
    }

    @Test
    void jobTurnCarryingTheSchemaInstructionIsBound() {
        Agent agent = mock(Agent.class);
        when(agent.getResultSchemaJson()).thenReturn(SCHEMA);
        String goal = "Summarise the ticket\n\n---\nRespond with ONLY a single JSON object.\n\nSchema:\n" + SCHEMA;
        assertEquals(SCHEMA, BufferedTurnRunner.turnOutputSchema(agent, goal));
    }

    @Test
    void agentWithoutASchemaIsNeverBound() {
        Agent agent = mock(Agent.class);
        when(agent.getResultSchemaJson()).thenReturn(null);
        assertNull(BufferedTurnRunner.turnOutputSchema(agent, "anything"));
        when(agent.getResultSchemaJson()).thenReturn("  ");
        assertNull(BufferedTurnRunner.turnOutputSchema(agent, "anything"));
    }

    @Test
    void replanLooksOnlyAtTheKeptAttemptAfterAnEscalation() {
        ChatClient client = mock(ChatClient.class);
        Instant turnStart = Instant.parse("2026-10-02T10:00:00Z");
        Instant strongStart = Instant.parse("2026-10-02T10:00:09Z");
        // Kept the cheap draft (no escalation): the whole turn counts.
        assertEquals(turnStart, new BufferedTurnRunner.Draft("a", client).since(turnStart));
        // Escalated: tool errors of the discarded cheap draft must not trigger a third run.
        assertEquals(strongStart, new BufferedTurnRunner.Draft("b", client, strongStart).since(turnStart));
    }
}
