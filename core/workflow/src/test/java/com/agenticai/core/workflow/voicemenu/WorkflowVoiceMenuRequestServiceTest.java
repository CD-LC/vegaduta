package com.agenticai.core.workflow.voicemenu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.omnichannel.trigger.WorkflowSyncTriggerResult;
import com.agenticai.core.workflow.WorkflowDefinition;
import com.agenticai.core.workflow.WorkflowDefinitionRepository;
import com.agenticai.core.workflow.WorkflowExecutionEngine;
import com.agenticai.core.workflow.WorkflowRun;
import com.agenticai.core.workflow.WorkflowRunRepository;
import com.agenticai.core.workflow.WorkflowRunStatus;
import com.agenticai.core.workflow.WorkflowStatus;
import com.agenticai.core.workflow.dto.NodeTraceEntry;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Unit coverage for the {@code voice_menu} node's halt/resume pair - see {@link
 * WorkflowVoiceMenuRequestService}'s javadoc for the design this mirrors ({@code
 * WorkflowInputRequestService}'s, which has no equivalent test of its own; this class establishes
 * the pattern for that whole halt/resume family rather than reusing an existing one).
 */
class WorkflowVoiceMenuRequestServiceTest {

    private WorkflowVoiceMenuRequestRepository requestRepository;
    private WorkflowRunRepository runRepository;
    private WorkflowDefinitionRepository definitionRepository;
    private WorkflowExecutionEngine executionEngine;
    private WorkflowVoiceMenuRequestService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        requestRepository = mock(WorkflowVoiceMenuRequestRepository.class);
        runRepository = mock(WorkflowRunRepository.class);
        definitionRepository = mock(WorkflowDefinitionRepository.class);
        executionEngine = mock(WorkflowExecutionEngine.class);
        ObjectProvider<WorkflowExecutionEngine> engineProvider = mock(ObjectProvider.class);
        when(engineProvider.getObject()).thenReturn(executionEngine);
        service =
                new WorkflowVoiceMenuRequestService(
                        requestRepository,
                        runRepository,
                        definitionRepository,
                        engineProvider,
                        new ObjectMapper().findAndRegisterModules());

        when(requestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void createPendingSnapshotsNodeConfigWithDefaultsApplied() {
        WorkflowNodeDto node =
                menuNode(
                        "n1",
                        Map.of(
                                "prompt",
                                "Press 1 for sales, 2 for support",
                                "branches",
                                List.of(
                                        Map.of("digit", "1", "label", "sales"),
                                        Map.of("digit", "2", "label", "support"))));

        WorkflowVoiceMenuRequest request =
                service.createPending(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "My IVR",
                        node,
                        "upstream output",
                        "system:test");

        assertEquals("Press 1 for sales, 2 for support", request.getPromptText());
        assertEquals("#", request.getFinishOnKey(), "finishOnKey should default to '#'");
        assertEquals(5, request.getTimeoutSeconds(), "timeoutSeconds should default to 5");
        assertEquals(2, request.getMaxRetries(), "maxRetries should default to 2");
        assertEquals(1, request.getAttempt());
        assertEquals(WorkflowVoiceMenuRequestStatus.PENDING, request.getStatus());
        assertEquals("upstream output", request.getNodeInput());
        assertTrue(request.getBranchesJson().contains("\"sales\""));
        assertTrue(request.getBranchesJson().contains("\"support\""));
    }

    @Test
    void createPendingHonorsExplicitConfigAndClampsOutOfRangeValues() {
        WorkflowNodeDto node =
                menuNode(
                        "n1",
                        Map.of(
                                "prompt", "Enter your PIN",
                                "numDigits", 4,
                                "finishOnKey", "*",
                                "timeoutSeconds",
                                        999, // above the 60s ceiling - must clamp, not fail
                                "maxRetries", -1 // below the 0 floor - must clamp, not fail
                                ));

        WorkflowVoiceMenuRequest request =
                service.createPending(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "PIN entry",
                        node,
                        "{}",
                        "system:test");

        assertEquals(4, request.getNumDigits());
        assertEquals("*", request.getFinishOnKey());
        assertEquals(60, request.getTimeoutSeconds());
        assertEquals(0, request.getMaxRetries());
    }

    @Test
    void collectDigitsMatchingABranchResumesTheRunAndReturnsItsOutput() {
        UUID runId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();
        WorkflowVoiceMenuRequest request =
                pendingRequest(
                        runId,
                        workflowId,
                        List.of(
                                Map.of("digit", "1", "label", "sales"),
                                Map.of("digit", "2", "label", "support")));
        UUID requestId = request.getId();
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(request));

        WorkflowRun run = awaitingVoiceInputRun(runId, workflowId);
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));
        when(runRepository.compareAndSetStatus(
                        runId, WorkflowRunStatus.AWAITING_VOICE_INPUT, WorkflowRunStatus.RUNNING))
                .thenReturn(1);

        WorkflowDefinition definition =
                new WorkflowDefinition(
                        "My IVR", "d", "{\"nodes\":[],\"edges\":[]}", WorkflowStatus.ACTIVE);
        when(definitionRepository.findById(workflowId)).thenReturn(Optional.of(definition));

        WorkflowRun resumedRun = new WorkflowRun(runId, workflowId, "My IVR", "system:test");
        resumedRun.complete(
                WorkflowRunStatus.COMPLETED,
                writeTrace(
                        new NodeTraceEntry(
                                "out1",
                                "output",
                                "Output",
                                "OK",
                                "Routed to sales.",
                                Instant.now(),
                                Instant.now())),
                null);
        when(executionEngine.resume(eq(definition), any(), any(), any(), eq("n1"), any(), any()))
                .thenReturn(resumedRun);

        WorkflowSyncTriggerResult result = service.collectDigits(requestId, "1", "system:dtmf");

        assertTrue(result.succeeded());
        assertNull(result.pendingVoiceMenu());
        assertEquals("Routed to sales.", result.outputText());
        verify(runRepository)
                .compareAndSetStatus(
                        runId, WorkflowRunStatus.AWAITING_VOICE_INPUT, WorkflowRunStatus.RUNNING);
        verify(executionEngine).resume(eq(definition), any(), any(), any(), eq("n1"), any(), any());
    }

    @Test
    void collectDigitsWithNoMatchAndRetriesRemainingRePromptsWithoutResuming() {
        UUID runId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();
        WorkflowVoiceMenuRequest request =
                pendingRequest(runId, workflowId, List.of(Map.of("digit", "1", "label", "sales")));
        UUID requestId = request.getId();
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(request));
        WorkflowRun run = awaitingVoiceInputRun(runId, workflowId);
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));

        // Digit "9" matches no configured branch; maxRetries defaults to 2 and
        // attempt starts at 1, so this must retry rather than resolve.
        WorkflowSyncTriggerResult result = service.collectDigits(requestId, "9", "system:dtmf");

        assertTrue(result.succeeded());
        assertNotNull(
                result.pendingVoiceMenu(),
                "an unmatched digit with retries remaining should re-prompt, not finish");
        assertEquals(requestId.toString(), result.pendingVoiceMenu().requestId());
        assertEquals(2, request.getAttempt(), "attempt should have incremented");
        verify(runRepository, never()).compareAndSetStatus(any(), any(), any());
        verify(executionEngine, never()).resume(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void collectDigitsWithBlankDigitsAfterRetriesExhaustedResolvesToTimeoutBranch() {
        UUID runId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();
        WorkflowVoiceMenuRequest request =
                pendingRequest(runId, workflowId, List.of(Map.of("digit", "1", "label", "sales")));
        setMaxRetries(
                request, 0); // no retries left - the very first blank gather already exhausts them
        UUID requestId = request.getId();
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(request));

        WorkflowRun run = awaitingVoiceInputRun(runId, workflowId);
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));
        when(runRepository.compareAndSetStatus(
                        runId, WorkflowRunStatus.AWAITING_VOICE_INPUT, WorkflowRunStatus.RUNNING))
                .thenReturn(1);
        WorkflowDefinition definition =
                new WorkflowDefinition(
                        "My IVR", "d", "{\"nodes\":[],\"edges\":[]}", WorkflowStatus.ACTIVE);
        when(definitionRepository.findById(workflowId)).thenReturn(Optional.of(definition));
        WorkflowRun resumedRun = new WorkflowRun(runId, workflowId, "My IVR", "system:test");
        resumedRun.complete(WorkflowRunStatus.COMPLETED, "[]", null);
        when(executionEngine.resume(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(resumedRun);

        service.collectDigits(requestId, "", "system:dtmf");

        assertEquals("timeout", request.getResolvedBranch());
        assertEquals(WorkflowVoiceMenuRequestStatus.RESOLVED, request.getStatus());
    }

    @Test
    void collectDigitsForAnAlreadyResolvedRequestThrows() {
        WorkflowVoiceMenuRequest request =
                pendingRequest(UUID.randomUUID(), UUID.randomUUID(), List.of());
        request.markResolved("1", "sales");
        when(requestRepository.findById(request.getId())).thenReturn(Optional.of(request));

        org.junit.jupiter.api.Assertions.assertThrows(
                WorkflowVoiceMenuRequestStateException.class,
                () -> service.collectDigits(request.getId(), "1", "system:test"));
    }

    // --- fixtures ---

    private static WorkflowNodeDto menuNode(String id, Map<String, Object> config) {
        return new WorkflowNodeDto(
                id, "voice_menu", Map.of(), Map.of("config", config, "label", "Menu"));
    }

    private WorkflowVoiceMenuRequest pendingRequest(
            UUID runId, UUID workflowId, List<Map<String, String>> branches) {
        WorkflowNodeDto node =
                menuNode("n1", Map.of("prompt", "Press 1 for sales", "branches", branches));
        WorkflowVoiceMenuRequest request =
                service.createPending(runId, workflowId, "My IVR", node, "{}", "system:test");
        setId(request, UUID.randomUUID());
        return request;
    }

    private WorkflowRun awaitingVoiceInputRun(UUID runId, UUID workflowId) {
        WorkflowRun run = new WorkflowRun(runId, workflowId, "My IVR", "system:test");
        run.complete(WorkflowRunStatus.AWAITING_VOICE_INPUT, "[]", null);
        return run;
    }

    private String writeTrace(NodeTraceEntry entry) {
        try {
            return new ObjectMapper().findAndRegisterModules().writeValueAsString(List.of(entry));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * {@code WorkflowVoiceMenuRequest.id} is JPA-generated with no setter - reflection is the only
     * way to give a detached test instance a stable id.
     */
    private static void setId(WorkflowVoiceMenuRequest request, UUID id) {
        setField(request, "id", id);
    }

    private static void setMaxRetries(WorkflowVoiceMenuRequest request, int maxRetries) {
        setField(request, "maxRetries", maxRetries);
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            Field field = WorkflowVoiceMenuRequest.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
