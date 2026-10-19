package com.agenticai.core.tutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.tutor.dto.TutorDtos.CreatePlanRequest;
import com.agenticai.core.tutor.dto.TutorDtos.DueReviews;
import com.agenticai.core.tutor.dto.TutorDtos.GeneratePlanRequest;
import com.agenticai.core.tutor.dto.TutorDtos.LessonView;
import com.agenticai.core.tutor.dto.TutorDtos.PlanView;
import com.agenticai.core.tutor.dto.TutorDtos.QuizAnswerRequest;
import com.agenticai.core.tutor.dto.TutorDtos.QuizAnswerResult;
import com.agenticai.core.tutor.dto.TutorDtos.ReviewAnswerRequest;
import com.agenticai.core.tutor.dto.TutorDtos.ReviewAnswerResult;
import com.agenticai.core.tutor.dto.TutorDtos.TutorStatus;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

/** The tutor routes are a thin shell: the principal's name is the only identity they ever pass on. */
class StudyPlanControllerTest {

    private final StudyPlanService service = mock(StudyPlanService.class);
    private final StudyPlanGenerator generator = mock(StudyPlanGenerator.class);
    private final StudyPlanController controller = new StudyPlanController(service, generator, true, 10);
    private final Principal principal = () -> "u1";
    private final UUID planId = UUID.randomUUID();

    private PlanView plan() {
        return new PlanView(planId, "Topic", "en", "beginner", null, "ACTIVE", 1, 0, 0, null,
            Instant.EPOCH, Instant.EPOCH, List.of());
    }

    @Test
    void theControllerOnlyExistsWhenTheFlagIsOn() {
        ConditionalOnProperty condition = StudyPlanController.class.getAnnotation(ConditionalOnProperty.class);

        assertEquals("app.tutor", condition.prefix());
        assertEquals("enabled", condition.name()[0]);
        assertEquals("true", condition.havingValue());
        assertEquals(false, condition.matchIfMissing());
    }

    @Test
    void statusReportsTheLimits() {
        when(service.maxPlansPerUser()).thenReturn(20);
        when(service.maxLessonsPerPlan()).thenReturn(60);

        TutorStatus status = controller.status(principal);

        assertTrue(status.enabled());
        assertTrue(status.generationAvailable());
        assertTrue(status.remindersEnabled());
        assertEquals(20, status.limits().maxPlansPerUser());
        assertEquals(60, status.limits().maxLessonsPerPlan());
        assertEquals(StudyPlanValidation.MAX_TOPIC_CHARS, status.limits().maxTopicChars());
        assertEquals(SpacedRepetition.SESSION_CARD_CAP, status.limits().reviewSessionCap());
        assertEquals(10, status.limits().generationsPerHour());
    }

    @Test
    void createAnswers201AndPassesThePrincipalName() {
        CreatePlanRequest request = new CreatePlanRequest("Topic", null, null, null, List.of());
        PlanView created = plan();
        when(service.create("u1", request)).thenReturn(created);

        ResponseEntity<PlanView> response = controller.create(request, principal);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertSame(created, response.getBody());
    }

    @Test
    void generateAnswers201AndLessonGenerationDelegates() {
        GeneratePlanRequest request = new GeneratePlanRequest("Photosynthesis", "beginner", "hi", null, null);
        PlanView created = plan();
        when(generator.generatePlan("u1", request)).thenReturn(created);
        LessonView lesson = new LessonView("m1-l1", "T", "O", "NOT_STARTED", true, null, List.of());
        when(generator.generateLesson("u1", planId, "m1-l1")).thenReturn(lesson);

        ResponseEntity<PlanView> response = controller.generate(request, principal);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertSame(created, response.getBody());
        assertSame(lesson, controller.generateLesson(planId, "m1-l1", principal));
    }

    @Test
    void deleteAnswers204() {
        ResponseEntity<Void> response = controller.delete(planId, principal);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(service).delete("u1", planId);
    }

    @Test
    void answersAndReviewsDelegateWithTheCallersSubject() {
        QuizAnswerResult graded = new QuizAnswerResult("q1", true, 2, 2, "why", false, "IN_PROGRESS");
        when(service.submitQuizAnswer("u1", planId, "m1-l1", "q1", 2)).thenReturn(graded);
        ReviewAnswerResult reviewed = new ReviewAnswerResult("m1-l1:q1", true, 2, 2, "why", "2026-10-04T10:00:00Z", 1, 0);
        when(service.answerReview("u1", planId, "m1-l1:q1", 2)).thenReturn(reviewed);
        DueReviews due = new DueReviews(List.of(), 0, 12);
        when(service.dueReviews("u1")).thenReturn(due);
        when(service.completeLesson("u1", planId, "m1-l1")).thenReturn(plan());

        assertSame(graded, controller.answer(planId, "m1-l1", new QuizAnswerRequest("q1", 2), principal));
        assertSame(reviewed, controller.answerReview(planId, "m1-l1:q1", new ReviewAnswerRequest(2), principal));
        assertSame(due, controller.dueReviews(principal));
        assertEquals(planId, controller.complete(planId, "m1-l1", principal).id());
    }

    @Test
    void anAnswerWithoutAQuestionIdIs400() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> controller.answer(planId, "m1-l1", new QuizAnswerRequest(" ", 1), principal));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        verifyNoInteractions(service);
    }

    @Test
    void anAnonymousCallerIs401() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> controller.list(null, null, null));

        assertEquals(HttpStatus.UNAUTHORIZED, e.getStatusCode());
        verifyNoInteractions(service);
    }
}
