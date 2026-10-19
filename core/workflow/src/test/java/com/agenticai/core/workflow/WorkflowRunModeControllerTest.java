package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.workflow.dto.WorkflowPinRequest;
import com.agenticai.core.workflow.dto.WorkflowPinResponse;
import com.agenticai.core.workflow.dto.WorkflowRunRequest;
import com.agenticai.core.workflow.dto.WorkflowRunResponse;
import com.agenticai.core.workflow.diagram.WorkflowDiagramPdfRenderer;
import com.agenticai.core.workflow.diagram.WorkflowDiagramService;
import com.agenticai.core.workflow.guidance.WorkflowInsightsService;
import com.agenticai.core.workflow.guidance.WorkflowLintService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.Principal;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

/**
 * The run endpoint's optional {@code mode}, and the thin pin / expression controllers. This
 * codebase has no MockMvc harness (see WorkflowRunScopingSecurityTest), so controllers are driven
 * directly.
 */
class WorkflowRunModeControllerTest {

    private static final UUID WF = UUID.randomUUID();

    private WorkflowService workflowService;
    private WorkflowBackgroundRunService background;
    private WorkflowController controller;
    private final Principal ann = () -> "ann";

    @BeforeEach
    void setUp() {
        workflowService = mock(WorkflowService.class);
        background = mock(WorkflowBackgroundRunService.class);
        controller =
                new WorkflowController(
                        workflowService,
                        mock(WorkflowGraphGeneratorService.class),
                        mock(WorkflowDiagramService.class),
                        mock(WorkflowDiagramPdfRenderer.class),
                        background,
                        mock(WorkflowNodeTestService.class),
                        mock(WorkflowLintService.class),
                        mock(WorkflowInsightsService.class));
    }

    private static WorkflowRunRequest request(String mode, Boolean backgroundRun) {
        return new WorkflowRunRequest("in", "by", null, null, backgroundRun, mode);
    }

    @Test
    void theCanvasDraftModeReachesTheService() {
        controller.run(WF, request("draft", null), null, ann);
        verify(workflowService)
                .run(eq(WF), isNull(), eq("in"), eq("by"), isNull(), anyList(), eq(RunMode.DRAFT));
    }

    @Test
    void theModeIsCaseInsensitive() {
        controller.run(WF, request("DRAFT", null), null, ann);
        verify(workflowService)
                .run(eq(WF), isNull(), eq("in"), eq("by"), isNull(), anyList(), eq(RunMode.DRAFT));
    }

    @Test
    void noModeBlankModeAndNoBodyAreAllProduction() {
        controller.run(WF, request(null, null), null, ann);
        controller.run(WF, request("  ", null), null, ann);
        controller.run(WF, request("production", null), null, ann);
        controller.run(WF, null, null, ann);
        verify(workflowService, org.mockito.Mockito.times(4))
                .run(eq(WF), isNull(), any(), any(), isNull(), anyList(), eq(RunMode.PRODUCTION));
        verify(workflowService, never())
                .run(any(), any(), any(), any(), any(), anyList(), eq(RunMode.DRAFT));
    }

    @Test
    void aBackgroundDraftRunKeepsItsMode() {
        controller.run(WF, request("draft", true), null, ann);
        verify(background)
                .start(eq(WF), eq("in"), eq("by"), isNull(), anyList(), eq(RunMode.DRAFT));
        verify(workflowService, never()).run(any(), any(), any(), any(), any(), anyList(), any());
    }

    @Test
    void anUnknownModeIs400AndNothingRuns() {
        for (String bad : List.of("test", "staging", "drafts")) {
            ResponseStatusException ex =
                    assertThrows(
                            ResponseStatusException.class,
                            () -> controller.run(WF, request(bad, null), null, ann));
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode(), bad);
        }
        verify(workflowService, never()).run(any(), any(), any(), any(), any(), anyList(), any());
        verify(background, never()).start(any(), any(), any(), any(), anyList(), any());
    }

    @Test
    void accessIsCheckedFirstRunnerForProductionEditorForDraft() {
        controller.run(WF, request(null, null), null, ann);
        verify(workflowService).requireAccess(WF, PermissionLevel.RUNNER);
        controller.run(WF, request("draft", null), null, ann);
        verify(workflowService).requireAccess(WF, PermissionLevel.EDITOR);
    }

    @Test
    void theRequestAndResponseCarryTheContractFields() throws Exception {
        ObjectMapper mapper = PinTestSupport.appMapper();
        WorkflowRunRequest parsed =
                mapper.readValue(
                        "{\"input\":\"x\",\"mode\":\"draft\",\"background\":true}",
                        WorkflowRunRequest.class);
        assertEquals("draft", parsed.mode());
        assertEquals("x", parsed.input());
        assertNull(mapper.readValue("{\"input\":\"x\"}", WorkflowRunRequest.class).mode());

        WorkflowRun run = new WorkflowRun(UUID.randomUUID(), WF, "WF", "u");
        run.setRunMode(RunMode.DRAFT);
        JsonNode json =
                mapper.readTree(mapper.writeValueAsString(WorkflowRunResponse.from(run, mapper)));
        assertEquals("draft", json.get("mode").asText());
        assertEquals(0, json.get("pinnedNodeCount").asInt());
        for (String existing :
                List.of(
                        "id",
                        "workflowId",
                        "workflowName",
                        "status",
                        "requestedBy",
                        "errorMessage",
                        "trace",
                        "startedAt",
                        "completedAt",
                        "outputFileName",
                        "workflowVersion")) {
            assertTrue(json.has(existing), "existing field kept: " + existing);
        }
    }

    // ---- pin controller

    @Test
    void thePinControllerPassesTheCallerAndAnswers204ForDelete() {
        WorkflowPinService pins = mock(WorkflowPinService.class);
        WorkflowPinController pinController = new WorkflowPinController(pins);
        WorkflowPinRequest body = new WorkflowPinRequest("{}", "manual", null, null);
        WorkflowPinResponse stored =
                new WorkflowPinResponse(
                        "n1", "manual", null, "ann", "2026-10-01T00:00:00Z", 2, "h", "{}");
        when(pins.put(WF, "n1", body, "ann")).thenReturn(stored);
        when(pins.list(WF)).thenReturn(List.of(stored));

        assertEquals(stored, pinController.put(WF, "n1", body, ann));
        assertEquals(List.of(stored), pinController.list(WF, null, null).getBody());
        ResponseEntity<Void> deleted = pinController.delete(WF, "n1", ann);
        assertEquals(HttpStatus.NO_CONTENT, deleted.getStatusCode());
        verify(pins).delete(WF, "n1", "ann");
    }

    @Test
    void theExpressionControllerServesTheFunctionRegistryOutsideTheWorkflowsPath()
            throws Exception {
        WorkflowExpressionService expressions = mock(WorkflowExpressionService.class);
        WorkflowExpressionController c = new WorkflowExpressionController(expressions);
        when(expressions.functions()).thenReturn(List.of());
        assertEquals(List.of(), c.functions());

        java.lang.reflect.Method m = WorkflowExpressionController.class.getMethod("functions");
        String path =
                m.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class)
                        .value()[0];
        String base =
                WorkflowExpressionController.class.getAnnotation(
                                org.springframework.web.bind.annotation.RequestMapping.class)
                        .value()[0];
        assertEquals("/api/workflow-expression-functions", base + path);
        assertFalse(
                (base + path).startsWith("/api/workflows/"),
                "must not collide with GET /api/workflows/{workflowId}");
    }

    @Test
    void everyExpressionRouteMatchesTheContractPaths() throws Exception {
        String base =
                WorkflowExpressionController.class.getAnnotation(
                                org.springframework.web.bind.annotation.RequestMapping.class)
                        .value()[0];
        Set<String> routes = new TreeSet<>();
        for (java.lang.reflect.Method m : WorkflowExpressionController.class.getDeclaredMethods()) {
            var post = m.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class);
            var get = m.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class);
            if (post != null) {
                routes.add("POST " + base + post.value()[0]);
            }
            if (get != null) {
                routes.add("GET " + base + get.value()[0]);
            }
        }
        assertEquals(
                new TreeSet<>(
                        Set.of(
                                "POST /api/workflows/{workflowId}/expressions/evaluate",
                                "POST /api/workflows/{workflowId}/expressions/lint",
                                "GET /api/workflows/{workflowId}/nodes/{nodeId}/upstream-schema",
                                "GET /api/workflow-expression-functions")),
                routes);

        Set<String> pinRoutes = new TreeSet<>();
        // WorkflowPinController carries full paths on its methods (no class-level base, so it
        // does not share WorkflowController's /api/workflows base -
        // scripts/ci/entity-name-lint.sh).
        var pinMapping =
                WorkflowPinController.class.getAnnotation(
                        org.springframework.web.bind.annotation.RequestMapping.class);
        String pinBase = pinMapping == null ? "" : pinMapping.value()[0];
        for (java.lang.reflect.Method m : WorkflowPinController.class.getDeclaredMethods()) {
            var put = m.getAnnotation(org.springframework.web.bind.annotation.PutMapping.class);
            var get = m.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class);
            var del = m.getAnnotation(org.springframework.web.bind.annotation.DeleteMapping.class);
            if (put != null) {
                pinRoutes.add("PUT " + pinBase + put.value()[0]);
            }
            if (get != null) {
                pinRoutes.add("GET " + pinBase + get.value()[0]);
            }
            if (del != null) {
                pinRoutes.add("DELETE " + pinBase + del.value()[0]);
            }
        }
        assertEquals(
                new TreeSet<>(
                        Set.of(
                                "GET /api/workflows/{workflowId}/pins",
                                "PUT /api/workflows/{workflowId}/nodes/{nodeId}/pin",
                                "DELETE /api/workflows/{workflowId}/nodes/{nodeId}/pin")),
                pinRoutes);
    }

    @Test
    void aDraftRunNeedsEditorAndARunnerIsRefusedBeforeAnythingRuns() {
        when(workflowService.requireAccess(WF, PermissionLevel.EDITOR))
                .thenThrow(new com.agenticai.core.sharing.ResourceAccessDeniedException());
        assertThrows(
                com.agenticai.core.sharing.ResourceAccessDeniedException.class,
                () -> controller.run(WF, request("draft", null), null, ann));
        assertThrows(
                com.agenticai.core.sharing.ResourceAccessDeniedException.class,
                () -> controller.run(WF, request("draft", true), null, ann));
        verify(workflowService, never()).run(any(), any(), any(), any(), any(), anyList(), any());
        verify(background, never()).start(any(), any(), any(), any(), anyList(), any());
    }

    @Test
    void aRunnerStillStartsProductionRuns() {
        when(workflowService.requireAccess(WF, PermissionLevel.EDITOR))
                .thenThrow(new com.agenticai.core.sharing.ResourceAccessDeniedException());
        controller.run(WF, request("production", null), null, ann);
        controller.run(WF, request(null, null), null, ann);
        verify(workflowService, org.mockito.Mockito.times(2))
                .run(
                        eq(WF),
                        isNull(),
                        eq("in"),
                        eq("by"),
                        isNull(),
                        anyList(),
                        eq(RunMode.PRODUCTION));
    }
}
