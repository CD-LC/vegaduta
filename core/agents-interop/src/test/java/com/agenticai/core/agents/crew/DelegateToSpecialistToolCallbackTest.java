package com.agenticai.core.agents.crew;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.turn.AgentChatService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Thin-adapter contract for {@link DelegateToSpecialistToolCallback}, mirroring {@code
 * DelegateToTeamToolCallbackTest}'s "mock AgentChatService, drive call() with raw JSON" structure.
 * Covers argument parsing/validation and that a delegation failure is surfaced as a tool-result
 * envelope rather than a thrown exception (same posture {@code DelegateToAgentToolCallback}'s own
 * javadoc documents), never the {@link HandoffChain}/depth logic itself, which {@link
 * HandoffChainTest} and {@link SpecialistHandoffServiceTest} already cover.
 */
class DelegateToSpecialistToolCallbackTest {

    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();

    private final AgentChatService chatService = mock(AgentChatService.class);
    private final UUID rootAgentId = UUID.randomUUID();
    private final HandoffChain chain = HandoffChain.root("session-1", rootAgentId);

    private DelegateToSpecialistToolCallback callback() {
        return new DelegateToSpecialistToolCallback(
                chatService, List.of("billing", "legal-review"), chain);
    }

    @Test
    void toolDefinitionListsEveryRegisteredCapabilityAndTheRemainingHopBudget() {
        String description = callback().getToolDefinition().description();

        assertTrue(description.contains("billing"));
        assertTrue(description.contains("legal-review"));
        assertTrue(description.contains(String.valueOf(HandoffChain.MAX_DEPTH)));
    }

    @Test
    void callParsesArgumentsAndDelegatesWithTheBoundChain() throws Exception {
        when(chatService.delegateToSpecialist(
                        eq(chain), eq("billing"), eq("refund question"), eq("prior context")))
                .thenReturn("Refund approved.");

        String out =
                callback()
                        .call(
                                "{\"capability\":\"billing\",\"subTask\":\"refund question\",\"contextSummary\":\"prior context\"}");

        JsonNode root = MAPPER.readTree(out);
        assertEquals("billing", root.get("capability").asString());
        assertEquals("Refund approved.", root.get("answer").asString());
        verify(chatService)
                .delegateToSpecialist(chain, "billing", "refund question", "prior context");
    }

    @Test
    void callToleratesAMissingOptionalContextSummary() {
        when(chatService.delegateToSpecialist(eq(chain), eq("billing"), eq("q"), eq(null)))
                .thenReturn("ok");

        callback().call("{\"capability\":\"billing\",\"subTask\":\"q\"}");

        verify(chatService).delegateToSpecialist(chain, "billing", "q", null);
    }

    @Test
    void callRejectsAMissingCapabilityWithoutInvokingDelegation() {
        String out = callback().call("{\"subTask\":\"q\"}");

        assertTrue(out.contains("\"error\""));
        assertTrue(out.contains("capability"));
        verify(chatService, never()).delegateToSpecialist(any(), any(), any(), any());
    }

    @Test
    void callRejectsAMissingSubTaskWithoutInvokingDelegation() {
        String out = callback().call("{\"capability\":\"billing\"}");

        assertTrue(out.contains("\"error\""));
        verify(chatService, never()).delegateToSpecialist(any(), any(), any(), any());
    }

    @Test
    void callSurfacesADelegationFailureAsAToolResultRatherThanThrowing() {
        when(chatService.delegateToSpecialist(eq(chain), eq("billing"), eq("q"), eq(null)))
                .thenThrow(new RuntimeException("provider down"));

        String out = callback().call("{\"capability\":\"billing\",\"subTask\":\"q\"}");

        assertTrue(out.contains("\"error\""));
        assertTrue(out.contains("Delegation failed"));
    }
}
