package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.workflow.dto.NodeTraceEntry;
import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.agenticai.core.workflow.dto.WorkflowNodeTestRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class WorkflowNodeTestServiceTest {

    private static final UUID WF = UUID.randomUUID();

    private WorkflowService workflowService;
    private WorkflowNodeExecutor executor;
    private NodeUsageResolver usageResolver;
    private WorkflowNodeTestService service;

    @BeforeEach
    void setUp() {
        workflowService = mock(WorkflowService.class);
        executor = mock(WorkflowNodeExecutor.class);
        usageResolver = mock(NodeUsageResolver.class);
        when(usageResolver.attach(any(), any(), anyString())).thenAnswer(inv -> inv.getArgument(0));
        WorkflowDefinition definition = mock(WorkflowDefinition.class);
        when(definition.getId()).thenReturn(WF);
        when(definition.getName()).thenReturn("wf");
        when(workflowService.requireAccess(WF, PermissionLevel.RUNNER)).thenReturn(definition);
        service =
                new WorkflowNodeTestService(
                        workflowService,
                        executor,
                        usageResolver,
                        com.agenticai.core.json.JsonMappers.plain());
    }

    private static WorkflowNodeDto node(String id, String type, Map<String, Object> config) {
        return new WorkflowNodeDto(id, type, Map.of(), Map.of("label", id, "config", config));
    }

    private void graph(WorkflowNodeDto... nodes) {
        graph(List.of(), nodes);
    }

    private void graph(List<WorkflowEdgeDto> edges, WorkflowNodeDto... nodes) {
        when(workflowService.graph(WF)).thenReturn(new WorkflowGraphDto(List.of(nodes), edges));
    }

    @Test
    void runsOneNodeAndReturnsTraceEntryWithRunData() {
        graph(node("t1", "code", Map.of("operation", "uppercase")));
        when(executor.executeNode(
                        eq(WF.toString()),
                        eq("wf"),
                        anyString(),
                        any(),
                        eq("hi"),
                        eq("ann"),
                        any()))
                .thenReturn(
                        WorkflowNodeExecutor.NodeExecutionResult.ok(
                                "HI", "HI", null, List.of("done")));

        NodeTraceEntry e = service.test(WF, "t1", new WorkflowNodeTestRequest("hi", null), "ann");

        assertEquals("OK", e.status());
        assertEquals("hi", e.inputJson());
        assertEquals("HI", e.outputJson());
        assertEquals(Boolean.FALSE, e.outputTruncated());
        assertNotNull(e.durationMs());
        assertTrue(e.logLines().contains("done"));
    }

    @Test
    void outputIsRedactedLikeARunTrace() {
        graph(node("h1", "http", Map.of("method", "GET", "url", "https://example.com")));
        when(executor.executeNode(
                        anyString(), anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(
                        WorkflowNodeExecutor.NodeExecutionResult.ok(
                                "{\"access_token\":\"abcd1234efgh5678\"}", "ok", null));
        NodeTraceEntry e = service.test(WF, "h1", new WorkflowNodeTestRequest("x", null), null);
        assertFalse(e.outputJson().contains("abcd1234efgh5678"), e.outputJson());
    }

    @Test
    void pinnedUpstreamOutputFeedsTheNodeWhenNoInputGiven() {
        graph(
                List.of(new WorkflowEdgeDto("e1", "up", "t1", null, null)),
                node("up", "trigger", Map.of()),
                node("t1", "code", Map.of("operation", "uppercase")));
        when(executor.executeNode(
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        eq("{\"a\":1}"),
                        any(),
                        any()))
                .thenReturn(WorkflowNodeExecutor.NodeExecutionResult.ok("ok", "ok", null));

        NodeTraceEntry e =
                service.test(
                        WF,
                        "t1",
                        new WorkflowNodeTestRequest(
                                null, Map.of("up", Map.of("a", 1), "unrelated", "zzz")),
                        null);

        assertEquals("{\"a\":1}", e.inputJson());
    }

    @Test
    void pinnedUpstreamOutputsAreReadableThroughNodeAndTriggerExpressions() {
        graph(
                List.of(new WorkflowEdgeDto("e1", "up", "t1", null, null)),
                node("up", "trigger", Map.of()),
                node("mid", "code", Map.of()),
                node("t1", "code", Map.of("operation", "uppercase")));
        java.util.concurrent.atomic.AtomicReference<ExpressionScope> seen =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(executor.executeNode(
                        anyString(), anyString(), anyString(), any(), any(), any(), any()))
                .thenAnswer(
                        inv -> {
                            seen.set(ExpressionScopeContext.current());
                            return WorkflowNodeExecutor.NodeExecutionResult.ok("ok", "ok", null);
                        });

        service.test(
                WF,
                "t1",
                new WorkflowNodeTestRequest(
                        null, Map.of("up", Map.of("a", 1), "mid", "{\"tier\":\"gold\"}")),
                null);

        ExpressionScope scope = seen.get();
        assertNotNull(scope.supplied());
        assertEquals("{\"tier\":\"gold\"}", scope.supplied().get("label:mid").output());
        assertEquals("{\"tier\":\"gold\"}", scope.supplied().get("id:mid").output());
        assertEquals("{\"a\":1}", scope.supplied().get("id:__trigger").output());
        assertTrue(scope.recordingSkipped());
    }

    @Test
    void executorFailureBecomesAnErrorEntryNotA500() {
        graph(node("a1", "agent", Map.of("agentId", UUID.randomUUID().toString())));
        when(executor.executeNode(
                        anyString(), anyString(), anyString(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("provider down"));
        NodeTraceEntry e = service.test(WF, "a1", new WorkflowNodeTestRequest("q", null), null);
        assertEquals("ERROR", e.status());
        assertNull(e.outputJson());
        assertEquals("q", e.inputJson());
    }

    @Test
    void unknownNodeIs404() {
        graph(node("t1", "code", Map.of()));
        ResponseStatusException ex =
                assertThrows(
                        ResponseStatusException.class,
                        () -> service.test(WF, "missing", null, null));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void sideEffectingAndRunBoundNodesAreRefusedWith409AndNeverExecuted() {
        List<WorkflowNodeDto> refused =
                List.of(
                        node("n1", "bulk_email", Map.of()),
                        node("n2", "crm_lead", Map.of()),
                        node("n3", "device_control", Map.of()),
                        node("n4", "tool", Map.of()),
                        node("n5", "output", Map.of("channel", "email")),
                        node("n6", "workflow", Map.of()),
                        node("n7", "http", Map.of("method", "POST")),
                        node("n8", "media", Map.of("operation", "render")),
                        node("n9", "approval", Map.of()),
                        node("n10", "human_input", Map.of()),
                        node("n11", "voice_menu", Map.of()),
                        node("n12", "agent", Map.of("agentId", "x", "requiresApproval", true)),
                        node(
                                "n13",
                                "agent",
                                Map.of(
                                        "agentId",
                                        "x",
                                        "inputMapping",
                                        Map.of("query", "{{input}}"))),
                        node("n14", "loop", Map.of()),
                        node("n15", "parallel", Map.of()),
                        node("n16", "fan_out", Map.of()),
                        node("n17", "sub_flow", Map.of()));
        graph(refused.toArray(new WorkflowNodeDto[0]));
        for (WorkflowNodeDto n : refused) {
            ResponseStatusException ex =
                    assertThrows(
                            ResponseStatusException.class,
                            () ->
                                    service.test(
                                            WF,
                                            n.id(),
                                            new WorkflowNodeTestRequest("x", null),
                                            null),
                            n.type());
            assertEquals(HttpStatus.CONFLICT, ex.getStatusCode(), n.type());
            assertNotNull(ex.getReason());
        }
        verify(executor, never())
                .executeNode(anyString(), anyString(), anyString(), any(), any(), any(), any());
    }

    @Test
    void readOnlyNodeTypesAreAllowed() {
        for (WorkflowNodeDto n :
                List.of(
                        node("a", "agent", Map.of("agentId", "x")),
                        node("b", "supervisor", Map.of()),
                        node("c", "code", Map.of()),
                        node("d", "condition", Map.of()),
                        node("e", "knowledge", Map.of()),
                        node("f", "http", Map.of("method", "get")),
                        node("g", "http", Map.of()),
                        node("h", "media", Map.of("operation", "tts")),
                        node("i", "trigger", Map.of()))) {
            assertNull(WorkflowNodeTestService.refusalReason(n), n.id() + "/" + n.type());
        }
    }

    // ---- persisted pins are the default upstream data (V374)

    private WorkflowPinStore withPins() {
        WorkflowPinStore store = new WorkflowPinStore(PinTestSupport.pinRepository());
        service.setPinStore(store);
        return store;
    }

    private java.util.concurrent.atomic.AtomicReference<ExpressionScope> captureScope() {
        java.util.concurrent.atomic.AtomicReference<ExpressionScope> seen =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(executor.executeNode(
                        anyString(), anyString(), anyString(), any(), any(), any(), any()))
                .thenAnswer(
                        inv -> {
                            seen.set(ExpressionScopeContext.current());
                            return WorkflowNodeExecutor.NodeExecutionResult.ok("ok", "ok", null);
                        });
        return seen;
    }

    @Test
    void thePersistedPinOfTheUpstreamNodeFeedsTheNodeWhenNothingIsSupplied() {
        withPins().save(WF, "up", "{\"saved\":true}", "manual", null, "ann", null);
        graph(
                List.of(new WorkflowEdgeDto("e1", "up", "t1", null, null)),
                node("up", "trigger", Map.of()),
                node("t1", "code", Map.of("operation", "uppercase")));
        captureScope();

        NodeTraceEntry e = service.test(WF, "t1", new WorkflowNodeTestRequest(null, null), null);

        assertEquals("{\"saved\":true}", e.inputJson());
        assertTrue(
                e.logLines().stream().anyMatch(l -> l.contains("saved pin")),
                e.logLines().toString());
    }

    @Test
    void theRequestsOwnPinnedInputsOverrideThePersistedPin() {
        withPins().save(WF, "up", "{\"saved\":true}", "manual", null, "ann", null);
        graph(
                List.of(new WorkflowEdgeDto("e1", "up", "t1", null, null)),
                node("up", "trigger", Map.of()),
                node("t1", "code", Map.of("operation", "uppercase")));
        captureScope();

        NodeTraceEntry e =
                service.test(
                        WF,
                        "t1",
                        new WorkflowNodeTestRequest(null, Map.of("up", Map.of("fresh", 1))),
                        null);

        assertEquals("{\"fresh\":1}", e.inputJson());
    }

    @Test
    void explicitInputStillWinsOverEveryPin() {
        withPins().save(WF, "up", "{\"saved\":true}", "manual", null, "ann", null);
        graph(
                List.of(new WorkflowEdgeDto("e1", "up", "t1", null, null)),
                node("up", "trigger", Map.of()),
                node("t1", "code", Map.of("operation", "uppercase")));
        captureScope();
        assertEquals(
                "typed",
                service.test(WF, "t1", new WorkflowNodeTestRequest("typed", null), null)
                        .inputJson());
    }

    @Test
    void persistedPinsOfAnyNodeAreReadableThroughNodeAndTriggerExpressions() {
        WorkflowPinStore store = withPins();
        store.save(WF, "up", "{\"event\":1}", "trigger_sample", null, "ann", null);
        store.save(WF, "mid", "{\"tier\":\"gold\"}", "run", null, "ann", null);
        graph(
                List.of(new WorkflowEdgeDto("e1", "mid", "t1", null, null)),
                node("up", "trigger", Map.of()),
                node("mid", "code", Map.of()),
                node("t1", "code", Map.of("operation", "uppercase")));
        java.util.concurrent.atomic.AtomicReference<ExpressionScope> seen = captureScope();

        service.test(WF, "t1", new WorkflowNodeTestRequest(null, null), null);

        ExpressionScope scope = seen.get();
        assertEquals("{\"tier\":\"gold\"}", scope.supplied().get("label:mid").output());
        assertEquals("{\"event\":1}", scope.supplied().get("id:__trigger").output());
    }

    @Test
    void aNodeWithNeitherAPinNorASuppliedOutputStaysAbsentSoItsReferenceFailsLoudly() {
        withPins().save(WF, "mid", "{}", "run", null, "ann", null);
        graph(
                List.of(),
                node("mid", "code", Map.of()),
                node("other", "code", Map.of()),
                node("t1", "code", Map.of("operation", "uppercase")));
        java.util.concurrent.atomic.AtomicReference<ExpressionScope> seen = captureScope();

        service.test(WF, "t1", new WorkflowNodeTestRequest("x", null), null);

        assertNotNull(seen.get().supplied().get("label:mid"));
        assertNull(seen.get().supplied().get("label:other"));
    }

    @Test
    void theNodeUnderTestRunsForRealEvenWhenItHasItsOwnPin() {
        withPins().save(WF, "t1", "{\"pinned\":true}", "manual", null, "ann", null);
        graph(node("t1", "code", Map.of("operation", "uppercase")));
        java.util.concurrent.atomic.AtomicReference<ExpressionScope> seen = captureScope();

        NodeTraceEntry e = service.test(WF, "t1", new WorkflowNodeTestRequest("x", null), null);

        assertEquals("OK", e.status());
        assertFalse(
                seen.get().honoursPins(),
                "a step test is never served from the pin of the node being tested");
        verify(executor)
                .executeNode(anyString(), anyString(), anyString(), any(), eq("x"), any(), any());
    }

    @Test
    void withoutAPinStoreOnlyTheRequestCounts() {
        graph(
                List.of(new WorkflowEdgeDto("e1", "up", "t1", null, null)),
                node("up", "trigger", Map.of()),
                node("t1", "code", Map.of("operation", "uppercase")));
        captureScope();
        assertEquals(
                "",
                service.test(WF, "t1", new WorkflowNodeTestRequest(null, null), null).inputJson());
    }

    @Test
    void aRunnerBelowEditorNeverReadsPersistedPinsThroughTheStepTest() {
        when(workflowService.requireAccess(WF, PermissionLevel.EDITOR))
                .thenThrow(new com.agenticai.core.sharing.ResourceAccessDeniedException());
        withPins().save(WF, "up", "{\"secret\":\"pii\"}", "manual", null, "ann", null);
        graph(
                List.of(new WorkflowEdgeDto("e1", "up", "t1", null, null)),
                node("up", "trigger", Map.of()),
                node("t1", "code", Map.of("operation", "uppercase")));
        java.util.concurrent.atomic.AtomicReference<ExpressionScope> seen = captureScope();

        NodeTraceEntry e = service.test(WF, "t1", new WorkflowNodeTestRequest(null, null), null);

        assertEquals("", e.inputJson(), "the saved pin must not reach the trace of a RUNNER");
        assertNull(seen.get().supplied().get("label:up"));
        // what the caller typed themselves is still theirs to use
        NodeTraceEntry own =
                service.test(
                        WF,
                        "t1",
                        new WorkflowNodeTestRequest(null, Map.of("up", Map.of("mine", 1))),
                        null);
        assertEquals("{\"mine\":1}", own.inputJson());
    }
}
