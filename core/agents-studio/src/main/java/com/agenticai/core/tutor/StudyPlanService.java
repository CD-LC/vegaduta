package com.agenticai.core.tutor;

import com.agenticai.core.tutor.StudyPlanDocs.Card;
import com.agenticai.core.tutor.StudyPlanDocs.Curriculum;
import com.agenticai.core.tutor.StudyPlanDocs.Lesson;
import com.agenticai.core.tutor.StudyPlanDocs.LessonContent;
import com.agenticai.core.tutor.StudyPlanDocs.Module;
import com.agenticai.core.tutor.StudyPlanDocs.QuizItem;
import com.agenticai.core.tutor.StudyPlanDocs.ReviewDeck;
import com.agenticai.core.tutor.dto.TutorDtos.CreatePlanRequest;
import com.agenticai.core.tutor.dto.TutorDtos.DueCardView;
import com.agenticai.core.tutor.dto.TutorDtos.DueReviews;
import com.agenticai.core.tutor.dto.TutorDtos.LessonContentView;
import com.agenticai.core.tutor.dto.TutorDtos.LessonView;
import com.agenticai.core.tutor.dto.TutorDtos.ModuleView;
import com.agenticai.core.tutor.dto.TutorDtos.PlanSummary;
import com.agenticai.core.tutor.dto.TutorDtos.PlanView;
import com.agenticai.core.tutor.dto.TutorDtos.QuizAnswerResult;
import com.agenticai.core.tutor.dto.TutorDtos.QuizQuestionView;
import com.agenticai.core.tutor.dto.TutorDtos.ReviewAnswerResult;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Study plans, quiz grading and the review deck for the Learn Anything tutor.
 *
 * <p><b>Ownership.</b> Every member-facing method takes the caller's {@code subject} (the principal
 * name, never a request field) and only ever loads rows through
 * {@link StudyPlanRepository#findByIdAndOwnerSubject}; another member's plan is indistinguishable
 * from a missing one (404).
 *
 * <p><b>Honest grading.</b> Answers are graded here by {@link QuizGrader} against the stored answer
 * key and scheduled by {@link SpacedRepetition} - both pure code, no model call. The answer key leaves
 * the server only in the result of an attempt and, afterwards, on questions the learner has answered.
 *
 * <p><b>Caps.</b> Plans per member, lessons per plan and the stored curriculum size are bounded
 * ({@code app.tutor.max-plans-per-user}, {@code max-lessons-per-plan}, {@code max-curriculum-bytes}).
 */
@Service
public class StudyPlanService {

    /** A member's deck never grows past this; the oldest not-due card makes room. */
    static final int MAX_REVIEW_CARDS = 500;
    /** Decks read per query by the reminder sweep - bounds what one tick holds in memory. */
    static final int REMINDER_PAGE_SIZE = 50;

    private final StudyPlanRepository repository;
    private final int maxPlansPerUser;
    private final int maxLessonsPerPlan;
    private final int maxCurriculumBytes;
    private final Clock clock;

    @Autowired
    public StudyPlanService(
        StudyPlanRepository repository,
        @Value("${app.tutor.max-plans-per-user:20}") int maxPlansPerUser,
        @Value("${app.tutor.max-lessons-per-plan:60}") int maxLessonsPerPlan,
        @Value("${app.tutor.max-curriculum-bytes:400000}") int maxCurriculumBytes
    ) {
        this(repository, maxPlansPerUser, maxLessonsPerPlan, maxCurriculumBytes, Clock.systemUTC());
    }

    StudyPlanService(
        StudyPlanRepository repository, int maxPlansPerUser, int maxLessonsPerPlan, int maxCurriculumBytes, Clock clock
    ) {
        this.repository = repository;
        this.maxPlansPerUser = Math.max(1, maxPlansPerUser);
        this.maxLessonsPerPlan = Math.max(1, maxLessonsPerPlan);
        this.maxCurriculumBytes = Math.max(10_000, maxCurriculumBytes);
        this.clock = clock;
    }

    public int maxPlansPerUser() {
        return maxPlansPerUser;
    }

    public int maxLessonsPerPlan() {
        return maxLessonsPerPlan;
    }

    // ------------------------------------------------------------------ plans

    @Transactional(readOnly = true)
    public List<PlanSummary> list(String subject) {
        Instant now = clock.instant();
        List<PlanSummary> out = new ArrayList<>();
        for (StudyPlan plan : repository.findByOwnerSubjectOrderByUpdatedAtDesc(subject)) {
            out.add(summary(plan, now));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public PlanView get(String subject, UUID planId) {
        return view(requireOwned(subject, planId), clock.instant());
    }

    /** Manual create with a caller-supplied curriculum. */
    @Transactional
    public PlanView create(String subject, CreatePlanRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("A study plan needs a topic and at least one module.");
        }
        return createFrom(
            subject,
            StudyPlanValidation.topic(request.topic()),
            StudyPlanValidation.language(request.language()),
            StudyPlanValidation.level(request.level()),
            StudyPlanValidation.goal(request.goal()),
            StudyPlanValidation.curriculum(request.modules(), maxLessonsPerPlan));
    }

    /** Persists an already-validated curriculum (manual create, and the generator's outline). */
    @Transactional
    public PlanView createFrom(String subject, String topic, String language, String level, String goal, Curriculum curriculum) {
        assertCanCreate(subject);
        String curriculumJson = StudyPlanDocs.write(curriculum);
        assertCurriculumSize(curriculumJson);
        Instant now = clock.instant();
        StudyPlan plan = new StudyPlan(
            subject, topic, language, level, goal, curriculumJson,
            StudyPlanDocs.write(new ReviewDeck(List.of(), null)), now);
        return view(save(plan), now);
    }

    /** 409 once the member holds {@code max-plans-per-user} plans - checked before any model call too. */
    @Transactional(readOnly = true)
    public void assertCanCreate(String subject) {
        if (repository.countByOwnerSubject(subject) >= maxPlansPerUser) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "You already have " + maxPlansPerUser + " courses. Delete one you no longer need to start another.");
        }
    }

    @Transactional
    public void delete(String subject, UUID planId) {
        repository.delete(requireOwned(subject, planId));
    }

    /** Account deletion: every plan the member owns. */
    @Transactional
    public long deleteAllOfUser(String subject) {
        if (subject == null || subject.isBlank()) {
            return 0;
        }
        return repository.deleteByOwnerSubject(subject);
    }

    @Transactional
    public void setTutorAgent(String subject, UUID planId, UUID agentId) {
        StudyPlan plan = requireOwned(subject, planId);
        plan.setTutorAgentId(agentId);
        save(plan);
    }

    // ------------------------------------------------------------------ lessons

    /** The stored lesson (answer key included) - for server-side collaborators only, never for a response. */
    @Transactional(readOnly = true)
    public Lesson requireLesson(String subject, UUID planId, String lessonId) {
        return findLesson(StudyPlanDocs.readCurriculum(requireOwned(subject, planId).getCurriculumJson()), lessonId);
    }

    @Transactional(readOnly = true)
    public LessonView lesson(String subject, UUID planId, String lessonId) {
        return lessonView(requireLesson(subject, planId, lessonId));
    }

    /**
     * Caches generated content on a lesson. First writer wins: if the lesson already has content
     * (a double-click, a second tab) the stored content is returned untouched, so a learner's
     * answers are never orphaned by a regeneration.
     */
    @Transactional
    public LessonView storeLessonContent(String subject, UUID planId, String lessonId, LessonContent content, List<QuizItem> quiz) {
        StudyPlan plan = requireOwned(subject, planId);
        Curriculum curriculum = StudyPlanDocs.readCurriculum(plan.getCurriculumJson());
        Lesson existing = findLesson(curriculum, lessonId);
        if (existing.hasContent()) {
            return lessonView(existing);
        }
        Curriculum updated = replaceLesson(curriculum, lessonId, l -> l.withContent(content, quiz));
        String json = StudyPlanDocs.write(updated);
        assertCurriculumSize(json);
        plan.updateCurriculum(json, clock.instant());
        save(plan);
        return lessonView(findLesson(updated, lessonId));
    }

    /**
     * Grades one quiz answer against the stored key. A wrong answer adds the question to the review
     * deck (or lapses the card already there). The answer key is only returned here, after the attempt.
     *
     * <p>The FIRST attempt is the recorded one: the key is revealed by that attempt, so a later try is
     * graded for practice (and a wrong one still lapses the card) but never rewrites what the lesson
     * shows as the learner's answer.
     */
    @Transactional
    public QuizAnswerResult submitQuizAnswer(String subject, UUID planId, String lessonId, String questionId, Integer selectedIndex) {
        StudyPlan plan = requireOwned(subject, planId);
        Curriculum curriculum = StudyPlanDocs.readCurriculum(plan.getCurriculumJson());
        Lesson lesson = findLesson(curriculum, lessonId);
        QuizItem item = null;
        if (lesson.quiz() != null) {
            for (QuizItem candidate : lesson.quiz()) {
                if (candidate.id().equals(questionId)) {
                    item = candidate;
                    break;
                }
            }
        }
        if (item == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That quiz question does not exist.");
        }
        boolean correct = QuizGrader.isCorrect(item.correctIndex(), item.options().size(), selectedIndex);
        Instant now = clock.instant();

        QuizItem answered = item.answeredIndex() == null ? item.withAnswer(selectedIndex) : item;
        List<QuizItem> quiz = new ArrayList<>();
        for (QuizItem q : lesson.quiz()) {
            quiz.add(q.id().equals(questionId) ? answered : q);
        }
        String lessonStatus = StudyPlanDocs.LESSON_NOT_STARTED.equals(lesson.status())
            ? StudyPlanDocs.LESSON_IN_PROGRESS : lesson.status();
        Curriculum updated = replaceLesson(curriculum, lessonId, l -> l.withQuiz(quiz).withStatus(lessonStatus));
        plan.updateCurriculum(StudyPlanDocs.write(updated), now);

        if (!correct) {
            ReviewDeck deck = StudyPlanDocs.readReview(plan.getReviewJson());
            plan.updateReview(StudyPlanDocs.write(lapseOrAddCard(deck, lessonId, item, now)), now);
        }
        save(plan);
        return new QuizAnswerResult(
            questionId, correct, selectedIndex, item.correctIndex(), item.explanation(), !correct, lessonStatus);
    }

    @Transactional
    public PlanView completeLesson(String subject, UUID planId, String lessonId) {
        StudyPlan plan = requireOwned(subject, planId);
        Curriculum curriculum = StudyPlanDocs.readCurriculum(plan.getCurriculumJson());
        findLesson(curriculum, lessonId);
        Curriculum updated = replaceLesson(curriculum, lessonId, l -> l.withStatus(StudyPlanDocs.LESSON_COMPLETED));
        Instant now = clock.instant();
        plan.updateCurriculum(StudyPlanDocs.write(updated), now);
        plan.setStatus(allCompleted(updated) ? StudyPlan.STATUS_COMPLETED : StudyPlan.STATUS_ACTIVE);
        return view(save(plan), now);
    }

    // ------------------------------------------------------------------ reviews

    /** The caller's due cards across all their plans: most overdue first, at most one sitting's worth. */
    @Transactional(readOnly = true)
    public DueReviews dueReviews(String subject) {
        Instant now = clock.instant();
        List<DueCardView> all = new ArrayList<>();
        for (StudyPlan plan : repository.findByOwnerSubjectOrderByUpdatedAtDesc(subject)) {
            for (Card card : StudyPlanDocs.readReview(plan.getReviewJson()).cards()) {
                if (SpacedRepetition.isDue(card.dueInstant(), now)) {
                    all.add(new DueCardView(
                        plan.getId(), plan.getTopic(), plan.getLanguage(), card.id(), card.lessonId(),
                        card.question(), card.options(), card.dueAt(), card.reps(), card.lapses()));
                }
            }
        }
        List<DueCardView> sitting = SpacedRepetition.queue(
            all, c -> StudyPlanDocs.parseInstant(c.dueAt()), now, SpacedRepetition.SESSION_CARD_CAP);
        return new DueReviews(sitting, all.size(), SpacedRepetition.SESSION_CARD_CAP);
    }

    @Transactional
    public ReviewAnswerResult answerReview(String subject, UUID planId, String cardId, Integer selectedIndex) {
        StudyPlan plan = requireOwned(subject, planId);
        ReviewDeck deck = StudyPlanDocs.readReview(plan.getReviewJson());
        Card card = null;
        for (Card candidate : deck.cards()) {
            if (candidate.id().equals(cardId)) {
                card = candidate;
                break;
            }
        }
        if (card == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That review card does not exist.");
        }
        boolean correct = QuizGrader.isCorrect(card.correctIndex(), card.options().size(), selectedIndex);
        Instant now = clock.instant();
        if (!SpacedRepetition.isDue(card.dueInstant(), now)) {
            // Only a due card can be answered: repeating a card that was just rescheduled would
            // otherwise stretch its interval without any spacing having happened.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That card is not due for review yet.");
        }
        SpacedRepetition.Schedule schedule = SpacedRepetition.next(card.state(), correct, now);
        Card rescheduled = card.rescheduled(
            schedule, correct ? StudyPlanDocs.RESULT_CORRECT : StudyPlanDocs.RESULT_INCORRECT);
        List<Card> cards = new ArrayList<>();
        for (Card c : deck.cards()) {
            cards.add(c.id().equals(cardId) ? rescheduled : c);
        }
        plan.updateReview(StudyPlanDocs.write(new ReviewDeck(cards, deck.lastRemindedAt())), now);
        save(plan);

        // The member's other decks, read without their curricula.
        int remaining = countDue(cards, now);
        String thisPlan = plan.getId().toString();
        for (Object[] row : repository.findReviewDecksOfOwner(subject)) {
            if (row == null || row.length < 2 || thisPlan.equalsIgnoreCase(String.valueOf(row[0]))) {
                continue;
            }
            remaining += countDue(readDeckLeniently(row[1]).cards(), now);
        }
        return new ReviewAnswerResult(
            cardId, correct, selectedIndex, card.correctIndex(), card.explanation(),
            rescheduled.dueAt(), schedule.intervalDays(), remaining);
    }

    // ------------------------------------------------------------------ reminders (sweep)

    /** One member's due reviews, summed across their plans. {@code topic} is the plan with the most due cards. */
    public record ReviewReminder(String ownerSubject, int dueCount, String topic, int planCount) {
    }

    /**
     * For the reminder sweep (runs inside one tenant's context): every member with at least one due
     * card who has not been reminded within {@code minGap}. Stamps {@code lastRemindedAt} on the decks
     * it reports, so the next tick skips them - the caller sends exactly one notification per entry.
     *
     * <p>Plan status is deliberately ignored: a learner who finished a course still has its cards
     * coming due. Only review decks are read ({@link StudyPlanRepository#findReviewDecksAfter}), one
     * bounded page at a time, and the stamp is a targeted update - the curriculum is never loaded.
     */
    @Transactional
    public List<ReviewReminder> claimDueReminders(Duration minGap) {
        Instant now = clock.instant();
        Map<String, OwnerDue> byOwner = new LinkedHashMap<>();
        String afterId = StudyPlanRepository.FIRST_PAGE;
        while (true) {
            List<Object[]> page = repository.findReviewDecksAfter(afterId, REMINDER_PAGE_SIZE);
            if (page == null || page.isEmpty()) {
                break;
            }
            for (Object[] row : page) {
                String owner = row[1] == null ? null : String.valueOf(row[1]);
                if (owner == null) {
                    continue;
                }
                ReviewDeck deck = readDeckLeniently(row[3]);
                Instant last = StudyPlanDocs.parseInstant(deck.lastRemindedAt());
                boolean recent = last != null && last.plus(minGap).isAfter(now);
                int due = countDue(deck.cards(), now);
                if (due == 0 && !recent) {
                    continue;
                }
                OwnerDue state = byOwner.computeIfAbsent(owner, k -> new OwnerDue());
                state.remindedRecently |= recent;
                if (due > 0) {
                    state.totalDue += due;
                    state.duePlanIds.add(String.valueOf(row[0]));
                    if (due > state.bestDue) {
                        state.bestDue = due;
                        state.bestTopic = row[2] == null ? null : String.valueOf(row[2]);
                    }
                }
            }
            afterId = String.valueOf(page.get(page.size() - 1)[0]);
            if (page.size() < REMINDER_PAGE_SIZE) {
                break;
            }
        }
        List<ReviewReminder> out = new ArrayList<>();
        String stamp = now.toString();
        for (Map.Entry<String, OwnerDue> entry : byOwner.entrySet()) {
            OwnerDue state = entry.getValue();
            if (state.totalDue == 0 || state.remindedRecently) {
                continue;
            }
            for (String planId : state.duePlanIds) {
                repository.stampReminded(planId, stamp);
            }
            out.add(new ReviewReminder(entry.getKey(), state.totalDue, state.bestTopic, state.duePlanIds.size()));
        }
        return out;
    }

    /** What the sweep keeps per member between pages: a few numbers and ids, never a deck. */
    private static final class OwnerDue {
        private int totalDue;
        private int bestDue;
        private String bestTopic;
        private boolean remindedRecently;
        private final List<String> duePlanIds = new ArrayList<>();
    }

    // ------------------------------------------------------------------ internals

    private StudyPlan requireOwned(String subject, UUID planId) {
        if (subject == null || subject.isBlank() || planId == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That course does not exist.");
        }
        return repository.findByIdAndOwnerSubject(planId, subject)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "That course does not exist."));
    }

    /** Flushes so a concurrent edit (second tab, double submit) surfaces here as a 409, not a 500 at commit. */
    private StudyPlan save(StudyPlan plan) {
        try {
            return repository.saveAndFlush(plan);
        } catch (OptimisticLockingFailureException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "This course was changed somewhere else at the same time. Reload and try again.");
        }
    }

    private void assertCurriculumSize(String curriculumJson) {
        if (curriculumJson.getBytes(StandardCharsets.UTF_8).length > maxCurriculumBytes) {
            throw new IllegalArgumentException("This course is too large to store. Use fewer or shorter lessons.");
        }
    }

    private static Lesson findLesson(Curriculum curriculum, String lessonId) {
        for (Module module : curriculum.modules()) {
            for (Lesson lesson : module.lessons()) {
                if (lesson.id().equals(lessonId)) {
                    return lesson;
                }
            }
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That lesson does not exist.");
    }

    private static Curriculum replaceLesson(Curriculum curriculum, String lessonId, UnaryOperator<Lesson> change) {
        List<Module> modules = new ArrayList<>();
        for (Module module : curriculum.modules()) {
            List<Lesson> lessons = new ArrayList<>();
            for (Lesson lesson : module.lessons()) {
                lessons.add(lesson.id().equals(lessonId) ? change.apply(lesson) : lesson);
            }
            modules.add(new Module(module.id(), module.title(), lessons));
        }
        return new Curriculum(modules);
    }

    private static boolean allCompleted(Curriculum curriculum) {
        boolean any = false;
        for (Module module : curriculum.modules()) {
            for (Lesson lesson : module.lessons()) {
                any = true;
                if (!StudyPlanDocs.LESSON_COMPLETED.equals(lesson.status())) {
                    return false;
                }
            }
        }
        return any;
    }

    /** The card id is derived ({@code <lessonId>:<questionId>}), so a repeated mistake lapses one card instead of piling up duplicates. */
    private static ReviewDeck lapseOrAddCard(ReviewDeck deck, String lessonId, QuizItem item, Instant now) {
        String cardId = lessonId + ":" + item.id();
        List<Card> cards = new ArrayList<>();
        boolean found = false;
        for (Card card : deck.cards()) {
            if (card.id().equals(cardId)) {
                found = true;
                cards.add(card.rescheduled(
                    SpacedRepetition.next(card.state(), false, now), StudyPlanDocs.RESULT_INCORRECT));
            } else {
                cards.add(card);
            }
        }
        if (!found) {
            SpacedRepetition.Schedule first = SpacedRepetition.next(SpacedRepetition.fresh(), false, now);
            if (cards.size() >= MAX_REVIEW_CARDS) {
                cards.remove(indexOfLeastUrgent(cards));
            }
            cards.add(new Card(
                cardId, lessonId, item.question(), item.options(), item.correctIndex(), item.explanation(),
                first.ease(), first.intervalDays(), first.reps(), first.lapses(), first.dueAt().toString(),
                StudyPlanDocs.RESULT_INCORRECT));
        }
        return new ReviewDeck(cards, deck.lastRemindedAt());
    }

    /** The card due furthest in the future - the best-known one - makes room when the deck is full. */
    private static int indexOfLeastUrgent(List<Card> cards) {
        int index = 0;
        Instant latest = null;
        for (int i = 0; i < cards.size(); i++) {
            Instant due = cards.get(i).dueInstant();
            if (due != null && (latest == null || due.isAfter(latest))) {
                latest = due;
                index = i;
            }
        }
        return index;
    }

    /** A deck from a projection row; one unreadable deck counts as empty instead of failing the whole read. */
    private static ReviewDeck readDeckLeniently(Object json) {
        try {
            return StudyPlanDocs.readReview(json == null ? null : String.valueOf(json));
        } catch (IllegalStateException e) {
            return new ReviewDeck(List.of(), null);
        }
    }

    private static int countDue(List<Card> cards, Instant now) {
        int due = 0;
        for (Card card : cards) {
            if (SpacedRepetition.isDue(card.dueInstant(), now)) {
                due++;
            }
        }
        return due;
    }

    private static PlanSummary summary(StudyPlan plan, Instant now) {
        Curriculum curriculum = StudyPlanDocs.readCurriculum(plan.getCurriculumJson());
        int total = 0;
        int completed = 0;
        for (Module module : curriculum.modules()) {
            for (Lesson lesson : module.lessons()) {
                total++;
                if (StudyPlanDocs.LESSON_COMPLETED.equals(lesson.status())) {
                    completed++;
                }
            }
        }
        int due = countDue(StudyPlanDocs.readReview(plan.getReviewJson()).cards(), now);
        return new PlanSummary(
            plan.getId(), plan.getTopic(), plan.getLanguage(), plan.getLevel(), plan.getGoal(), plan.getStatus(),
            total, completed, due, plan.getTutorAgentId(), plan.getCreatedAt(), plan.getUpdatedAt());
    }

    private static PlanView view(StudyPlan plan, Instant now) {
        PlanSummary s = summary(plan, now);
        List<ModuleView> modules = new ArrayList<>();
        for (Module module : StudyPlanDocs.readCurriculum(plan.getCurriculumJson()).modules()) {
            List<LessonView> lessons = new ArrayList<>();
            for (Lesson lesson : module.lessons()) {
                lessons.add(lessonView(lesson));
            }
            modules.add(new ModuleView(module.id(), module.title(), lessons));
        }
        return new PlanView(
            s.id(), s.topic(), s.language(), s.level(), s.goal(), s.status(), s.totalLessons(), s.completedLessons(),
            s.dueReviews(), s.tutorAgentId(), s.createdAt(), s.updatedAt(), modules);
    }

    /** The only place a stored lesson becomes a response: the answer key is added per question, and only once answered. */
    static LessonView lessonView(Lesson lesson) {
        LessonContentView content = lesson.content() == null ? null : new LessonContentView(
            lesson.content().explanation(), lesson.content().keyPoints(), lesson.content().workedExample());
        List<QuizQuestionView> quiz = null;
        if (lesson.quiz() != null) {
            quiz = new ArrayList<>();
            for (QuizItem item : lesson.quiz()) {
                Integer answered = item.answeredIndex();
                quiz.add(answered == null
                    ? new QuizQuestionView(item.id(), item.question(), item.options(), false, null, null, null, null)
                    : new QuizQuestionView(
                        item.id(), item.question(), item.options(), true, answered,
                        answered == item.correctIndex(), item.correctIndex(), item.explanation()));
            }
        }
        return new LessonView(
            lesson.id(), lesson.title(), lesson.objective(), lesson.status(), lesson.hasContent(), content, quiz);
    }
}
