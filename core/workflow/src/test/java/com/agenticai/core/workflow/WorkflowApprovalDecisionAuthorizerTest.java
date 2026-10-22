package com.agenticai.core.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.approval.ApprovalNotAuthorizedException;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.sharing.ResourceAccessDeniedException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Deciding a workflow node's approval takes run access to that workflow. */
class WorkflowApprovalDecisionAuthorizerTest {

    private final WorkflowRunRepository runs = mock(WorkflowRunRepository.class);
    private final WorkflowService workflows = mock(WorkflowService.class);
    private final WorkflowApprovalDecisionAuthorizer authorizer =
            new WorkflowApprovalDecisionAuthorizer(
                    runs, workflows, com.agenticai.core.json.JsonMappers.plain());
    private final UUID runId = UUID.randomUUID();
    private final UUID workflowId = UUID.randomUUID();

    private ApprovalRequest approval(String payload) {
        return new ApprovalRequest("workflow.node.approval", "Approve step", payload, "bob");
    }

    @Test
    void onlyWorkflowNodeApprovalsAreItsConcern() {
        assertThat(authorizer.supports(approval("{}"))).isTrue();
        assertThat(
                        authorizer.supports(
                                new ApprovalRequest("channel.whatsapp.send", "s", "{}", "x")))
                .isFalse();
    }

    @Test
    void aMemberWithRunAccessMayDecide() {
        when(runs.findById(runId))
                .thenReturn(Optional.of(new WorkflowRun(runId, workflowId, "wf", "bob")));
        assertThatCode(() -> authorizer.authorize(approval("{\"runId\":\"" + runId + "\"}")))
                .doesNotThrowAnyException();
    }

    @Test
    void aMemberWithoutRunAccessMayNot() {
        when(runs.findById(runId))
                .thenReturn(Optional.of(new WorkflowRun(runId, workflowId, "wf", "bob")));
        doThrow(new ResourceAccessDeniedException())
                .when(workflows)
                .requireAccess(workflowId, PermissionLevel.RUNNER);

        assertThatThrownBy(() -> authorizer.authorize(approval("{\"runId\":\"" + runId + "\"}")))
                .isInstanceOf(ApprovalNotAuthorizedException.class);
    }

    @Test
    void anUnknownRunOrAPayloadWithoutOneIsRefused() {
        when(runs.findById(runId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> authorizer.authorize(approval("{\"runId\":\"" + runId + "\"}")))
                .isInstanceOf(ApprovalNotAuthorizedException.class);
        assertThatThrownBy(() -> authorizer.authorize(approval("{}")))
                .isInstanceOf(ApprovalNotAuthorizedException.class);
        assertThatThrownBy(() -> authorizer.authorize(approval("not json")))
                .isInstanceOf(ApprovalNotAuthorizedException.class);
    }
}
