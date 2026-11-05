package com.agenticai.core.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.rl.feedback.FeedbackService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * approve(id, by, formValues) used to rewrite and save the payload BEFORE taking the row lock, so a
 * request decided in that window still had its payload changed. The merge now happens inside the
 * recorder's locked transaction, after the PENDING check.
 */
class ApprovalFormValuesRaceTest {

    private static final String PAYLOAD = "{\"formFields\":[\"reason\"],\"resume\":{}}";

    private ApprovalRequestRepository repository;
    private HumanApprovalGateService gate;

    @BeforeEach
    void setUp() {
        repository = mock(ApprovalRequestRepository.class);
        ApprovalActionExecutorRegistry registry = mock(ApprovalActionExecutorRegistry.class);
        when(registry.find(anyString())).thenReturn(Optional.empty());
        when(repository.save(any(ApprovalRequest.class))).thenAnswer(call -> call.getArgument(0));
        ApprovalRequestRecorder recorder =
                new ApprovalRequestRecorder(
                        repository,
                        mock(AuditLogService.class),
                        mock(ApplicationEventPublisher.class),
                        mock(WorkflowEventPublisher.class));
        gate =
                new HumanApprovalGateService(
                        mock(ApprovalPolicyService.class),
                        repository,
                        registry,
                        recorder,
                        mock(FeedbackService.class),
                        new ObjectMapper(),
                        5,
                        30,
                        3600);
    }

    private ApprovalRequest request(UUID id) throws Exception {
        ApprovalRequest request = new ApprovalRequest("workflow.approval", "ask", PAYLOAD, "wf");
        Field field = ApprovalRequest.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(request, id);
        return request;
    }

    @Test
    void aRequestDecidedBeforeTheLockKeepsItsPayload() throws Exception {
        UUID id = UUID.randomUUID();
        ApprovalRequest staleView = request(id); // what the unlocked read saw: PENDING
        ApprovalRequest lockedRow =
                request(id); // what the locked read sees: someone got there first
        lockedRow.markRejected("other-admin", "no");
        when(repository.findById(id)).thenReturn(Optional.of(staleView));
        when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(lockedRow));

        assertThatThrownBy(() -> gate.approve(id, "admin", Map.of("reason", "because")))
                .isInstanceOf(ApprovalRequestAlreadyDecidedException.class);

        assertThat(staleView.getPayloadJson()).isEqualTo(PAYLOAD);
        assertThat(lockedRow.getPayloadJson()).isEqualTo(PAYLOAD);
        verify(repository, never()).save(any(ApprovalRequest.class));
    }

    @Test
    void aPendingRequestGetsTheMergedPayloadAndIsApproved() throws Exception {
        UUID id = UUID.randomUUID();
        ApprovalRequest row = request(id);
        when(repository.findById(id)).thenReturn(Optional.of(row));
        when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(row));

        ApprovalRequest result = gate.approve(id, "admin", Map.of("reason", "because"));

        assertThat(result.getStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(result.getPayloadJson()).contains("\"formValues\":{\"reason\":\"because\"}");
    }
}
