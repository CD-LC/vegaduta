package com.agenticai.core.rl.repo;

import com.agenticai.core.rl.domain.Episode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EpisodeRepository extends JpaRepository<Episode, UUID> {

    List<Episode> findByAgentIdOrderByStartedAtDesc(UUID agentId);

    /** All episodes for an agent whose composite reward has been computed - the export/trend source. */
    List<Episode> findByAgentIdAndRewardIsNotNullOrderByStartedAtAsc(UUID agentId);

    /** The episode a human feedback signal belongs to (most recent matching turn wins). */
    Optional<Episode> findFirstByAgentIdAndSessionIdAndTurnRefOrderByStartedAtDesc(UUID agentId, String sessionId, String turnRef);

    /** Latest still-open (non-terminal) episode for a live turn - used by the trajectory advisor to attach steps. */
    Optional<Episode> findFirstByAgentIdAndSessionIdAndTerminalFalseOrderByStartedAtDesc(UUID agentId, String sessionId);

    /**
     * Latest episode recorded anywhere in a given session, regardless of
     * agent/turnRef - the match {@link com.agenticai.core.rl.feedback.FeedbackService
     * #recordApprovalDecision} uses. A workflow Approval-node's own
     * approval-request payload only ever carries the workflow run id (used
     * as the session id for every Agent-node turn inside that run - see
     * {@code WorkflowNodeExecutor#executeAgentNode}), never a specific
     * agentId/turnRef, so this is the widest key an approval decision can
     * reliably match back to "whatever model decision the human was
     * actually looking at."
     */
    Optional<Episode> findFirstBySessionIdOrderByStartedAtDesc(String sessionId);
}
