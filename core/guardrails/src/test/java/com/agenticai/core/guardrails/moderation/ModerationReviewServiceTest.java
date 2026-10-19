package com.agenticai.core.guardrails.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Phase P6. Queue enqueue + reviewer decisions, and that every path audits. */
class ModerationReviewServiceTest {

    private ModerationReviewItemRepository repo;
    private AuditLogService auditLog;
    private ModerationReviewService service;

    private final ModerationContext ctx =
        new ModerationContext("t1", "PRO", UUID.randomUUID(), "sess-1", "actor-1", "user_input");

    @BeforeEach
    void setUp() {
        repo = mock(ModerationReviewItemRepository.class);
        auditLog = mock(AuditLogService.class);
        service = new ModerationReviewService(repo, auditLog);
    }

    @Test
    void enqueuePersistsAndAudits() {
        UUID id = UUID.randomUUID();
        when(repo.save(any())).thenAnswer(inv -> {
            ModerationReviewItem saved = mock(ModerationReviewItem.class);
            when(saved.getId()).thenReturn(id);
            return saved;
        });

        ModerationReviewItem item = service.enqueue(
            ctx, "some content", ModerationPolicyPack.HARASSMENT, ModerationLayer.SEMANTIC, 65, "semantic scored 65/100");

        assertThat(item.getId()).isEqualTo(id);
        verify(auditLog).record(
            eq("actor-1"), eq(ModerationReviewService.ACTION_QUEUED), eq(ModerationReviewService.TARGET_TYPE),
            eq(id.toString()), eq(AuditOutcome.PENDING_APPROVAL), any());
    }

    @Test
    void approveResolvesPendingAndAudits() {
        UUID id = UUID.randomUUID();
        ModerationReviewItem item = mock(ModerationReviewItem.class);
        when(item.isPending()).thenReturn(true);
        when(item.getPolicyPack()).thenReturn("harassment");
        when(item.getConfidence()).thenReturn(65);
        when(repo.findById(id)).thenReturn(Optional.of(item));
        when(repo.save(item)).thenReturn(item);

        service.approve(id, "reviewer-1", "looks fine on review");

        verify(item).resolve(eq(ModerationReviewStatus.APPROVED), eq("reviewer-1"), eq("looks fine on review"));
        verify(auditLog).record(
            eq("reviewer-1"), eq(ModerationReviewService.ACTION_APPROVE), eq(ModerationReviewService.TARGET_TYPE),
            eq(id.toString()), eq(AuditOutcome.APPROVED), any());
    }

    @Test
    void blockAndEscalateUseDistinctActionTypes() {
        UUID id = UUID.randomUUID();
        ModerationReviewItem item = mock(ModerationReviewItem.class);
        when(item.isPending()).thenReturn(true);
        when(item.getPolicyPack()).thenReturn("pii");
        when(item.getConfidence()).thenReturn(70);
        when(repo.findById(id)).thenReturn(Optional.of(item));
        when(repo.save(item)).thenReturn(item);

        service.block(id, "reviewer-1", null);
        verify(auditLog).record(eq("reviewer-1"), eq(ModerationReviewService.ACTION_BLOCK), any(), eq(id.toString()),
            eq(AuditOutcome.REJECTED), any());

        service.escalate(id, "reviewer-1", "needs legal");
        verify(auditLog).record(eq("reviewer-1"), eq(ModerationReviewService.ACTION_ESCALATE), any(), eq(id.toString()),
            eq(AuditOutcome.PENDING_APPROVAL), any());
    }

    @Test
    void decidingAnAlreadyResolvedItemConflicts() {
        UUID id = UUID.randomUUID();
        ModerationReviewItem item = mock(ModerationReviewItem.class);
        when(item.isPending()).thenReturn(false);
        when(item.getStatus()).thenReturn(ModerationReviewStatus.BLOCKED);
        when(repo.findById(id)).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> service.approve(id, "reviewer-2", null))
            .isInstanceOf(ModerationReviewAlreadyDecidedException.class);
    }

    @Test
    void missingItemThrowsNotFound() {
        UUID id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(id)).isInstanceOf(ModerationReviewNotFoundException.class);
        assertThatThrownBy(() -> service.approve(id, "reviewer", null)).isInstanceOf(ModerationReviewNotFoundException.class);
    }
}
