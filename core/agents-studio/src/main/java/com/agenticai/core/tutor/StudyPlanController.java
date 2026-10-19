package com.agenticai.core.tutor;

import com.agenticai.core.tutor.dto.TutorDtos.CreatePlanRequest;
import org.springframework.web.bind.annotation.RequestParam;
import com.agenticai.core.tenancy.ListPaging;
import com.agenticai.core.tutor.dto.TutorDtos.DueReviews;
import com.agenticai.core.tutor.dto.TutorDtos.GeneratePlanRequest;
import com.agenticai.core.tutor.dto.TutorDtos.LessonView;
import com.agenticai.core.tutor.dto.TutorDtos.PlanSummary;
import com.agenticai.core.tutor.dto.TutorDtos.PlanView;
import com.agenticai.core.tutor.dto.TutorDtos.QuizAnswerRequest;
import com.agenticai.core.tutor.dto.TutorDtos.QuizAnswerResult;
import com.agenticai.core.tutor.dto.TutorDtos.ReviewAnswerRequest;
import com.agenticai.core.tutor.dto.TutorDtos.ReviewAnswerResult;
import com.agenticai.core.tutor.dto.TutorDtos.TutorLimits;
import com.agenticai.core.tutor.dto.TutorDtos.TutorStatus;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The Learn Anything tutor's REST surface: a member's own study plans, quiz answers and review deck.
 *
 * <p>Only exists when {@code app.tutor.enabled=true} (default false). With the flag off no route under
 * {@code /api/tutor} is mapped, so every call - including {@code GET /api/tutor/status} - answers the
 * platform's plain 404, and the console hides the feature on that.
 *
 * <p>Thin on purpose: ownership, caps, grading and status codes are {@link StudyPlanService}'s;
 * moderation, the hourly generation cap and the model calls are {@link StudyPlanGenerator}'s.
 * Everything is keyed on {@code principal.getName()} exactly like {@code UserMemoryController}; no
 * request field can name another member. No {@code SecurityConfig} entry - the path falls through to
 * {@code anyRequest().authenticated()}.
 */
@RestController
@RequestMapping("/api/tutor")
@ConditionalOnProperty(prefix = "app.tutor", name = "enabled", havingValue = "true")
public class StudyPlanController {

    private final StudyPlanService service;
    private final StudyPlanGenerator generator;
    private final boolean remindersEnabled;
    private final int generationsPerHour;

    public StudyPlanController(
        StudyPlanService service,
        StudyPlanGenerator generator,
        @Value("${app.tutor.reminders.enabled:false}") boolean remindersEnabled,
        @Value("${app.tutor.generate.max-per-user-per-hour:10}") int generationsPerHour
    ) {
        this.service = service;
        this.generator = generator;
        this.remindersEnabled = remindersEnabled;
        this.generationsPerHour = generationsPerHour;
    }

    @GetMapping("/status")
    public TutorStatus status(Principal principal) {
        subject(principal);
        return new TutorStatus(true, true, remindersEnabled, new TutorLimits(
            service.maxPlansPerUser(), service.maxLessonsPerPlan(), StudyPlanValidation.MAX_TOPIC_CHARS,
            SpacedRepetition.SESSION_CARD_CAP, generationsPerHour));
    }

    @GetMapping("/plans")
    public ResponseEntity<List<PlanSummary>> list(Principal principal, @RequestParam(required = false) Integer limit, @RequestParam(required = false) Long offset) {
        return ListPaging.window(service.list(subject(principal)), limit, offset, 50, 200);
    }

    @PostMapping("/plans")
    public ResponseEntity<PlanView> create(@RequestBody CreatePlanRequest request, Principal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(subject(principal), request));
    }

    /** Any topic, any language: the model writes the outline, the server validates and stores it. */
    @PostMapping("/plans/generate")
    public ResponseEntity<PlanView> generate(@RequestBody GeneratePlanRequest request, Principal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(generator.generatePlan(subject(principal), request));
    }

    @GetMapping("/plans/{id}")
    public PlanView get(@PathVariable UUID id, Principal principal) {
        return service.get(subject(principal), id);
    }

    @DeleteMapping("/plans/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id, Principal principal) {
        service.delete(subject(principal), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/plans/{id}/lessons/{lessonId}/answers")
    public QuizAnswerResult answer(
        @PathVariable UUID id, @PathVariable String lessonId, @RequestBody QuizAnswerRequest request, Principal principal
    ) {
        if (request == null || !StringUtils.hasText(request.questionId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "questionId is required.");
        }
        return service.submitQuizAnswer(subject(principal), id, lessonId, request.questionId(), request.selectedIndex());
    }

    /** Idempotent: writes the lesson on first open, returns the cached one afterwards. No answer key in the reply. */
    @PostMapping("/plans/{id}/lessons/{lessonId}/generate")
    public LessonView generateLesson(@PathVariable UUID id, @PathVariable String lessonId, Principal principal) {
        return generator.generateLesson(subject(principal), id, lessonId);
    }

    @PostMapping("/plans/{id}/lessons/{lessonId}/complete")
    public PlanView complete(@PathVariable UUID id, @PathVariable String lessonId, Principal principal) {
        return service.completeLesson(subject(principal), id, lessonId);
    }

    @GetMapping("/reviews/due")
    public DueReviews dueReviews(Principal principal) {
        return service.dueReviews(subject(principal));
    }

    @PostMapping("/reviews/{planId}/{cardId}/answer")
    public ReviewAnswerResult answerReview(
        @PathVariable UUID planId, @PathVariable String cardId, @RequestBody ReviewAnswerRequest request, Principal principal
    ) {
        return service.answerReview(
            subject(principal), planId, cardId, request == null ? null : request.selectedIndex());
    }

    static String subject(Principal principal) {
        if (principal == null || !StringUtils.hasText(principal.getName())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in to use the tutor.");
        }
        return principal.getName();
    }
}
