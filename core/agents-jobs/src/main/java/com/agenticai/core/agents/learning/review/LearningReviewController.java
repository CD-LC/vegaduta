package com.agenticai.core.agents.learning.review;

import com.agenticai.core.agents.learning.LearningProposalService;
import com.agenticai.core.agents.learning.LearningProposalStatus;
import java.security.Principal;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@code GET /api/learning/review/summary}: the short "what I learned this week" summary shown at the top of the
 * Learned section. Scoped to the signed-in person; 404 unless the daily review and the proposal store are on.
 */
@RestController
@RequestMapping("/api/learning/review")
@ConditionalOnProperty(
        name = {"app.learning.proposals.enabled", "app.learning.review.enabled"},
        havingValue = "true")
public class LearningReviewController {

    private final LearningProposalService proposals;

    public LearningReviewController(LearningProposalService proposals) {
        this.proposals = proposals;
    }

    @GetMapping("/summary")
    public WeeklyLearningSummary summary(Principal principal) {
        if (principal == null || !StringUtils.hasText(principal.getName())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in to see what was learned.");
        }
        String subject = principal.getName();
        return WeeklyLearningSummary.compose(
                proposals.list(subject, LearningProposalStatus.APPROVED, null),
                proposals.list(subject, LearningProposalStatus.PENDING, null).size(),
                Instant.now());
    }
}
