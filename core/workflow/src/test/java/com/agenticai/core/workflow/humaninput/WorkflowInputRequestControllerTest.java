package com.agenticai.core.workflow.humaninput;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.sharing.ResourceAccessDeniedException;
import com.agenticai.core.workflow.WorkflowRun;
import com.agenticai.core.workflow.WorkflowRunStatus;
import com.agenticai.core.workflow.WorkflowRunnerAccess;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * Submitting a human-input form resumes the run with the caller's values, so only someone with
 * RUNNER access to the request's workflow may do it. It used to be open to every member.
 */
class WorkflowInputRequestControllerTest {

    private static final UUID REQUEST = UUID.randomUUID();
    private static final UUID WORKFLOW = UUID.randomUUID();

    private WorkflowInputRequestService service;
    private WorkflowRunnerAccess runnerAccess;
    private WorkflowInputRequestController controller;

    @BeforeEach
    void setUp() {
        service = mock(WorkflowInputRequestService.class);
        runnerAccess = mock(WorkflowRunnerAccess.class);
        controller = new WorkflowInputRequestController(service, runnerAccess);
        WorkflowInputRequest request = mock(WorkflowInputRequest.class);
        when(request.getWorkflowId()).thenReturn(WORKFLOW);
        when(service.get(REQUEST)).thenReturn(request);
    }

    @Test
    void submitChecksRunnerAccessOnTheRequestsWorkflowFirst() {
        WorkflowRun run = mock(WorkflowRun.class);
        when(run.getId()).thenReturn(UUID.randomUUID());
        when(run.getStatus()).thenReturn(WorkflowRunStatus.RUNNING);
        when(service.submit(any(), any(), any())).thenReturn(run);

        Map<String, Object> result =
                controller.submit(
                        REQUEST,
                        new WorkflowInputRequestController.SubmitRequest(Map.of("a", 1)),
                        null);

        InOrder order = inOrder(runnerAccess, service);
        order.verify(runnerAccess).requireRunnerAccess(WORKFLOW);
        order.verify(service).submit(any(), any(), any());
        assertThat(result).containsEntry("status", "RUNNING");
    }

    @Test
    void submitDeniedNeverResumesTheRun() {
        doThrow(new ResourceAccessDeniedException())
                .when(runnerAccess)
                .requireRunnerAccess(WORKFLOW);

        assertThatThrownBy(
                        () ->
                                controller.submit(
                                        REQUEST,
                                        new WorkflowInputRequestController.SubmitRequest(Map.of()),
                                        null))
                .isInstanceOf(ResourceAccessDeniedException.class);
        verify(service, never()).submit(any(), any(), any());
    }
}
