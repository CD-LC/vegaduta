package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.approval.ApprovalPolicyService;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.audit.PlatformIssueService;
import com.agenticai.core.guardrails.ApprovalParkProperties;
import com.agenticai.core.guardrails.GuardedToolCallback;
import com.agenticai.core.guardrails.GuardrailService;
import com.agenticai.core.guardrails.ToolResultMarker;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Whether a tool call ran is carried by a typed marker on the result ({@link ToolResultMarker}), not by the first
 * words of the text: the approval-park wording is configurable, so a deployment that words it differently used to have
 * its parked calls recorded in the effect journal as executed effects (and replayed from it later as if done).
 */
class LoggingToolCallbackTypedResultTest {

    private static final String SESSION = "session";
    private static final String TURN = "turn1";

    private static final class Raw implements ToolCallback {
        int calls;

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name("send_invoice").description("d").inputSchema("{}").build();
        }

        @Override
        public String call(String toolInput) {
            calls++;
            return "{\"sent\":true}";
        }
    }

    @SuppressWarnings("unchecked")
    private LoggingToolCallback chain(Raw raw, EffectJournal journal, ApprovalParkProperties texts) {
        ApprovalPolicyService policy = mock(ApprovalPolicyService.class);
        when(policy.requiresApproval("mcp_tool.send_invoice")).thenReturn(true);
        HumanApprovalGateService gate = mock(HumanApprovalGateService.class);
        ApprovalRequest request = mock(ApprovalRequest.class);
        when(request.getId()).thenReturn(UUID.randomUUID());
        when(gate.recordToolCallForApproval(
                        anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any(), any()))
                .thenReturn(request);
        GuardrailService guardrails = mock(GuardrailService.class);
        when(guardrails.sanitizeToolOutput(anyString(), anyString(), any())).thenAnswer(i -> i.getArgument(1));
        UUID agent = UUID.randomUUID();
        GuardedToolCallback guarded =
                new GuardedToolCallback(raw, new AtomicInteger(), 10, guardrails, policy, gate, agent, SESSION);
        if (texts != null) {
            guarded.withApprovalParkTexts(texts);
        }
        return new LoggingToolCallback(
                guarded,
                mock(ToolCallLogRepository.class),
                agent,
                SESSION,
                mock(ObjectProvider.class),
                mock(ToolCircuitBreakerService.class),
                UUID.randomUUID(),
                journal,
                name -> true,
                TURN,
                null);
    }

    @Test
    void aCallParkedUnderAConfiguredWordingIsNotRecordedAsAnExecutedEffect() {
        EffectJournal journal = new EffectJournal(true, Duration.ofMinutes(30), 10, null, Clock.systemUTC());
        ApprovalParkProperties texts = new ApprovalParkProperties();
        texts.setAwaitingMessage("Waiting on a person for {tool}.");
        Raw raw = new Raw();

        String result = chain(raw, journal, texts).call("{\"to\":\"a\"}");

        assertEquals("Waiting on a person for send_invoice.", result);
        assertEquals(0, raw.calls, "the tool never ran");
        assertTrue(
                journal.lookup(EffectJournal.keyFor(SESSION, TURN, "send_invoice", "{\"to\":\"a\"}")).isEmpty(),
                "a parked call must not be journaled as a done effect: a later identical call would be served its"
                        + " 'result' from the journal and the invoice would never be sent");
    }

    @Test
    void anExecutedCallStillIsRecorded() {
        EffectJournal journal = new EffectJournal(true, Duration.ofMinutes(30), 10, null, Clock.systemUTC());
        ApprovalPolicyService policy = mock(ApprovalPolicyService.class);
        Raw raw = new Raw();
        GuardrailService guardrails = mock(GuardrailService.class);
        when(guardrails.sanitizeToolOutput(anyString(), anyString(), any())).thenAnswer(i -> i.getArgument(1));
        UUID agent = UUID.randomUUID();
        @SuppressWarnings("unchecked")
        LoggingToolCallback cb =
                new LoggingToolCallback(
                        new GuardedToolCallback(
                                raw, new AtomicInteger(), 10, guardrails, policy, mock(HumanApprovalGateService.class), agent, SESSION),
                        mock(ToolCallLogRepository.class),
                        agent,
                        SESSION,
                        mock(ObjectProvider.class),
                        mock(ToolCircuitBreakerService.class),
                        UUID.randomUUID(),
                        journal,
                        name -> true,
                        TURN,
                        null);

        cb.call("{\"to\":\"a\"}");

        assertTrue(journal.lookup(EffectJournal.keyFor(SESSION, TURN, "send_invoice", "{\"to\":\"a\"}")).isPresent());
        assertEquals(1, raw.calls);
    }

    @Test
    void wasExecutedReadsTheTypedMarkerAndKeepsTheOldWordingForCompatibility() {
        String custom = "A platform answer in no known wording " + System.nanoTime();
        assertTrue(LoggingToolCallback.wasExecuted(custom));

        ToolResultMarker.mark(custom, ToolResultMarker.Kind.PARKED);

        assertFalse(LoggingToolCallback.wasExecuted(custom));
        // Compatibility: the old prefixes are still recognised without a marker.
        assertFalse(LoggingToolCallback.wasExecuted("This tool call ('t') was not executed: blocked"));
        assertFalse(LoggingToolCallback.wasExecuted("This action ('t') requires human approval"));
        assertFalse(LoggingToolCallback.wasExecuted(null));
        assertTrue(LoggingToolCallback.wasExecuted("{\"status\":\"sent\"}"));
    }
}
