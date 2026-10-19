package com.agenticai.core.approval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.AuditLogService;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Proves requirement (a) at its source: {@link ApprovalRequestExecutionRecordedEvent}
 * fires from EXACTLY the {@link ApprovalRequestRecorder} methods that make an
 * execution outcome durable, and not from the ones that don't - see that
 * event's own javadoc for the full list and reasoning. {@code recordNew} and
 * {@code recordApproved}/{@code recordRejected} are pre-execution and
 * intentionally out of scope here (covered implicitly: nothing in this suite
 * stubs an interaction on a mock event publisher those methods never touch).
 */
class ApprovalRequestRecorderTest {

    private ApprovalRequestRepository requestRepository;
    private AuditLogService auditLogService;
    private ApplicationEventPublisher eventPublisher;
    private ApprovalRequestRecorder recorder;

    @BeforeEach
    void setUp() {
        requestRepository = mock(ApprovalRequestRepository.class);
        when(requestRepository.save(any(ApprovalRequest.class))).thenAnswer(inv -> inv.getArgument(0));
        auditLogService = mock(AuditLogService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        recorder = new ApprovalRequestRecorder(requestRepository, auditLogService, eventPublisher,
            mock(com.agenticai.core.eventbus.WorkflowEventPublisher.class));
    }

    /**
     * A real (not mocked) {@link ApprovalRequest} with {@code id} set via
     * reflection - JPA normally assigns it on persist, which never happens in
     * a pure unit test, and the recorder's audit-logging calls need a
     * non-null id to stringify. Using the real entity (rather than mocking
     * it, as {@code HumanApprovalGateServiceTest} does) is deliberate here:
     * these tests exercise the real {@code mark*}/{@code scheduleRetry}
     * mutators the recorder calls, not just its interactions.
     */
    private ApprovalRequest pendingRequest() {
        ApprovalRequest request = new ApprovalRequest("mcp_tool.send_message", "summary", "{}",
            "agent:" + java.util.UUID.randomUUID() + ":session:job:" + java.util.UUID.randomUUID());
        try {
            java.lang.reflect.Field idField = ApprovalRequest.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(request, java.util.UUID.randomUUID());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return request;
    }

    @Test
    void recordExecutionFailedPublishesTheEvent() {
        ApprovalRequest request = pendingRequest();
        request.markApproved("admin@example.com");

        recorder.recordExecutionFailed(request, "admin@example.com", "boom");

        ArgumentCaptor<ApprovalRequestExecutionRecordedEvent> captor =
            ArgumentCaptor.forClass(ApprovalRequestExecutionRecordedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertEquals(request.getId(), captor.getValue().getRequestId());
        assertEquals(request.getRequestedBy(), captor.getValue().getRequestedBy());
    }

    @Test
    void recordDeadLetteredPublishesTheEvent() {
        ApprovalRequest request = pendingRequest();
        request.markApproved("admin@example.com");

        recorder.recordDeadLettered(request, "exhausted");

        verify(eventPublisher).publishEvent(any(ApprovalRequestExecutionRecordedEvent.class));
    }

    @Test
    void recordRetrySucceededPublishesTheEvent() {
        ApprovalRequest request = pendingRequest();
        request.markApproved("admin@example.com");

        recorder.recordRetrySucceeded(request);

        verify(eventPublisher).publishEvent(any(ApprovalRequestExecutionRecordedEvent.class));
    }

    /**
     * {@code recordRetryScheduled} means "still settling" (a NEW retry is now
     * pending) - firing the event here would tell a listener to resume into a
     * gated tool call that has not actually finished, racing the retry it is
     * scheduled to run.
     */
    @Test
    void recordRetryScheduledDoesNotPublish() {
        ApprovalRequest request = pendingRequest();
        request.markApproved("admin@example.com");

        recorder.recordRetryScheduled(request, "503", Instant.now().plusSeconds(30));

        verify(eventPublisher, never()).publishEvent(any());
    }

    /** No executionResult is ever recorded here, so nothing about "settled" changed. */
    @Test
    void recordNoExecutorRegisteredDoesNotPublish() {
        ApprovalRequest request = pendingRequest();
        request.markApproved("admin@example.com");

        recorder.recordNoExecutorRegistered(request, "admin@example.com");

        verify(eventPublisher, never()).publishEvent(any());
    }

    /** The executor has not run yet at this point - nothing has settled. */
    @Test
    void recordApprovedDoesNotPublish() {
        ApprovalRequest request = pendingRequest();
        when(requestRepository.findByIdForUpdate(request.getId())).thenReturn(java.util.Optional.of(request));

        recorder.recordApproved(request, "admin@example.com");

        verify(eventPublisher, never()).publishEvent(any());
    }

    /**
     * The race: two deciders both passed the outside-the-transaction PENDING check. The second
     * one's locked re-read sees the first decision and must neither save nor audit nor publish.
     */
    @Test
    void aSecondApproveOfTheSameRequestIsRefusedUnderTheLock() {
        ApprovalRequest stale = pendingRequest();
        ApprovalRequest lockedRow = pendingRequest();
        setId(lockedRow, stale.getId());
        lockedRow.markApproved("first-admin");
        when(requestRepository.findByIdForUpdate(stale.getId())).thenReturn(java.util.Optional.of(lockedRow));

        org.junit.jupiter.api.Assertions.assertThrows(
            ApprovalRequestAlreadyDecidedException.class,
            () -> recorder.recordApproved(stale, "second-admin"));

        verify(requestRepository, never()).save(any(ApprovalRequest.class));
        verify(auditLogService, never()).record(any(), any(), any(), any(), any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void rejectingAnAlreadyApprovedRequestIsRefusedUnderTheLock() {
        ApprovalRequest stale = pendingRequest();
        ApprovalRequest lockedRow = pendingRequest();
        setId(lockedRow, stale.getId());
        lockedRow.markApproved("first-admin");
        when(requestRepository.findByIdForUpdate(stale.getId())).thenReturn(java.util.Optional.of(lockedRow));

        org.junit.jupiter.api.Assertions.assertThrows(
            ApprovalRequestAlreadyDecidedException.class,
            () -> recorder.recordRejected(stale, "second-admin", "too late"));

        verify(requestRepository, never()).save(any(ApprovalRequest.class));
    }

    @Test
    void theDecisionIsAppliedToTheLockedRowNotTheStaleCopy() {
        ApprovalRequest stale = pendingRequest();
        ApprovalRequest lockedRow = pendingRequest();
        setId(lockedRow, stale.getId());
        when(requestRepository.findByIdForUpdate(stale.getId())).thenReturn(java.util.Optional.of(lockedRow));

        ApprovalRequest result = recorder.recordApproved(stale, "admin@example.com");

        assertEquals(ApprovalStatus.APPROVED, result.getStatus());
        assertEquals(ApprovalStatus.APPROVED, lockedRow.getStatus());
        assertEquals(ApprovalStatus.PENDING, stale.getStatus());
    }

    @Test
    void aRequestThatNoLongerExistsIsNotFound() {
        ApprovalRequest request = pendingRequest();
        when(requestRepository.findByIdForUpdate(request.getId())).thenReturn(java.util.Optional.empty());

        org.junit.jupiter.api.Assertions.assertThrows(
            ApprovalRequestNotFoundException.class,
            () -> recorder.recordApproved(request, "admin@example.com"));
    }

    private static void setId(ApprovalRequest request, java.util.UUID id) {
        try {
            java.lang.reflect.Field idField = ApprovalRequest.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(request, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
