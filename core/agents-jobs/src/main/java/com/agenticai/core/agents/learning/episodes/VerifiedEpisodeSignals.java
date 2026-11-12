package com.agenticai.core.agents.learning.episodes;

import com.agenticai.core.agents.job.approval.AgentJobApprovalLookup;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.approval.ApprovalStatus;
import com.agenticai.core.rl.domain.TurnFeedback;
import com.agenticai.core.rl.feedback.FeedbackService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The {@link EpisodeSignalSource} (ADR-0028 WP4c): counts of what people decided about a turn,
 * nothing else.
 *
 * <ul>
 *   <li><b>Approvals.</b> The turn's approval requests are those the tool guard recorded under the
 *       label {@code agent:<agentId>:session:<sessionId>} since the turn began. A request counts as
 *       rejected when its status is REJECTED and a person decided it; as approved when a person
 *       approved it (APPROVED or a later execution state). Pending requests, a request that expired
 *       undecided and anything without a human decider are not counted.
 *   <li><b>Rating.</b> {@code turn_feedback} rows ({@code FeedbackService}) for the same agent and
 *       session since the turn began: thumbs up, resolved, or a rating of 4-5 is ACCEPTED; thumbs
 *       down, unresolved, an edit or a rating of 1-2 is REJECTED. Conflicting or neutral feedback
 *       gives no verdict (null).
 * </ul>
 *
 * Feedback is usually given after the recorder has already filed the episode, so in practice only
 * the approval counts (decided during the turn) and feedback that beat the recorder are seen; the
 * episode text is never revised later. Everything read here is a human act in a tenant table; no
 * model output is read. Any failure or missing repository yields {@link Signals#NONE}, never a
 * guess. Runs on the recorder's worker with the turn's tenant applied.
 */
@Component
@ConditionalOnProperty(name = "app.memory.episodic.enabled", havingValue = "true")
public class VerifiedEpisodeSignals implements EpisodeSignalSource {

    private static final Logger log = LoggerFactory.getLogger(VerifiedEpisodeSignals.class);

    private final ObjectProvider<AgentJobApprovalLookup> approvals;
    private final ObjectProvider<FeedbackService> feedback;

    public VerifiedEpisodeSignals(
            ObjectProvider<AgentJobApprovalLookup> approvals,
            ObjectProvider<FeedbackService> feedback) {
        this.approvals = approvals;
        this.feedback = feedback;
    }

    @Override
    public Signals signalsFor(UUID agentId, String sessionId, Instant since) {
        if (agentId == null || sessionId == null || sessionId.isBlank() || since == null) {
            return Signals.NONE;
        }
        int approved = 0;
        int rejected = 0;
        try {
            AgentJobApprovalLookup lookup = approvals.getIfAvailable();
            if (lookup != null) {
                String label = "agent:" + agentId + ":session:" + sessionId;
                for (ApprovalRequest r : lookup.findTop20ByRequestedByOrderByCreatedAtDesc(label)) {
                    if (r == null || r.getCreatedAt() == null || r.getCreatedAt().isBefore(since)) {
                        continue;
                    }
                    if (!humanDecided(r)) {
                        continue;
                    }
                    if (r.getStatus() == ApprovalStatus.REJECTED) {
                        rejected++;
                    } else if (r.getStatus() != ApprovalStatus.PENDING) {
                        approved++;
                    }
                }
            }
        } catch (RuntimeException e) {
            log.debug("Approval signals unavailable for agent {}: {}", agentId, e.getMessage());
            approved = 0;
            rejected = 0;
        }
        return new Signals(approved, rejected, verdict(agentId, sessionId, since));
    }

    private static boolean humanDecided(ApprovalRequest r) {
        String by = r.getDecidedBy();
        return by != null && !by.isBlank() && !by.startsWith("system:") && r.getDecidedAt() != null;
    }

    private HumanVerdict verdict(UUID agentId, String sessionId, Instant since) {
        try {
            FeedbackService repo = feedback.getIfAvailable();
            if (repo == null) {
                return null;
            }
            List<TurnFeedback> rows = repo.recentForSession(agentId, sessionId, since);
            boolean accepted = false;
            boolean rejected = false;
            for (TurnFeedback f : rows) {
                if (f == null
                        || f.getSignal() == null
                        || f.getCreatedBy() == null
                        || f.getCreatedBy().isBlank()) {
                    continue; // not attributable to a person
                }
                switch (f.getSignal()) {
                    case THUMBS_UP, RESOLVED -> accepted = true;
                    case THUMBS_DOWN, UNRESOLVED, EDIT -> rejected = true;
                    case RATING -> {
                        Short rating = f.getRating();
                        if (rating != null && rating >= 4) {
                            accepted = true;
                        } else if (rating != null && rating <= 2) {
                            rejected = true;
                        }
                    }
                }
            }
            if (accepted == rejected) {
                return null; // none, neutral or conflicting
            }
            return accepted ? HumanVerdict.ACCEPTED : HumanVerdict.REJECTED;
        } catch (RuntimeException e) {
            log.debug("Feedback signals unavailable for agent {}: {}", agentId, e.getMessage());
            return null;
        }
    }
}
