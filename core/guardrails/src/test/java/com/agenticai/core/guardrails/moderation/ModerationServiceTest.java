package com.agenticai.core.guardrails.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

/**
 * Phase P6. Proves the orchestrator's layered flow and its two hard
 * invariants: never a silent block (every block/queue audited, except the
 * external-classifier match which goes to the restricted path ONLY), and
 * default-safe (off = allow, nothing written).
 */
class ModerationServiceTest {

    private ModerationPolicyService policy;
    private PolicyPackSemanticClassifier semantic;
    private ExternalSafetyClassifier external;
    private ModerationRestrictedAuditService restrictedAudit;
    private ModerationReviewService reviewService;
    private AuditLogService auditLog;
    private ModerationService service;

    private final ModerationContext ctx =
        new ModerationContext("t1", "PRO", UUID.randomUUID(), "sess-1", "actor-1", "user_input");

    @BeforeEach
    void setUp() {
        policy = mock(ModerationPolicyService.class);
        semantic = mock(PolicyPackSemanticClassifier.class);
        external = mock(ExternalSafetyClassifier.class);
        restrictedAudit = mock(ModerationRestrictedAuditService.class);
        reviewService = mock(ModerationReviewService.class);
        auditLog = mock(AuditLogService.class);
        service = new ModerationService(
            policy, new PolicyPackPatternMatcher(), semantic, external, restrictedAudit, reviewService, auditLog);
    }

    @Test
    void defaultOffAllowsAndWritesNothing() {
        when(policy.isModerationEnabled()).thenReturn(false);

        ModerationOutcome outcome = service.moderate("I want to die and here is sh*t", ctx, null);

        assertThat(outcome.isAllowed()).isTrue();
        verify(auditLog, never()).record(any(), any(), any(), any(), any(), any());
        verify(reviewService, never()).enqueue(any(), any(), any(), any(), ArgumentMatchers.anyInt(), any());
    }

    @Test
    void patternHitBlocksAndAudits() {
        when(policy.isModerationEnabled()).thenReturn(true);
        when(policy.enabledPacks(ctx)).thenReturn(List.of(ModerationPolicyPack.FOUL_LANGUAGE));
        when(policy.resolve(eq(ModerationPolicyPack.FOUL_LANGUAGE), any()))
            .thenReturn(new PolicyPackConfig(true, false, 50, 90));

        ModerationOutcome outcome = service.moderate("this is sh*t", ctx, null);

        assertThat(outcome.isBlocked()).isTrue();
        assertThat(outcome.decidingLayer()).isEqualTo(ModerationLayer.PATTERN);
        assertThat(outcome.pack()).isEqualTo(ModerationPolicyPack.FOUL_LANGUAGE);
        // Never a silent block.
        verify(auditLog).record(
            eq("actor-1"), eq(ModerationService.ACTION_PATTERN), eq(ModerationService.TARGET_TYPE),
            eq("sess-1"), eq(AuditOutcome.REJECTED), ArgumentMatchers.contains("decision=BLOCK"));
    }

    @Test
    void semanticMidBandQueuesForReview() {
        when(policy.isModerationEnabled()).thenReturn(true);
        when(policy.enabledPacks(ctx)).thenReturn(List.of(ModerationPolicyPack.HARASSMENT));
        when(policy.resolve(eq(ModerationPolicyPack.HARASSMENT), any()))
            .thenReturn(new PolicyPackConfig(true, true, 50, 90));
        // Clean of any harassment PATTERN rule, so only the semantic layer fires.
        when(semantic.score(any(), eq(ModerationPolicyPack.HARASSMENT), any())).thenReturn(65);

        UUID itemId = UUID.randomUUID();
        ModerationReviewItem item = mock(ModerationReviewItem.class);
        when(item.getId()).thenReturn(itemId);
        when(reviewService.enqueue(any(), any(), eq(ModerationPolicyPack.HARASSMENT),
            eq(ModerationLayer.SEMANTIC), eq(65), any())).thenReturn(item);

        ModerationOutcome outcome = service.moderate("you should really reconsider your choices", ctx, mock(org.springframework.ai.chat.client.ChatClient.class));

        assertThat(outcome.isQueuedForReview()).isTrue();
        assertThat(outcome.reviewItemId()).isEqualTo(itemId);
        assertThat(outcome.confidence()).isEqualTo(65);
        verify(reviewService).enqueue(any(), any(), eq(ModerationPolicyPack.HARASSMENT),
            eq(ModerationLayer.SEMANTIC), eq(65), any());
    }

    @Test
    void semanticBelowReviewBandAllows() {
        when(policy.isModerationEnabled()).thenReturn(true);
        when(policy.enabledPacks(ctx)).thenReturn(List.of(ModerationPolicyPack.HARASSMENT));
        when(policy.resolve(eq(ModerationPolicyPack.HARASSMENT), any()))
            .thenReturn(new PolicyPackConfig(true, true, 50, 90));
        when(semantic.score(any(), eq(ModerationPolicyPack.HARASSMENT), any())).thenReturn(10);

        ModerationOutcome outcome = service.moderate("perfectly nice message", ctx,
            mock(org.springframework.ai.chat.client.ChatClient.class));

        assertThat(outcome.isAllowed()).isTrue();
        verify(reviewService, never()).enqueue(any(), any(), any(), any(), ArgumentMatchers.anyInt(), any());
    }

    @Test
    void externalClassifierMatchRoutesToRestrictedPathOnlyAndBlocks() {
        when(policy.isModerationEnabled()).thenReturn(true);
        when(policy.enabledPacks(ctx)).thenReturn(List.of()); // no pattern/semantic packs
        when(policy.isExternalClassifierEnabled()).thenReturn(true);
        when(external.classify(any(), any()))
            .thenReturn(new ExternalSafetyVerdict(true, "csam", 99, "vendor-ref-xyz"));

        ModerationOutcome outcome = service.moderate("...content...", ctx, null);

        assertThat(outcome.isBlocked()).isTrue();
        assertThat(outcome.decidingLayer()).isEqualTo(ModerationLayer.EXTERNAL_CLASSIFIER);
        // Generic reason - NO category detail leaks into the general outcome.
        assertThat(outcome.reason()).doesNotContain("csam");
        // Restricted path recorded...
        verify(restrictedAudit).record(any(), any(), any());
        // ...and the GENERAL audit log got nothing about this match.
        verify(auditLog, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void externalClassifierFailureFailsSafeForContentFlow() {
        when(policy.isModerationEnabled()).thenReturn(true);
        when(policy.enabledPacks(ctx)).thenReturn(List.of());
        when(policy.isExternalClassifierEnabled()).thenReturn(true);
        when(external.classify(any(), any())).thenThrow(new RuntimeException("vendor outage"));

        ModerationOutcome outcome = service.moderate("ordinary content", ctx, null);

        assertThat(outcome.isAllowed()).isTrue();
        verify(restrictedAudit, never()).record(any(), any(), any());
    }
}
