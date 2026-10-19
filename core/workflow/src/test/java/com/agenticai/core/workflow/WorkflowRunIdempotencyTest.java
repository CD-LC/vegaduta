package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.omnichannel.session.InboundMessageDedupService;
import com.agenticai.core.workflow.diagram.WorkflowDiagramPdfRenderer;
import com.agenticai.core.workflow.diagram.WorkflowDiagramService;
import com.agenticai.core.workflow.dto.WorkflowRunResponse;
import com.agenticai.core.workflow.guidance.WorkflowInsightsService;
import com.agenticai.core.workflow.guidance.WorkflowLintService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@code Idempotency-Key} on POST /api/workflows/{id}/run (V483) - the
 * headless-caller retry guard: a network-timeout replay must 409 instead of
 * silently starting a second run.
 */
class WorkflowRunIdempotencyTest {

    private WorkflowService service;
    private InboundMessageDedupService dedup;
    private WorkflowController controller;
    private final UUID id = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = mock(WorkflowService.class);
        dedup = mock(InboundMessageDedupService.class);
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
        controller.setRunIdempotencyDedup(dedup);
    }

    @Test
    void firstKeyClaimsAndRuns() {
        when(dedup.tryClaim(eq("workflow-run"), contains("key-1"))).thenReturn(true);
        when(service.run(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(mock(WorkflowRunResponse.class));
        controller.run(id, null, "key-1", null);
        verify(service).run(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void replayedKeyConflictsAndNeverRuns() {
        when(dedup.tryClaim(eq("workflow-run"), contains("key-2"))).thenReturn(false);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> controller.run(id, null, "key-2", null));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(service, never()).run(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void noHeaderSkipsDedupEntirely() {
        when(service.run(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(mock(WorkflowRunResponse.class));
        controller.run(id, null, null, null);
        verify(dedup, never()).tryClaim(anyString(), anyString());
    }

    @Test
    void overlongKeyIsRejected() {
        String longKey = "x".repeat(129);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> controller.run(id, null, longKey, null));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }
}
