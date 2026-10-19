package com.agenticai.core.rl.repo;

import com.agenticai.core.rl.domain.FeedbackSignal;
import com.agenticai.core.rl.domain.TurnFeedback;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TurnFeedbackRepository extends JpaRepository<TurnFeedback, UUID> {

    List<TurnFeedback> findByAgentIdOrderByCreatedAtDesc(UUID agentId);

    long countByAgentIdAndSignal(UUID agentId, FeedbackSignal signal);

    /** Windowed count, for callers that judge feedback since a point in time (the brief proof ledger). */
    long countByAgentIdAndSignalAndCreatedAtGreaterThanEqual(UUID agentId, FeedbackSignal signal, java.time.Instant since);

    /** Feedback inside a window (an experiment's), both ends inclusive. */
    long countByAgentIdAndSignalAndCreatedAtBetween(
        UUID agentId, FeedbackSignal signal, java.time.Instant from, java.time.Instant to);
}
