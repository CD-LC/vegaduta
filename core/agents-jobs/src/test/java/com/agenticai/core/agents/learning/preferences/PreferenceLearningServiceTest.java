package com.agenticai.core.agents.learning.preferences;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.learning.LearningProposal;
import com.agenticai.core.agents.learning.LearningProposalKind;
import com.agenticai.core.agents.learning.LearningProposalService;
import com.agenticai.core.agents.learning.LearningProposalService.Outcome;
import com.agenticai.core.agents.learning.LearningProposalService.Proposal;
import com.agenticai.core.agents.learning.LearningProposalService.Result;
import com.agenticai.core.agents.learning.LearningProposalStatus;
import com.agenticai.core.agents.learning.LearningProposalView;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.approval.ApprovalHistoryService;
import com.agenticai.core.approval.ApprovalStatus;
import com.agenticai.core.rl.feedback.TurnFeedbackRecordedEvent;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantScope;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

class PreferenceLearningServiceTest {

    private static final String ME = "me";
    private static final UUID AGENT = UUID.randomUUID();

    private final List<PreferenceSignal> rows = new ArrayList<>();
    private final List<LearningProposalView> known = new ArrayList<>();
    private PreferenceSignalRepository signals;
    private LearningProposalService proposals;
    private ApprovalHistoryService approvalRepo;

    @BeforeEach
    void fakes() {
        signals = mock(PreferenceSignalRepository.class);
        when(signals.save(any(PreferenceSignal.class)))
                .thenAnswer(
                        inv -> {
                            rows.add(inv.getArgument(0));
                            return inv.getArgument(0);
                        });
        when(signals.countBySubjectAndSignalKeyAndCreatedAtAfter(anyString(), anyString(), any(Instant.class)))
                .thenAnswer(
                        inv ->
                                rows.stream()
                                        .filter(r -> r.getSubject().equals(inv.getArgument(0)))
                                        .filter(r -> r.getSignalKey().equals(inv.getArgument(1)))
                                        .filter(r -> r.getCreatedAt().isAfter(inv.getArgument(2)))
                                        .count());
        proposals = mock(LearningProposalService.class);
        when(proposals.claimSlot(anyString(), any())).thenReturn(true);
        when(proposals.runOffThread(any(Runnable.class)))
                .thenAnswer(
                        inv -> {
                            ((Runnable) inv.getArgument(0)).run();
                            return true;
                        });
        when(proposals.list(anyString(), any(), any())).thenAnswer(inv -> statusFilter(inv.getArgument(1)));
        when(proposals.propose(any(Proposal.class))).thenReturn(new Result(Outcome.CREATED, mock(LearningProposal.class)));
        approvalRepo = mock(ApprovalHistoryService.class);
    }

    private List<LearningProposalView> statusFilter(LearningProposalStatus status) {
        return known.stream().filter(v -> v.status().equals(status.name())).toList();
    }

    @SuppressWarnings("unchecked")
    private PreferenceLearningService service(int threshold) {
        ObjectProvider<ApprovalHistoryService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(approvalRepo);
        return new PreferenceLearningService(signals, proposals, provider, threshold, 30);
    }

    private static TenantScope scopeOf(String subject) {
        TenantContext ctx = mock(TenantContext.class);
        when(ctx.getSubject()).thenReturn(subject);
        return TenantScope.of(Optional.of(ctx));
    }

    private ArgumentCaptor<Proposal> filed(int times) {
        ArgumentCaptor<Proposal> c = ArgumentCaptor.forClass(Proposal.class);
        verify(proposals, org.mockito.Mockito.times(times)).propose(c.capture());
        return c;
    }

    private static LearningProposalView existing(String body, LearningProposalStatus status) {
        return new LearningProposalView(
                UUID.randomUUID(), null, "PREFERENCE", "t", body, "{}", "{}", status.name(), Instant.now(), null, true);
    }

    // ------------------------------------------------------------------ style requests

    @Test
    void theSameRequestBelowThresholdFilesNothing() {
        PreferenceLearningService s = service(3);
        s.recordAndEvaluate(ME, AGENT, "s1", "chat", PreferenceCatalog.detect("shorter please"));
        s.recordAndEvaluate(ME, AGENT, "s2", "chat", PreferenceCatalog.detect("make it shorter"));
        verify(proposals, never()).propose(any());
        assertEquals(2, rows.size());
    }

    @Test
    void thirdRepeatFilesOnePreferenceWithEvidenceAndNoQuote() {
        PreferenceLearningService s = service(3);
        for (int i = 0; i < 3; i++) {
            s.recordAndEvaluate(ME, AGENT, "s" + i, "chat", PreferenceCatalog.detect("shorter please"));
        }
        Proposal p = filed(1).getValue();
        assertEquals(ME, p.subject());
        assertEquals(LearningProposalKind.PREFERENCE, p.kind());
        assertEquals("Prefers short, concise replies", p.body());
        assertEquals("short-replies", p.evidence().get("signal"));
        assertEquals(3L, p.evidence().get("count"));
        assertFalse(p.evidence().containsKey("quote"));
        assertTrue(p.title().contains("3 times"));
        assertTrue(rows.stream().noneMatch(r -> r.getSignalKey().contains("please")));
    }

    @Test
    void differentSignalsAreCountedSeparately() {
        PreferenceLearningService s = service(2);
        s.recordAndEvaluate(ME, AGENT, null, "chat", PreferenceCatalog.detect("shorter"));
        s.recordAndEvaluate(ME, AGENT, null, "chat", PreferenceCatalog.detect("no emojis"));
        verify(proposals, never()).propose(any());
        s.recordAndEvaluate(ME, AGENT, null, "feedback", PreferenceCatalog.detect("no emojis please"));
        assertEquals("Does not want emojis in replies", filed(1).getValue().body());
    }

    @Test
    void anotherPersonsSignalsDoNotCount() {
        PreferenceLearningService s = service(2);
        s.recordAndEvaluate("alice", AGENT, null, "chat", PreferenceCatalog.detect("shorter"));
        s.recordAndEvaluate(ME, AGENT, null, "chat", PreferenceCatalog.detect("shorter"));
        verify(proposals, never()).propose(any());
    }

    @Test
    void aPreferenceAlreadyPendingOrApprovedIsNotFiledAgain() {
        known.add(existing("prefers  SHORT, concise replies", LearningProposalStatus.APPROVED));
        PreferenceLearningService s = service(2);
        s.recordAndEvaluate(ME, AGENT, null, "chat", PreferenceCatalog.detect("shorter"));
        s.recordAndEvaluate(ME, AGENT, null, "chat", PreferenceCatalog.detect("shorter"));
        s.recordAndEvaluate(ME, AGENT, null, "chat", PreferenceCatalog.detect("shorter"));
        verify(proposals, never()).propose(any());
    }

    @Test
    void aFailureNeverEscapesTheWorker() {
        when(signals.save(any(PreferenceSignal.class))).thenThrow(new IllegalStateException("db down"));
        service(2).recordAndEvaluate(ME, AGENT, null, "chat", PreferenceCatalog.detect("shorter"));
        verify(proposals, never()).propose(any());
    }

    // ------------------------------------------------------------------ hooks

    @Test
    void anOrdinaryMessageWithNoApprovalSlotQueuesNothing() {
        when(proposals.claimSlot(anyString(), any())).thenReturn(false);
        service(3).onUserMessage(AGENT, "s", "what is the weather in Pune", scopeOf(ME));
        verify(proposals, never()).runOffThread(any());
    }

    @Test
    void aMessageWithoutATenantIsIgnoredEvenWhenItLooksLikeASignal() {
        service(3).onUserMessage(AGENT, "s", "shorter", TenantScope.of(Optional.empty()));
        verify(proposals, never()).runOffThread(any());
        verify(signals, never()).save(any());
    }

    @Test
    void aStyleMessageIsRecordedOffTheRequestThread() {
        service(3).onUserMessage(AGENT, "s", "no emojis please", scopeOf(ME));
        verify(proposals).runOffThread(any(Runnable.class));
        assertEquals(1, rows.size());
        assertEquals("no-emojis", rows.get(0).getSignalKey());
        assertEquals("chat", rows.get(0).getSource());
    }

    @Test
    void aFeedbackCommentCountsAsASignalAndAPlainOneDoesNot() {
        PreferenceLearningService s = service(3);
        s.onFeedback(new TurnFeedbackRecordedEvent(this, ME, AGENT, "s", "way too long, be concise"));
        s.onFeedback(new TurnFeedbackRecordedEvent(this, ME, AGENT, "s", "great job"));
        s.onFeedback(new TurnFeedbackRecordedEvent(this, null, AGENT, "s", "shorter"));
        assertEquals(1, rows.size());
        assertEquals("feedback", rows.get(0).getSource());
    }

    // ------------------------------------------------------------------ approval patterns

    private static ApprovalRequest decision(String type, ApprovalStatus status, long humanSeconds) {
        ApprovalRequest r = mock(ApprovalRequest.class);
        Instant created = Instant.now().minus(Duration.ofDays(2));
        when(r.getActionType()).thenReturn(type);
        when(r.getStatus()).thenReturn(status);
        when(r.getCreatedAt()).thenReturn(created);
        when(r.getDecidedAt()).thenReturn(created.plusSeconds(humanSeconds));
        return r;
    }

    @Test
    void repeatedApprovalsOfOneTypeBecomeAnInformationalPattern() {
        List<ApprovalRequest> rowsIn = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            rowsIn.add(decision("mcp_tool.calendar_create", ApprovalStatus.APPROVED, 30));
        }
        var patterns = PreferenceLearningService.approvalPatterns(rowsIn, 3);
        assertEquals(1, patterns.size());
        assertEquals("mcp_tool.calendar_create", patterns.get(0).actionType());
        assertTrue(patterns.get(0).approves());
        assertEquals(4, patterns.get(0).count());
    }

    @Test
    void aMixedHistoryAutomaticApprovalsAndPendingRowsAreNotPatterns() {
        List<ApprovalRequest> rowsIn = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            rowsIn.add(decision("a", ApprovalStatus.APPROVED, 30));
        }
        rowsIn.add(decision("a", ApprovalStatus.REJECTED, 30)); // one rejection breaks "always approves"
        for (int i = 0; i < 5; i++) {
            rowsIn.add(decision("b", ApprovalStatus.APPROVED, 0)); // decided at creation: automatic
        }
        for (int i = 0; i < 5; i++) {
            rowsIn.add(decision("c", ApprovalStatus.PENDING, 30));
        }
        assertTrue(PreferenceLearningService.approvalPatterns(rowsIn, 3).isEmpty());
    }

    @Test
    void repeatedRejectionsAndFailedExecutionsAreCountedTheRightWay() {
        List<ApprovalRequest> rowsIn = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            rowsIn.add(decision("send_mail", ApprovalStatus.REJECTED, 20));
            rowsIn.add(decision("calendar", ApprovalStatus.EXECUTION_FAILED, 20)); // approved, then failed to run
        }
        var patterns = PreferenceLearningService.approvalPatterns(rowsIn, 3);
        assertEquals(2, patterns.size());
        assertTrue(patterns.stream().anyMatch(p -> p.actionType().equals("send_mail") && !p.approves()));
        assertTrue(patterns.stream().anyMatch(p -> p.actionType().equals("calendar") && p.approves()));
    }

    @Test
    void theScanFilesAnInformationalProposalThatChangesNoApprovalRule() {
        List<ApprovalRequest> decided = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            decided.add(decision("mcp_tool.calendar_create", ApprovalStatus.APPROVED, 45));
        }
        when(approvalRepo.decidedBy(eq(ME), any(Instant.class)))
                .thenReturn(decided);
        service(3).scanApprovals(ME, AGENT);
        Proposal p = filed(1).getValue();
        assertEquals(LearningProposalKind.PREFERENCE, p.kind());
        assertTrue(p.body().contains("informational"));
        assertTrue(p.body().contains("still asked for"));
        assertTrue(p.title().contains("autonomy"));
        assertEquals("approval-pattern:approved", p.evidence().get("signal"));
        assertEquals(3, p.evidence().get("count"));
    }

    @Test
    void theScanFilesAtMostThreeProposals() {
        List<ApprovalRequest> decided = new ArrayList<>();
        for (String type : List.of("a", "b", "c", "d", "e")) {
            for (int i = 0; i < 3; i++) {
                decided.add(decision(type, ApprovalStatus.APPROVED, 45));
            }
        }
        when(approvalRepo.decidedBy(eq(ME), any(Instant.class)))
                .thenReturn(decided);
        service(3).scanApprovals(ME, AGENT);
        filed(3);
    }
}
