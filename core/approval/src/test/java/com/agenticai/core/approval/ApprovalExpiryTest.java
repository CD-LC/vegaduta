package com.agenticai.core.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.rl.feedback.FeedbackService;
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;

/**
 * A request nobody decides used to wait forever and stay approvable. Real gate, real recorder and real
 * entity over a mocked repository: the request is closed, the audit says EXPIRED, a late approve is
 * refused, the executor is told, and with the TTL off (the default) nothing is touched.
 */
class ApprovalExpiryTest {

    private ApprovalRequestRepository repository;
    private AuditLogService audit;
    private ApplicationEventPublisher events;
    private WorkflowEventPublisher workflowEvents;
    private ApprovalActionExecutorRegistry registry;
    private ApprovalActionExecutor executor;
    private HumanApprovalGateService gate;

    @BeforeEach
    void setUp() {
        repository = mock(ApprovalRequestRepository.class);
        audit = mock(AuditLogService.class);
        events = mock(ApplicationEventPublisher.class);
        workflowEvents = mock(WorkflowEventPublisher.class);
        registry = mock(ApprovalActionExecutorRegistry.class);
        executor = mock(ApprovalActionExecutor.class);
        when(registry.find(anyString())).thenReturn(Optional.of(executor));
        when(repository.save(any(ApprovalRequest.class))).thenAnswer(call -> call.getArgument(0));
        ApprovalRequestRecorder recorder = new ApprovalRequestRecorder(repository, audit, events, workflowEvents);
        gate = new HumanApprovalGateService(
            mock(ApprovalPolicyService.class), repository, registry, recorder, mock(FeedbackService.class),
            new ObjectMapper(), 5, 30, 3600);
    }

    private ApprovalRequest pending() throws Exception {
        ApprovalRequest request = new ApprovalRequest("mcp_tool.send", "send it", "{}", "agent:a:session:s");
        Field id = ApprovalRequest.class.getDeclaredField("id");
        id.setAccessible(true);
        id.set(request, UUID.randomUUID());
        when(repository.findById(request.getId())).thenReturn(Optional.of(request));
        when(repository.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));
        return request;
    }

    private static ApprovalExpiryProperties bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
            .bind("app.approvals.expiry", ApprovalExpiryProperties.class)
            .orElseGet(ApprovalExpiryProperties::new);
    }

    // ------------------------------------------------------------------ properties

    @Test
    void expiryIsOffByDefaultSoBehaviourIsUnchanged() {
        ApprovalExpiryProperties defaults = new ApprovalExpiryProperties();

        assertThat(defaults.isEnabled()).isFalse();
        assertThat(defaults.getTtl()).isEqualTo(Duration.ZERO);
        assertThat(defaults.getSweepInterval()).isEqualTo(Duration.ofMinutes(5));
        assertThat(defaults.getBatchSize()).isEqualTo(200);
        assertThat(defaults.getExpiredReason()).contains("{ttl}").contains("NOT performed");
    }

    @Test
    void thePropertyNamesBindAndTheReasonStatesTheWait() {
        ApprovalExpiryProperties bound = bind(Map.of(
            "app.approvals.expiry.ttl", "24h",
            "app.approvals.expiry.sweep-interval", "30s",
            "app.approvals.expiry.batch-size", "50"));

        assertThat(bound.isEnabled()).isTrue();
        assertThat(bound.getTtl()).isEqualTo(Duration.ofHours(24));
        assertThat(bound.getSweepInterval()).isEqualTo(Duration.ofSeconds(30));
        assertThat(bound.getBatchSize()).isEqualTo(50);
        assertThat(bound.reason()).contains("within 24 hours").doesNotContain("{ttl}");
        assertThat(ApprovalExpiryProperties.describe(Duration.ofMinutes(90))).isEqualTo("90 minutes");
        assertThat(ApprovalExpiryProperties.describe(Duration.ofDays(1))).isEqualTo("24 hours");
        assertThat(ApprovalExpiryProperties.describe(Duration.ofDays(7))).isEqualTo("7 days");
    }

    // ------------------------------------------------------------------ the request

    @Test
    void anExpiredRequestIsRejectedByTheExpiryActorWithTheReason() throws Exception {
        ApprovalRequest request = pending();

        request.markExpired("nobody came");

        assertThat(request.getStatus()).isEqualTo(ApprovalStatus.REJECTED);
        assertThat(request.getDecidedBy()).isEqualTo(ApprovalRequest.EXPIRY_ACTOR);
        assertThat(request.getRejectionReason()).isEqualTo("nobody came");
        assertThat(request.getDecidedAt()).isNotNull();
        assertThat(request.isExpired()).isTrue();

        ApprovalRequest rejectedByAPerson = new ApprovalRequest("a", "s", "{}", "r");
        rejectedByAPerson.markRejected("alice", "no");
        assertThat(rejectedByAPerson.isExpired()).as("a human rejection is not an expiry").isFalse();
    }

    // ------------------------------------------------------------------ the gate

    @Test
    void expiringClosesTheRequestAuditsItAndTellsTheExecutor() throws Exception {
        ApprovalRequest request = pending();

        ApprovalRequest result = gate.expire(request.getId(), "expired reason");

        assertThat(result.getStatus()).isEqualTo(ApprovalStatus.REJECTED);
        assertThat(result.isExpired()).isTrue();
        verify(audit).record(
            eq(ApprovalRequest.EXPIRY_ACTOR), eq("mcp_tool.send"), eq("approval_request"),
            eq(request.getId().toString()), eq(AuditOutcome.EXPIRED), eq("expired reason"));
        verify(executor).onRejected(request, ApprovalRequest.EXPIRY_ACTOR, "expired reason");
        verify(executor, never()).execute(any());
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(workflowEvents).publish(eq("approval.resolved"), eq(request.getId().toString()), payload.capture());
        assertThat(payload.getValue()).containsEntry("decision", "expired");
    }

    @Test
    void aLateApproveOfAnExpiredRequestIsRefusedAndNeverRunsTheAction() throws Exception {
        ApprovalRequest request = pending();
        gate.expire(request.getId(), "expired reason");

        assertThatThrownBy(() -> gate.approve(request.getId(), "alice"))
            .isInstanceOf(ApprovalRequestAlreadyDecidedException.class);
        verify(executor, never()).execute(any());
    }

    @Test
    void aRequestADecidedFirstCannotBeExpired() throws Exception {
        ApprovalRequest request = pending();
        request.markApproved("alice");

        assertThatThrownBy(() -> gate.expire(request.getId(), "expired reason"))
            .isInstanceOf(ApprovalRequestAlreadyDecidedException.class);
    }

    // ------------------------------------------------------------------ the sweep

    private ApprovalExpiryJob job(ApprovalExpiryProperties properties) {
        TenantSchemaIterator iterator = mock(TenantSchemaIterator.class);
        org.mockito.Mockito.doAnswer(call -> {
            call.<Consumer<String>>getArgument(0).accept("tenant_a");
            return null;
        }).when(iterator).forEachActiveTenantSchema(any());
        return new ApprovalExpiryJob(properties, iterator, repository, gate, mock(PgAdvisoryJobLock.class));
    }

    @Test
    void withTheTtlOffTheSweepTouchesNothing() {
        assertThat(job(new ApprovalExpiryProperties()).sweep(Instant.now())).isZero();
        verify(repository, never()).findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(any(), any(), any());
    }

    @Test
    void theSweepExpiresPendingRequestsOlderThanTheTtlAndSurvivesABadRow() throws Exception {
        ApprovalRequest first = pending();
        ApprovalRequest decidedFirst = pending();
        decidedFirst.markApproved("alice");
        ApprovalRequest third = pending();
        Instant now = Instant.parse("2026-10-23T12:00:00Z");
        when(repository.findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
                eq(ApprovalStatus.PENDING), eq(now.minus(Duration.ofHours(2))), any(Pageable.class)))
            .thenReturn(List.of(first, decidedFirst, third));

        int expired = job(bind(Map.of("app.approvals.expiry.ttl", "2h"))).sweep(now);

        assertThat(expired).isEqualTo(2);
        assertThat(first.isExpired()).isTrue();
        assertThat(third.isExpired()).isTrue();
        assertThat(decidedFirst.getStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(first.getRejectionReason()).contains("2 hours");
    }
}
