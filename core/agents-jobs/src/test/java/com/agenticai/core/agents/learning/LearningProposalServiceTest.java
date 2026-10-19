package com.agenticai.core.agents.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.learning.LearningProposalService.Outcome;
import com.agenticai.core.agents.learning.LearningProposalService.Proposal;
import com.agenticai.core.agents.skills.learning.SkillDraftScrubber;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class LearningProposalServiceTest {

    private static final String ME = "me";

    private final List<LearningProposal> rows = new ArrayList<>();
    private final List<String> applied = new ArrayList<>();
    private LearningProposalRepository repo;

    @SuppressWarnings("unchecked")
    private LearningProposalService service(int maxPending, LearningProposalApplier... appliers) {
        ObjectProvider<SkillDraftScrubber> none = mock(ObjectProvider.class);
        return new LearningProposalService(repo, List.of(appliers), none, maxPending, 30, 0, 100);
    }

    private LearningProposalApplier lessonApplier() {
        return new LearningProposalApplier() {
            @Override
            public LearningProposalKind kind() {
                return LearningProposalKind.LESSON;
            }

            @Override
            public void apply(LearningProposal p, String text) {
                applied.add(text);
            }
        };
    }

    @BeforeEach
    void fakeRepository() {
        repo = mock(LearningProposalRepository.class);
        when(repo.save(any(LearningProposal.class)))
                .thenAnswer(
                        inv -> {
                            LearningProposal p = inv.getArgument(0);
                            if (!rows.contains(p)) {
                                rows.add(p);
                            }
                            return p;
                        });
        when(repo.findByIdAndSubject(any(), any()))
                .thenAnswer(
                        inv ->
                                rows.stream()
                                        .filter(r -> r.getId().equals(inv.getArgument(0)))
                                        .filter(r -> r.getSubject().equals(inv.getArgument(1)))
                                        .findFirst());
        when(repo.existsBySubjectAndKindAndBodyHashAndStatusIn(any(), any(), any(), any()))
                .thenAnswer(
                        inv -> {
                            java.util.Collection<LearningProposalStatus> st = inv.getArgument(3);
                            return rows.stream()
                                    .anyMatch(
                                            r ->
                                                    r.getSubject().equals(inv.getArgument(0))
                                                            && r.getKind() == inv.getArgument(1)
                                                            && r.getBodyHash().equals(inv.getArgument(2))
                                                            && st.contains(r.getStatus()));
                        });
        when(repo.countBySubjectAndStatus(any(), any()))
                .thenAnswer(
                        inv ->
                                rows.stream()
                                        .filter(r -> r.getSubject().equals(inv.getArgument(0)))
                                        .filter(r -> r.getStatus() == inv.getArgument(1))
                                        .count());
    }

    private static Proposal lesson(String body) {
        return new Proposal(
                ME, UUID.randomUUID(), LearningProposalKind.LESSON, null, body, Map.of(), Map.of("signal", "x"));
    }

    @Test
    void proposeStoresPendingAndDedupesIdenticalText() {
        LearningProposalService s = service(10, lessonApplier());
        var first = s.propose(lesson("Always answer in short bullet points."));
        assertEquals(Outcome.CREATED, first.outcome());
        assertEquals(LearningProposalStatus.PENDING, first.proposal().getStatus());
        // same words, different case and spacing
        var again = s.propose(lesson("always  answer in SHORT bullet points. "));
        assertEquals(Outcome.DUPLICATE, again.outcome());
        assertEquals(1, rows.size());
    }

    @Test
    void pendingCapStopsFurtherProposals() {
        LearningProposalService s = service(2, lessonApplier());
        assertTrue(s.propose(lesson("one")).created());
        assertTrue(s.propose(lesson("two")).created());
        assertEquals(Outcome.PENDING_CAP_REACHED, s.propose(lesson("three")).outcome());
        assertEquals(2, rows.size());
    }

    @Test
    void secretsAreScrubbedAndHeavilySensitiveTextIsDropped() {
        LearningProposalService s = service(10, lessonApplier());
        var r = s.propose(lesson("Use my token=abcd1234efgh for the deploy"));
        assertTrue(r.created());
        assertFalse(r.proposal().getBody().contains("abcd1234efgh"));
        var heavy =
                s.propose(
                        lesson(
                                "write to a@b.com c@d.com e@f.com g@h.com and i@j.com"));
        assertEquals(Outcome.TOO_SENSITIVE, heavy.outcome());
    }

    @Test
    void approveDispatchesToTheKindsApplierWithTheEditedText() {
        LearningProposalService s = service(10, lessonApplier());
        LearningProposal p = s.propose(lesson("Prefer metric units.")).proposal();
        LearningProposalView done = s.approve(ME, p.getId(), "Prefer metric units, always.");
        assertEquals("APPROVED", done.status());
        assertEquals(List.of("Prefer metric units, always."), applied);
        assertEquals("Prefer metric units, always.", done.body());
        // deciding twice is refused
        assertEquals(
                HttpStatus.CONFLICT,
                assertThrows(ResponseStatusException.class, () -> s.approve(ME, p.getId()))
                        .getStatusCode());
    }

    @Test
    void approvingAKindWithNoApplierIs409AndLeavesItPending() {
        LearningProposalService s = service(10, lessonApplier());
        LearningProposal p =
                s.propose(
                                new Proposal(
                                        ME, null, LearningProposalKind.ROUTINE, "Daily digest", "Every morning at 8", null, null))
                        .proposal();
        ResponseStatusException e =
                assertThrows(ResponseStatusException.class, () -> s.approve(ME, p.getId()));
        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
        assertEquals(LearningProposalStatus.PENDING, p.getStatus());
        assertFalse(s.canApply(LearningProposalKind.ROUTINE));
    }

    @Test
    void anApplierThatRefusesLeavesTheProposalPendingWith409() {
        LearningProposalApplier refusing =
                new LearningProposalApplier() {
                    @Override
                    public LearningProposalKind kind() {
                        return LearningProposalKind.LESSON;
                    }

                    @Override
                    public void apply(LearningProposal p, String text) throws LearningProposalApplyException {
                        throw new LearningProposalApplyException("full");
                    }
                };
        LearningProposalService s = service(10, refusing);
        LearningProposal p = s.propose(lesson("x lesson")).proposal();
        ResponseStatusException e =
                assertThrows(ResponseStatusException.class, () -> s.approve(ME, p.getId()));
        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
        assertEquals("full", e.getReason());
        assertEquals(LearningProposalStatus.PENDING, p.getStatus());
    }

    @Test
    void rejectedTextIsNotProposedAgainAndOthersCannotDecideMine() {
        LearningProposalService s = service(10, lessonApplier());
        LearningProposal p = s.propose(lesson("Be terse.")).proposal();
        assertEquals(
                HttpStatus.NOT_FOUND,
                assertThrows(ResponseStatusException.class, () -> s.reject("someone-else", p.getId()))
                        .getStatusCode());
        s.reject(ME, p.getId());
        assertEquals(LearningProposalStatus.REJECTED, p.getStatus());
        assertEquals(Outcome.PREVIOUSLY_REJECTED, s.propose(lesson("Be terse.")).outcome());
    }

    @Test
    void invalidInputNeverThrows() {
        LearningProposalService s = service(10, lessonApplier());
        assertEquals(Outcome.INVALID, s.propose(null).outcome());
        assertEquals(Outcome.INVALID, s.propose(lesson("  ")).outcome());
        assertNull(s.propose(lesson("  ")).proposal());
    }

    @Test
    void offThreadWorkRunsOnTheWorkerNotTheCaller() throws Exception {
        LearningProposalService s = service(10, lessonApplier());
        AtomicReference<Thread> ran = new AtomicReference<>();
        var latch = new java.util.concurrent.CountDownLatch(1);
        assertTrue(
                s.runOffThread(
                        () -> {
                            ran.set(Thread.currentThread());
                            latch.countDown();
                        }));
        assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS));
        assertFalse(ran.get() == Thread.currentThread());
        s.shutdown();
    }
}
