package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.workflow.diagram.WorkflowDiagramPdfRenderer;
import com.agenticai.core.workflow.diagram.WorkflowDiagramService;
import com.agenticai.core.workflow.dto.WorkflowRunResponse;
import com.agenticai.core.workflow.guidance.WorkflowInsightsService;
import com.agenticai.core.workflow.guidance.WorkflowLintService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * X-Total-Count / X-Has-More and ?offset= on GET /api/workflows/{id}/runs (the canvas polls this).
 */
class WorkflowControllerRunsHeadersTest {

    @org.junit.jupiter.api.BeforeEach
    void defaultCapOn() {
        System.setProperty("agenticai.list.default-cap", "true");
    }

    @org.junit.jupiter.api.AfterEach
    void defaultCapOff() {
        System.clearProperty("agenticai.list.default-cap");
    }

    private WorkflowService service;
    private WorkflowController controller;
    private final UUID id = UUID.randomUUID();
    private final WorkflowRunResponse row = mock(WorkflowRunResponse.class);

    @BeforeEach
    void setUp() {
        service = mock(WorkflowService.class);
        controller =
                new WorkflowController(
                        service,
                        mock(WorkflowGraphGeneratorService.class),
                        mock(WorkflowDiagramService.class),
                        mock(WorkflowDiagramPdfRenderer.class),
                        mock(WorkflowBackgroundRunService.class),
                        mock(WorkflowNodeTestService.class),
                        mock(WorkflowLintService.class),
                        mock(WorkflowInsightsService.class));
    }

    @Test
    void shortPageSkipsTheCountQuery() {
        when(service.listRuns(eq(id), eq(5), any())).thenReturn(List.of(row));
        var res = controller.runs(id, 5, null, null);
        assertEquals("1", res.getHeaders().getFirst("X-Total-Count"));
        assertEquals("false", res.getHeaders().getFirst("X-Has-More"));
        verify(service, never()).countRuns(any());
    }

    @Test
    void fullPageCountsAndFlagsMore() {
        when(service.listRuns(eq(id), eq(1), any())).thenReturn(List.of(row));
        when(service.countRuns(id)).thenReturn(450L);
        var res = controller.runs(id, 1, null, null);
        assertEquals("450", res.getHeaders().getFirst("X-Total-Count"));
        assertEquals("true", res.getHeaders().getFirst("X-Has-More"));
    }

    @Test
    void offsetIsPassedThrough() {
        when(service.listRuns(eq(id), eq(10), eq(200L))).thenReturn(List.of(row));
        when(service.countRuns(id)).thenReturn(201L);
        var res = controller.runs(id, 10, 200L, null);
        assertEquals("201", res.getHeaders().getFirst("X-Total-Count"));
        assertEquals("false", res.getHeaders().getFirst("X-Has-More"));
    }

    @Test
    void cursorSwitchesToKeysetPagingWithoutACount() {
        java.time.Instant t = java.time.Instant.parse("2026-10-16T10:00:00Z");
        UUID lastId = UUID.randomUUID();
        WorkflowRunResponse a = mock(WorkflowRunResponse.class);
        WorkflowRunResponse b = mock(WorkflowRunResponse.class);
        when(b.startedAt()).thenReturn(t);
        when(b.id()).thenReturn(lastId);
        com.agenticai.core.audit.KeysetCursor after =
                new com.agenticai.core.audit.KeysetCursor(t.plusSeconds(60), UUID.randomUUID());
        // size 2 asked, 3 fetched: one more page exists, cursor = last row kept.
        when(service.listRunsAfter(eq(id), eq(after), eq(2))).thenReturn(List.of(a, b, row));
        var res = controller.runs(id, 2, 999L, after.encode());
        assertEquals(2, res.getBody().size());
        assertEquals("true", res.getHeaders().getFirst("X-Has-More"));
        assertEquals(
                new com.agenticai.core.audit.KeysetCursor(t, lastId).encode(),
                res.getHeaders().getFirst(com.agenticai.core.audit.KeysetCursor.NEXT_CURSOR_HEADER));
        assertEquals(null, res.getHeaders().getFirst("X-Total-Count"));
        verify(service, never()).countRuns(any());
        verify(service, never()).listRuns(any(), any(), any());
    }
}
