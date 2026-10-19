package com.agenticai.core.rl.web;

import com.agenticai.core.rl.domain.TurnFeedback;
import com.agenticai.core.rl.feedback.FeedbackService;
import com.agenticai.core.rl.web.dto.TurnFeedbackRequest;
import com.agenticai.core.rl.web.dto.TurnFeedbackResponse;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The human end of the RL reward loop: the web Playground's per-turn thumbs/rating/edit/resolution
 * control posts here. Mounted under {@code /api/agents} (matching the frontend {@code
 * api.sendTurnFeedback} path {@code POST /api/agents/{agentId}/turns/feedback}) - it falls under
 * {@code SecurityConfig}'s {@code anyRequest().authenticated()}, so no admin role is needed. {@code
 * TurnFeedbackAccessGate} (core/agents) requires RUNNER on the agent, the same as chatting with it,
 * and a recorded turn can only be rated by whoever had it ({@link FeedbackService#record}).
 *
 * <p>{@code FeedbackService.record} persists the signal and, when it matches a recorded episode,
 * folds a HUMAN reward into that episode's bandit arm - so a thumbs-up is training signal, not just
 * a stored opinion.
 */
@RestController
@RequestMapping("/api/agents")
public class RlFeedbackController {

    private final FeedbackService feedbackService;

    public RlFeedbackController(FeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    @PostMapping("/{agentId}/turns/feedback")
    public ResponseEntity<?> submit(
            @PathVariable UUID agentId, @Valid @RequestBody TurnFeedbackRequest request) {
        // turn_feedback.session_id is NOT NULL; the control omits sessionId
        // when no chat session is active, so coalesce to "" (which simply
        // won't match any real episode, leaving the signal stored without a
        // reward - the correct degradation).
        String sessionId = request.sessionId() == null ? "" : request.sessionId();
        TurnFeedback saved;
        try {
            saved =
                    feedbackService.record(
                            agentId,
                            sessionId,
                            request.turnRef(),
                            request.signal(),
                            request.rating(),
                            request.comment(),
                            request.editedAnswer());
        } catch (FeedbackService.TurnNotOwnedException e) {
            // Answered here, not thrown: GlobalExceptionHandler's catch-all would make it a 500.
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(TurnFeedbackResponse.from(saved));
    }
}
