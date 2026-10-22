package com.agenticai.core.tutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.tutor.StudyPlanDocs.LessonContent;
import com.agenticai.core.tutor.StudyPlanDocs.QuizItem;
import com.agenticai.core.tutor.StudyPlanService.ReviewReminder;
import com.agenticai.core.tutor.dto.TutorDtos.CreatePlanRequest;
import com.agenticai.core.tutor.dto.TutorDtos.DueReviews;
import com.agenticai.core.tutor.dto.TutorDtos.LessonContentInput;
import com.agenticai.core.tutor.dto.TutorDtos.LessonInput;
import com.agenticai.core.tutor.dto.TutorDtos.LessonView;
import com.agenticai.core.tutor.dto.TutorDtos.ModuleInput;
import com.agenticai.core.tutor.dto.TutorDtos.PlanView;
import com.agenticai.core.tutor.dto.TutorDtos.QuizAnswerResult;
import com.agenticai.core.tutor.dto.TutorDtos.QuizInput;
import com.agenticai.core.tutor.dto.TutorDtos.QuizQuestionView;
import com.agenticai.core.tutor.dto.TutorDtos.ReviewAnswerResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Owner isolation, caps, deterministic grading and the "no answer before an attempt" rule, with an
 * in-memory repository.
 */
class StudyPlanServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-03T10:00:00Z");

    /** A clock the test can move. */
    private static final class MovableClock extends Clock {
        private Instant now = T0;

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final Map<UUID, StudyPlan> rows = new LinkedHashMap<>();
    private final StudyPlanRepository repository = mock(StudyPlanRepository.class);
    private final MovableClock clock = new MovableClock();
    private StudyPlanService service;

    @BeforeEach
    void setUp() {
        when(repository.saveAndFlush(any(StudyPlan.class)))
                .thenAnswer(
                        inv -> {
                            StudyPlan plan = inv.getArgument(0);
                            rows.put(plan.getId(), plan);
                            return plan;
                        });
        when(repository.save(any(StudyPlan.class)))
                .thenAnswer(
                        inv -> {
                            StudyPlan plan = inv.getArgument(0);
                            rows.put(plan.getId(), plan);
                            return plan;
                        });
        when(repository.findByIdAndOwnerSubject(any(UUID.class), anyString()))
                .thenAnswer(
                        inv -> {
                            StudyPlan plan = rows.get(inv.<UUID>getArgument(0));
                            return plan != null
                                            && plan.getOwnerSubject()
                                                    .equals(inv.<String>getArgument(1))
                                    ? Optional.of(plan)
                                    : Optional.empty();
                        });
        when(repository.findByOwnerSubjectOrderByUpdatedAtDesc(anyString()))
                .thenAnswer(
                        inv -> {
                            List<StudyPlan> out = new ArrayList<>();
                            for (StudyPlan plan : rows.values()) {
                                if (plan.getOwnerSubject().equals(inv.<String>getArgument(0))) {
                                    out.add(plan);
                                }
                            }
                            return out;
                        });
        when(repository.countByOwnerSubject(anyString()))
                .thenAnswer(
                        inv ->
                                rows.values().stream()
                                        .filter(
                                                p ->
                                                        p.getOwnerSubject()
                                                                .equals(inv.<String>getArgument(0)))
                                        .count());
        // The review-only projections: text columns, decks with at least one card, keyset-paged by
        // id.
        when(repository.findReviewDecksAfter(anyString(), anyInt()))
                .thenAnswer(
                        inv -> {
                            String after = inv.getArgument(0);
                            int pageSize = inv.getArgument(1);
                            List<Object[]> out = new ArrayList<>();
                            rows.values().stream()
                                    .filter(StudyPlanServiceTest::hasCards)
                                    .filter(plan -> plan.getId().toString().compareTo(after) > 0)
                                    .sorted(
                                            Comparator.comparing(
                                                    (StudyPlan plan) -> plan.getId().toString()))
                                    .limit(pageSize)
                                    .forEach(
                                            plan ->
                                                    out.add(
                                                            new Object[] {
                                                                plan.getId().toString(),
                                                                plan.getOwnerSubject(),
                                                                plan.getTopic(),
                                                                plan.getReviewJson()
                                                            }));
                            return out;
                        });
        when(repository.findReviewDecksOfOwner(anyString()))
                .thenAnswer(
                        inv -> {
                            List<Object[]> out = new ArrayList<>();
                            for (StudyPlan plan : rows.values()) {
                                if (plan.getOwnerSubject().equals(inv.<String>getArgument(0))
                                        && hasCards(plan)) {
                                    out.add(
                                            new Object[] {
                                                plan.getId().toString(), plan.getReviewJson()
                                            });
                                }
                            }
                            return out;
                        });
        when(repository.stampReminded(anyString(), anyString()))
                .thenAnswer(
                        inv -> {
                            StudyPlan plan = rows.get(UUID.fromString(inv.<String>getArgument(0)));
                            if (plan == null) {
                                return 0;
                            }
                            plan.updateReviewQuietly(
                                    StudyPlanDocs.write(
                                            new StudyPlanDocs.ReviewDeck(
                                                    StudyPlanDocs.readReview(plan.getReviewJson())
                                                            .cards(),
                                                    inv.<String>getArgument(1))));
                            return 1;
                        });
        service = new StudyPlanService(repository, 2, 60, 400_000, clock);
    }

    private static boolean hasCards(StudyPlan plan) {
        return !StudyPlanDocs.readReview(plan.getReviewJson()).cards().isEmpty();
    }

    private static QuizInput question(String text, int correctIndex) {
        return new QuizInput(text, List.of("A", "B", "C", "D"), correctIndex, "Because of " + text);
    }

    private static CreatePlanRequest request(String topic) {
        LessonInput withQuiz =
                new LessonInput(
                        "Light and leaves",
                        "Know what a leaf needs",
                        new LessonContentInput(
                                "Plants make food from light.",
                                List.of("Light", "Water"),
                                "A leaf in the sun."),
                        List.of(
                                question("What do plants need?", 2),
                                question("Where does it happen?", 1)));
        LessonInput outlineOnly =
                new LessonInput("The dark reactions", "Explain the Calvin cycle", null, null);
        return new CreatePlanRequest(
                topic,
                "hi",
                "Beginner",
                "Pass my exam",
                List.of(new ModuleInput("Basics", List.of(withQuiz, outlineOnly))));
    }

    private static int status(Runnable call) {
        return assertThrows(ResponseStatusException.class, call::run).getStatusCode().value();
    }

    @Test
    void createAssignsIdsAndNormalisesLanguageAndLevel() {
        PlanView plan = service.create("alice", request("Photosynthesis"));

        assertEquals("Photosynthesis", plan.topic());
        assertEquals("hi", plan.language());
        assertEquals("beginner", plan.level());
        assertEquals(StudyPlan.STATUS_ACTIVE, plan.status());
        assertEquals(2, plan.totalLessons());
        assertEquals(0, plan.completedLessons());
        assertEquals("m1", plan.modules().get(0).id());
        LessonView first = plan.modules().get(0).lessons().get(0);
        assertEquals("m1-l1", first.id());
        assertTrue(first.generated());
        assertEquals("q1", first.quiz().get(0).id());
        LessonView second = plan.modules().get(0).lessons().get(1);
        assertFalse(second.generated());
        assertNull(second.content());
        assertNull(second.quiz());
    }

    @Test
    void theAnswerKeyIsNeverSentBeforeAnAttempt() throws Exception {
        PlanView plan = service.create("alice", request("Photosynthesis"));

        for (QuizQuestionView q : plan.modules().get(0).lessons().get(0).quiz()) {
            assertFalse(q.answered());
            assertNull(q.correctIndex());
            assertNull(q.correct());
            assertNull(q.explanation());
            assertNull(q.selectedIndex());
        }
        String json =
                com.agenticai.core.json.JsonMappers.withJavaTime()
                        .writeValueAsString(service.get("alice", plan.id()));
        assertFalse(json.contains("correctIndex"), json);
        assertFalse(json.contains("Because of"), json);
    }

    @Test
    void aWrongAnswerIsGradedRevealedAndAddedToReview() {
        PlanView plan = service.create("alice", request("Photosynthesis"));

        QuizAnswerResult result = service.submitQuizAnswer("alice", plan.id(), "m1-l1", "q1", 0);

        assertFalse(result.correct());
        assertEquals(2, result.correctIndex());
        assertEquals(0, result.selectedIndex());
        assertEquals("Because of What do plants need?", result.explanation());
        assertTrue(result.addedToReview());
        assertEquals(StudyPlanDocs.LESSON_IN_PROGRESS, result.lessonStatus());

        List<QuizQuestionView> quiz = service.lesson("alice", plan.id(), "m1-l1").quiz();
        assertTrue(quiz.get(0).answered());
        assertEquals(2, quiz.get(0).correctIndex());
        assertEquals(Boolean.FALSE, quiz.get(0).correct());
        // The question the learner has not attempted stays sealed.
        assertFalse(quiz.get(1).answered());
        assertNull(quiz.get(1).correctIndex());

        // Not due yet: a lapse comes back tomorrow.
        assertEquals(0, service.dueReviews("alice").totalDue());
        clock.now = T0.plus(Duration.ofDays(1));
        DueReviews due = service.dueReviews("alice");
        assertEquals(1, due.totalDue());
        assertEquals("m1-l1:q1", due.cards().get(0).cardId());
        assertEquals("Photosynthesis", due.cards().get(0).planTopic());
    }

    @Test
    void aCorrectAnswerAddsNoReviewCard() {
        PlanView plan = service.create("alice", request("Photosynthesis"));

        QuizAnswerResult result = service.submitQuizAnswer("alice", plan.id(), "m1-l1", "q1", 2);

        assertTrue(result.correct());
        assertFalse(result.addedToReview());
        clock.now = T0.plus(Duration.ofDays(30));
        assertEquals(0, service.dueReviews("alice").totalDue());
    }

    @Test
    void repeatingAMistakeLapsesTheSameCardInsteadOfAddingAnother() {
        PlanView plan = service.create("alice", request("Photosynthesis"));
        service.submitQuizAnswer("alice", plan.id(), "m1-l1", "q1", 0);
        service.submitQuizAnswer("alice", plan.id(), "m1-l1", "q1", 3);

        clock.now = T0.plus(Duration.ofDays(2));
        DueReviews due = service.dueReviews("alice");

        assertEquals(1, due.totalDue());
        assertEquals(2, due.cards().get(0).lapses());
    }

    @Test
    void gradingRefusesAnOutOfRangeOrMissingSelection() {
        PlanView plan = service.create("alice", request("Photosynthesis"));

        assertThrows(
                IllegalArgumentException.class,
                () -> service.submitQuizAnswer("alice", plan.id(), "m1-l1", "q1", 9));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.submitQuizAnswer("alice", plan.id(), "m1-l1", "q1", null));
        assertEquals(
                404, status(() -> service.submitQuizAnswer("alice", plan.id(), "m1-l1", "q99", 0)));
        assertEquals(
                404, status(() -> service.submitQuizAnswer("alice", plan.id(), "m1-l2", "q1", 0)));
        assertEquals(
                404, status(() -> service.submitQuizAnswer("alice", plan.id(), "nope", "q1", 0)));
    }

    @Test
    void anotherMembersPlanLooksMissing() {
        PlanView plan = service.create("alice", request("Photosynthesis"));
        service.submitQuizAnswer("alice", plan.id(), "m1-l1", "q1", 0);
        clock.now = T0.plus(Duration.ofDays(1));

        assertEquals(404, status(() -> service.get("mallory", plan.id())));
        assertEquals(404, status(() -> service.delete("mallory", plan.id())));
        assertEquals(
                404,
                status(() -> service.submitQuizAnswer("mallory", plan.id(), "m1-l1", "q1", 2)));
        assertEquals(404, status(() -> service.completeLesson("mallory", plan.id(), "m1-l1")));
        assertEquals(404, status(() -> service.answerReview("mallory", plan.id(), "m1-l1:q1", 2)));
        assertEquals(404, status(() -> service.requireLesson("mallory", plan.id(), "m1-l1")));
        assertTrue(service.list("mallory").isEmpty());
        assertEquals(0, service.dueReviews("mallory").totalDue());
        verify(repository, never()).delete(any(StudyPlan.class));
        assertEquals(1, service.list("alice").size());
    }

    @Test
    void thePlanCapIsPerMemberAndAnswers409() {
        service.create("alice", request("One"));
        service.create("alice", request("Two"));

        assertEquals(
                HttpStatus.CONFLICT.value(),
                status(() -> service.create("alice", request("Three"))));
        assertEquals(HttpStatus.CONFLICT.value(), status(() -> service.assertCanCreate("alice")));
        // A different member is not affected by alice's count.
        assertEquals("One", service.create("bob", request("One")).topic());
    }

    @Test
    void lessonAndSizeCapsAreEnforced() {
        StudyPlanService small = new StudyPlanService(repository, 20, 1, 400_000, clock);
        assertThrows(
                IllegalArgumentException.class,
                () -> small.create("alice", request("Photosynthesis")));

        StudyPlanService tiny = new StudyPlanService(repository, 20, 60, 10_000, clock);
        LessonInput huge =
                new LessonInput(
                        "Big",
                        "o",
                        new LessonContentInput("x".repeat(15_000), List.of(), null),
                        null);
        CreatePlanRequest big =
                new CreatePlanRequest(
                        "Big topic",
                        null,
                        null,
                        null,
                        List.of(new ModuleInput("M", List.of(huge))));
        assertThrows(IllegalArgumentException.class, () -> tiny.create("alice", big));
        assertTrue(rows.isEmpty());
    }

    @Test
    void invalidInputIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> service.create("alice", null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service.create(
                                "alice",
                                new CreatePlanRequest(
                                        " ", null, null, null, request("x y").modules())));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service.create(
                                "alice",
                                new CreatePlanRequest(
                                        "Topic",
                                        "not a language!",
                                        null,
                                        null,
                                        request("x y").modules())));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service.create(
                                "alice",
                                new CreatePlanRequest(
                                        "Topic", null, "wizard", null, request("x y").modules())));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service.create(
                                "alice",
                                new CreatePlanRequest("Topic", null, null, null, List.of())));
        LessonInput badKey =
                new LessonInput(
                        "L",
                        "o",
                        new LessonContentInput("text", null, null),
                        List.of(new QuizInput("Q?", List.of("A", "B"), 2, "e")));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service.create(
                                "alice",
                                new CreatePlanRequest(
                                        "Topic",
                                        null,
                                        null,
                                        null,
                                        List.of(new ModuleInput("M", List.of(badKey))))));
    }

    @Test
    void completingEveryLessonCompletesThePlan() {
        PlanView plan = service.create("alice", request("Photosynthesis"));

        PlanView afterFirst = service.completeLesson("alice", plan.id(), "m1-l1");
        assertEquals(1, afterFirst.completedLessons());
        assertEquals(StudyPlan.STATUS_ACTIVE, afterFirst.status());

        PlanView afterSecond = service.completeLesson("alice", plan.id(), "m1-l2");
        assertEquals(2, afterSecond.completedLessons());
        assertEquals(StudyPlan.STATUS_COMPLETED, afterSecond.status());
    }

    @Test
    void reviewAnswersAreGradedAndRescheduledDeterministically() {
        PlanView plan = service.create("alice", request("Photosynthesis"));
        service.submitQuizAnswer("alice", plan.id(), "m1-l1", "q1", 0);
        service.submitQuizAnswer("alice", plan.id(), "m1-l1", "q2", 0);
        clock.now = T0.plus(Duration.ofDays(1));
        assertEquals(2, service.dueReviews("alice").totalDue());

        ReviewAnswerResult right = service.answerReview("alice", plan.id(), "m1-l1:q1", 2);

        assertTrue(right.correct());
        assertEquals(2, right.correctIndex());
        assertEquals(1, right.intervalDays());
        assertEquals(clock.now.plus(Duration.ofDays(1)).toString(), right.nextDueAt());
        assertEquals(1, right.remainingDue());

        ReviewAnswerResult wrong = service.answerReview("alice", plan.id(), "m1-l1:q2", 3);
        assertFalse(wrong.correct());
        assertEquals(1, wrong.correctIndex());
        assertEquals(0, wrong.remainingDue());

        assertEquals(404, status(() -> service.answerReview("alice", plan.id(), "m1-l1:q9", 0)));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.answerReview("alice", plan.id(), "m1-l1:q1", null));
    }

    @Test
    void generatedContentIsCachedOnceAndNeverOverwritten() {
        PlanView plan = service.create("alice", request("Photosynthesis"));
        List<QuizItem> quiz = List.of(new QuizItem("q1", "Q?", List.of("A", "B"), 1, "why", null));

        LessonView stored =
                service.storeLessonContent(
                        "alice",
                        plan.id(),
                        "m1-l2",
                        new LessonContent("First text", List.of("k"), null),
                        quiz);
        LessonView again =
                service.storeLessonContent(
                        "alice",
                        plan.id(),
                        "m1-l2",
                        new LessonContent("Second text", List.of(), null),
                        quiz);

        assertTrue(stored.generated());
        assertEquals("First text", stored.content().explanation());
        assertEquals("First text", again.content().explanation());
        assertNull(stored.quiz().get(0).correctIndex());
        assertEquals(
                1, service.requireLesson("alice", plan.id(), "m1-l2").quiz().get(0).correctIndex());
    }

    @Test
    void aConcurrentEditSurfacesAs409() {
        PlanView plan = service.create("alice", request("Photosynthesis"));
        doThrow(new OptimisticLockingFailureException("stale"))
                .when(repository)
                .saveAndFlush(any(StudyPlan.class));

        assertEquals(
                HttpStatus.CONFLICT.value(),
                status(() -> service.completeLesson("alice", plan.id(), "m1-l1")));
    }

    @Test
    void remindersAreClaimedOncePerMemberPerGap() {
        PlanView alice = service.create("alice", request("Photosynthesis"));
        service.submitQuizAnswer("alice", alice.id(), "m1-l1", "q1", 0);
        service.submitQuizAnswer("alice", alice.id(), "m1-l1", "q2", 0);
        PlanView bob = service.create("bob", request("Algebra"));
        service.submitQuizAnswer("bob", bob.id(), "m1-l1", "q1", 2);

        assertTrue(
                service.claimDueReminders(Duration.ofHours(20)).isEmpty(),
                "nothing is due on day 0");

        clock.now = T0.plus(Duration.ofDays(1));
        List<ReviewReminder> first = service.claimDueReminders(Duration.ofHours(20));
        assertEquals(1, first.size());
        assertEquals("alice", first.get(0).ownerSubject());
        assertEquals(2, first.get(0).dueCount());
        assertEquals("Photosynthesis", first.get(0).topic());
        assertEquals(1, first.get(0).planCount());

        clock.now = clock.now.plus(Duration.ofHours(1));
        assertTrue(
                service.claimDueReminders(Duration.ofHours(20)).isEmpty(),
                "already reminded within the gap");

        clock.now = clock.now.plus(Duration.ofHours(20));
        assertEquals(1, service.claimDueReminders(Duration.ofHours(20)).size());
    }

    @Test
    void aCompletedPlanWithADueCardIsStillReminded() {
        PlanView plan = service.create("alice", request("Photosynthesis"));
        service.submitQuizAnswer("alice", plan.id(), "m1-l1", "q1", 0);
        service.completeLesson("alice", plan.id(), "m1-l1");
        assertEquals(
                StudyPlan.STATUS_COMPLETED,
                service.completeLesson("alice", plan.id(), "m1-l2").status());

        clock.now = T0.plus(Duration.ofDays(1));
        Instant updatedBefore = rows.get(plan.id()).getUpdatedAt();
        List<ReviewReminder> reminders = service.claimDueReminders(Duration.ofHours(20));

        assertEquals(1, reminders.size());
        assertEquals("alice", reminders.get(0).ownerSubject());
        assertEquals(1, reminders.get(0).dueCount());
        verify(repository).stampReminded(plan.id().toString(), clock.now.toString());
        // The stamp is bookkeeping: it must not reorder the course list.
        assertEquals(updatedBefore, rows.get(plan.id()).getUpdatedAt());
        // A stamp on the finished plan keeps the member quiet for the gap.
        clock.now = clock.now.plus(Duration.ofHours(2));
        assertTrue(service.claimDueReminders(Duration.ofHours(20)).isEmpty());
    }

    @Test
    void theSweepReadsDecksInPagesAndNeverLoadsWholePlans() {
        StudyPlanService roomy = new StudyPlanService(repository, 500, 60, 400_000, clock);
        int members = StudyPlanService.REMINDER_PAGE_SIZE + 7;
        for (int i = 0; i < members; i++) {
            PlanView plan = roomy.create("member-" + i, request("Topic " + i));
            roomy.submitQuizAnswer("member-" + i, plan.id(), "m1-l1", "q1", 0);
        }
        // A plan with an empty deck is not part of the sweep at all.
        roomy.create("no-cards", request("Untouched"));
        clearInvocations(repository);

        clock.now = T0.plus(Duration.ofDays(1));
        List<ReviewReminder> reminders = roomy.claimDueReminders(Duration.ofHours(20));

        assertEquals(members, reminders.size());
        verify(repository, times(2)).findReviewDecksAfter(anyString(), anyInt());
        verify(repository, times(members)).stampReminded(anyString(), anyString());
        verify(repository, never()).findAll();
        verify(repository, never()).findByOwnerSubjectOrderByUpdatedAtDesc(anyString());
        verify(repository, never()).save(any(StudyPlan.class));
        verify(repository, never()).saveAndFlush(any(StudyPlan.class));
    }

    @Test
    void aMembersDueCardsAreSummedAcrossTheirPlans() {
        PlanView one = service.create("alice", request("Photosynthesis"));
        PlanView two = service.create("alice", request("Algebra"));
        service.submitQuizAnswer("alice", one.id(), "m1-l1", "q1", 0);
        service.submitQuizAnswer("alice", two.id(), "m1-l1", "q1", 0);
        service.submitQuizAnswer("alice", two.id(), "m1-l1", "q2", 0);

        clock.now = T0.plus(Duration.ofDays(1));
        List<ReviewReminder> reminders = service.claimDueReminders(Duration.ofHours(20));

        assertEquals(1, reminders.size());
        assertEquals(3, reminders.get(0).dueCount());
        assertEquals(2, reminders.get(0).planCount());
        assertEquals("Algebra", reminders.get(0).topic());

        // Answering one card reports what is still due across BOTH plans.
        assertEquals(2, service.answerReview("alice", one.id(), "m1-l1:q1", 2).remainingDue());
    }

    @Test
    void theFirstQuizAttemptIsTheRecordedOne() {
        PlanView plan = service.create("alice", request("Photosynthesis"));
        assertFalse(service.submitQuizAnswer("alice", plan.id(), "m1-l1", "q1", 0).correct());

        // The key is known now; resubmitting the right option is graded but does not rewrite the
        // record.
        QuizAnswerResult retry = service.submitQuizAnswer("alice", plan.id(), "m1-l1", "q1", 2);

        assertTrue(retry.correct());
        QuizQuestionView shown = service.lesson("alice", plan.id(), "m1-l1").quiz().get(0);
        assertEquals(0, shown.selectedIndex());
        assertEquals(Boolean.FALSE, shown.correct());
        // The review card from the first, wrong attempt is still there.
        clock.now = T0.plus(Duration.ofDays(1));
        assertEquals(1, service.dueReviews("alice").totalDue());
    }

    @Test
    void aCardThatIsNotDueCannotBeAnsweredAgain() {
        PlanView plan = service.create("alice", request("Photosynthesis"));
        service.submitQuizAnswer("alice", plan.id(), "m1-l1", "q1", 0);

        // Not due yet on day 0.
        assertEquals(
                HttpStatus.CONFLICT.value(),
                status(() -> service.answerReview("alice", plan.id(), "m1-l1:q1", 2)));

        clock.now = T0.plus(Duration.ofDays(1));
        ReviewAnswerResult first = service.answerReview("alice", plan.id(), "m1-l1:q1", 2);
        assertEquals(1, first.intervalDays());
        // Straight away again: refused, and the interval has not been stretched.
        assertEquals(
                HttpStatus.CONFLICT.value(),
                status(() -> service.answerReview("alice", plan.id(), "m1-l1:q1", 2)));
        clock.now = clock.now.plus(Duration.ofDays(1));
        assertEquals(1, service.dueReviews("alice").totalDue());
    }

    @Test
    void accountDeletionRemovesOnlyThatMembersPlans() {
        when(repository.deleteByOwnerSubject("alice")).thenReturn(2L);

        assertEquals(2L, service.deleteAllOfUser("alice"));
        assertEquals(0L, service.deleteAllOfUser(" "));
        verify(repository).deleteByOwnerSubject("alice");
    }
}
