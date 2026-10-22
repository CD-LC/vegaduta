package com.agenticai.core.tutor;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * The shape of the two JSONB documents on {@link StudyPlan} and the one codec for them. These
 * records are the STORED form - they carry the quiz answer key - and never leave the server: every
 * API response goes through the views in {@code dto.TutorDtos}, which only reveal an answer after
 * the learner has attempted the question.
 *
 * <p>Timestamps inside the documents are ISO-8601 strings so the codec needs no Jackson time
 * module.
 */
public final class StudyPlanDocs {

    public static final String LESSON_NOT_STARTED = "NOT_STARTED";
    public static final String LESSON_IN_PROGRESS = "IN_PROGRESS";
    public static final String LESSON_COMPLETED = "COMPLETED";

    public static final String RESULT_CORRECT = "correct";
    public static final String RESULT_INCORRECT = "incorrect";

    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    private StudyPlanDocs() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Curriculum(List<Module> modules) {
        public Curriculum {
            modules = modules == null ? List.of() : List.copyOf(modules);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Module(String id, String title, List<Lesson> lessons) {
        public Module {
            lessons = lessons == null ? List.of() : List.copyOf(lessons);
        }
    }

    /**
     * {@code content} and {@code quiz} are null until the lesson has been generated (or supplied).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Lesson(
            String id,
            String title,
            String objective,
            String status,
            LessonContent content,
            List<QuizItem> quiz) {
        public Lesson {
            status = status == null ? LESSON_NOT_STARTED : status;
            quiz = quiz == null ? null : List.copyOf(quiz);
        }

        public boolean hasContent() {
            return content != null;
        }

        public Lesson withStatus(String newStatus) {
            return new Lesson(id, title, objective, newStatus, content, quiz);
        }

        public Lesson withQuiz(List<QuizItem> newQuiz) {
            return new Lesson(id, title, objective, status, content, newQuiz);
        }

        public Lesson withContent(LessonContent newContent, List<QuizItem> newQuiz) {
            return new Lesson(id, title, objective, status, newContent, newQuiz);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LessonContent(String explanation, List<String> keyPoints, String workedExample) {
        public LessonContent {
            keyPoints = keyPoints == null ? List.of() : List.copyOf(keyPoints);
        }
    }

    /**
     * {@code answeredIndex} is the learner's FIRST answer (the recorded attempt), null until they
     * attempt the question.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record QuizItem(
            String id,
            String question,
            List<String> options,
            int correctIndex,
            String explanation,
            Integer answeredIndex) {
        public QuizItem {
            options = options == null ? List.of() : List.copyOf(options);
        }

        public QuizItem withAnswer(int selectedIndex) {
            return new QuizItem(id, question, options, correctIndex, explanation, selectedIndex);
        }
    }

    /** {@code lastRemindedAt}: when the reminder sweep last notified the owner about this deck. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReviewDeck(List<Card> cards, String lastRemindedAt) {
        public ReviewDeck {
            cards = cards == null ? List.of() : List.copyOf(cards);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Card(
            String id,
            String lessonId,
            String question,
            List<String> options,
            int correctIndex,
            String explanation,
            double ease,
            int intervalDays,
            int reps,
            int lapses,
            String dueAt,
            String lastResult) {
        public Card {
            options = options == null ? List.of() : List.copyOf(options);
        }

        public Instant dueInstant() {
            return parseInstant(dueAt);
        }

        public SpacedRepetition.State state() {
            return new SpacedRepetition.State(ease, intervalDays, reps, lapses);
        }

        public Card rescheduled(SpacedRepetition.Schedule schedule, String result) {
            return new Card(
                    id,
                    lessonId,
                    question,
                    options,
                    correctIndex,
                    explanation,
                    schedule.ease(),
                    schedule.intervalDays(),
                    schedule.reps(),
                    schedule.lapses(),
                    schedule.dueAt().toString(),
                    result);
        }
    }

    public static Curriculum readCurriculum(String json) {
        if (json == null || json.isBlank()) {
            return new Curriculum(List.of());
        }
        try {
            Curriculum parsed = MAPPER.readValue(json, Curriculum.class);
            return parsed == null ? new Curriculum(List.of()) : parsed;
        } catch (JacksonException e) {
            throw new IllegalStateException("Stored study plan curriculum is not valid JSON", e);
        }
    }

    public static ReviewDeck readReview(String json) {
        if (json == null || json.isBlank()) {
            return new ReviewDeck(List.of(), null);
        }
        try {
            ReviewDeck parsed = MAPPER.readValue(json, ReviewDeck.class);
            return parsed == null ? new ReviewDeck(List.of(), null) : parsed;
        } catch (JacksonException e) {
            throw new IllegalStateException("Stored study plan review deck is not valid JSON", e);
        }
    }

    public static String write(Object document) {
        try {
            return MAPPER.writeValueAsString(document);
        } catch (JacksonException e) {
            throw new IllegalStateException("Could not serialise a study plan document", e);
        }
    }

    /** Null for a missing or unreadable timestamp - callers treat that as "due now" / "never". */
    public static Instant parseInstant(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(iso);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
