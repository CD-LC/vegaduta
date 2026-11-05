package com.agenticai.core.agents.learning.episodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.job.approval.AgentJobApprovalLookup;
import com.agenticai.core.agents.learning.episodes.EpisodeSignalSource.HumanVerdict;
import com.agenticai.core.agents.learning.episodes.EpisodeSignalSource.Signals;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.rl.domain.FeedbackSignal;
import com.agenticai.core.rl.domain.TurnFeedback;
import com.agenticai.core.rl.feedback.FeedbackService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class VerifiedEpisodeSignalsTest {

    private static final UUID AGENT = UUID.randomUUID();
    private static final String LABEL = "agent:" + AGENT + ":session:s1";
    private static final Instant SINCE = Instant.now().minusSeconds(60);

    private final AgentJobApprovalLookup approvals = mock(AgentJobApprovalLookup.class);
    private final FeedbackService feedback = mock(FeedbackService.class);
    private final AtomicReference<AgentJobApprovalLookup> approvalRef =
            new AtomicReference<>(approvals);
    private final AtomicReference<FeedbackService> feedbackRef = new AtomicReference<>(feedback);
    private final VerifiedEpisodeSignals signals =
            new VerifiedEpisodeSignals(provider(approvalRef), provider(feedbackRef));

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(AtomicReference<T> ref) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenAnswer(inv -> ref.get());
        return p;
    }

    private static ApprovalRequest approved(String by) {
        ApprovalRequest r = new ApprovalRequest("tool", "s", "{}", LABEL);
        r.markApproved(by);
        return r;
    }

    private static ApprovalRequest rejected(String by) {
        ApprovalRequest r = new ApprovalRequest("tool", "s", "{}", LABEL);
        r.markRejected(by, "no");
        return r;
    }

    private static TurnFeedback fb(FeedbackSignal s, Short rating, String by) {
        return new TurnFeedback(AGENT, "s1", "t1", s, rating, null, null, by, Instant.now());
    }

    private void feedbackIs(TurnFeedback... rows) {
        when(feedback.recentForSession(any(), any(), any())).thenReturn(List.of(rows));
    }

    @Test
    void countsOnlyHumanDecidedApprovalsOfTheTurn() {
        ApprovalRequest pending = new ApprovalRequest("tool", "s", "{}", LABEL);
        ApprovalRequest expired = new ApprovalRequest("tool", "s", "{}", LABEL);
        expired.markExpired("nobody answered");
        ApprovalRequest failedAfterApproval = approved("alice");
        failedAfterApproval.markExecutionFailed("HTTP 500");
        when(approvals.findTop20ByRequestedByOrderByCreatedAtDesc(LABEL))
                .thenReturn(
                        List.of(
                                approved("alice"),
                                failedAfterApproval,
                                rejected("bob"),
                                pending,
                                expired,
                                approved("system:auto")));

        Signals s = signals.signalsFor(AGENT, "s1", SINCE);

        assertEquals(2, s.approved());
        assertEquals(1, s.rejected());
        assertNull(s.verdict());
    }

    @Test
    void requestsFromBeforeTheTurnAreIgnored() {
        when(approvals.findTop20ByRequestedByOrderByCreatedAtDesc(LABEL))
                .thenReturn(List.of(approved("alice")));
        assertEquals(Signals.NONE, signals.signalsFor(AGENT, "s1", Instant.now().plusSeconds(60)));
    }

    @Test
    void feedbackBecomesAVerdictOnlyWhenAPersonAgreesWithThemselves() {
        feedbackIs(fb(FeedbackSignal.THUMBS_UP, null, "alice"));
        assertEquals(HumanVerdict.ACCEPTED, signals.signalsFor(AGENT, "s1", SINCE).verdict());

        feedbackIs(fb(FeedbackSignal.RATING, (short) 5, "alice"));
        assertEquals(HumanVerdict.ACCEPTED, signals.signalsFor(AGENT, "s1", SINCE).verdict());

        feedbackIs(fb(FeedbackSignal.EDIT, null, "alice"));
        assertEquals(HumanVerdict.REJECTED, signals.signalsFor(AGENT, "s1", SINCE).verdict());

        feedbackIs(fb(FeedbackSignal.RATING, (short) 1, "alice"));
        assertEquals(HumanVerdict.REJECTED, signals.signalsFor(AGENT, "s1", SINCE).verdict());

        feedbackIs(fb(FeedbackSignal.RATING, (short) 3, "alice"));
        assertNull(signals.signalsFor(AGENT, "s1", SINCE).verdict());

        feedbackIs(
                fb(FeedbackSignal.THUMBS_UP, null, "alice"),
                fb(FeedbackSignal.THUMBS_DOWN, null, "alice"));
        assertNull(signals.signalsFor(AGENT, "s1", SINCE).verdict());

        feedbackIs(fb(FeedbackSignal.THUMBS_UP, null, null));
        assertNull(
                signals.signalsFor(AGENT, "s1", SINCE).verdict(),
                "feedback not attributable to a person");
    }

    @Test
    void anyFailureOrMissingStoreYieldsNoSignalsNeverAGuess() {
        when(approvals.findTop20ByRequestedByOrderByCreatedAtDesc(LABEL))
                .thenThrow(new IllegalStateException("db down"));
        when(feedback.recentForSession(any(), any(), any()))
                .thenThrow(new IllegalStateException("db down"));
        assertEquals(Signals.NONE, signals.signalsFor(AGENT, "s1", SINCE));

        approvalRef.set(null);
        feedbackRef.set(null);
        assertEquals(Signals.NONE, signals.signalsFor(AGENT, "s1", SINCE));
        assertEquals(Signals.NONE, signals.signalsFor(null, "s1", SINCE));
        assertEquals(Signals.NONE, signals.signalsFor(AGENT, " ", SINCE));
        assertEquals(Signals.NONE, signals.signalsFor(AGENT, "s1", null));
    }
}
