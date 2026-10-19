package com.agenticai.core.tutor.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Wire shapes of {@code /api/tutor}. Requests never carry an owner or tenant - identity comes from
 * the principal. Responses never carry a quiz answer the learner has not attempted yet: see
 * {@link QuizQuestionView}.
 */
public final class TutorDtos {

    private TutorDtos() {
    }

    // ------------------------------------------------------------------ requests

    /** Manual create: the caller supplies the whole curriculum. Ids are assigned by the server. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CreatePlanRequest(String topic, String language, String level, String goal, List<ModuleInput> modules) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ModuleInput(String title, List<LessonInput> lessons) {
    }

    /** {@code content} and {@code quiz} are optional; a lesson without them can be generated later. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LessonInput(String title, String objective, LessonContentInput content, List<QuizInput> quiz) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LessonContentInput(String explanation, List<String> keyPoints, String workedExample) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record QuizInput(String question, List<String> options, Integer correctIndex, String explanation) {
    }

    /** Generate a plan for any topic. Only {@code topic} is required. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GeneratePlanRequest(String topic, String level, String language, String goal, Integer weeklyMinutes) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record QuizAnswerRequest(String questionId, Integer selectedIndex) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReviewAnswerRequest(Integer selectedIndex) {
    }

    /** Optional body of {@code POST /api/tutor/agent}: a plan of the caller's to remember the tutor on. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TutorAgentRequest(UUID planId) {
    }

    // ------------------------------------------------------------------ responses

    public record TutorLimits(
        int maxPlansPerUser, int maxLessonsPerPlan, int maxTopicChars, int reviewSessionCap, int generationsPerHour
    ) {
    }

    /** {@code GET /api/tutor/status}. The whole {@code /api/tutor} tree answers 404 when the feature is off. */
    public record TutorStatus(boolean enabled, boolean generationAvailable, boolean remindersEnabled, TutorLimits limits) {
    }

    public record PlanSummary(
        UUID id, String topic, String language, String level, String goal, String status,
        int totalLessons, int completedLessons, int dueReviews, UUID tutorAgentId,
        Instant createdAt, Instant updatedAt
    ) {
    }

    public record PlanView(
        UUID id, String topic, String language, String level, String goal, String status,
        int totalLessons, int completedLessons, int dueReviews, UUID tutorAgentId,
        Instant createdAt, Instant updatedAt, List<ModuleView> modules
    ) {
    }

    public record ModuleView(String id, String title, List<LessonView> lessons) {
    }

    /**
     * {@code generated} is false until the lesson has an explanation; {@code content} and {@code quiz}
     * are then null. {@code status} is NOT_STARTED, IN_PROGRESS or COMPLETED.
     */
    public record LessonView(
        String id, String title, String objective, String status, boolean generated,
        LessonContentView content, List<QuizQuestionView> quiz
    ) {
    }

    public record LessonContentView(String explanation, List<String> keyPoints, String workedExample) {
    }

    /**
     * One quiz question as the learner may see it. Before the first attempt {@code answered} is false
     * and {@code selectedIndex}, {@code correct}, {@code correctIndex} and {@code explanation} are
     * ABSENT from the JSON (not null) - the answer key is never sent ahead of an attempt.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record QuizQuestionView(
        String id, String question, List<String> options, boolean answered,
        Integer selectedIndex, Boolean correct, Integer correctIndex, String explanation
    ) {
    }

    /** Result of answering a lesson quiz question. A wrong answer puts the question in the review deck. */
    public record QuizAnswerResult(
        String questionId, boolean correct, int selectedIndex, int correctIndex, String explanation,
        boolean addedToReview, String lessonStatus
    ) {
    }

    /** A due review card - never carries the answer. */
    public record DueCardView(
        UUID planId, String planTopic, String language, String cardId, String lessonId,
        String question, List<String> options, String dueAt, int reps, int lapses
    ) {
    }

    /** {@code cards} holds at most {@code sessionCap} cards, most overdue first; {@code totalDue} counts all. */
    public record DueReviews(List<DueCardView> cards, int totalDue, int sessionCap) {
    }

    public record ReviewAnswerResult(
        String cardId, boolean correct, int selectedIndex, int correctIndex, String explanation,
        String nextDueAt, int intervalDays, int remainingDue
    ) {
    }

    /** {@code POST /api/tutor/agent}. */
    public record TutorAgentResponse(UUID agentId, boolean created) {
    }
}
