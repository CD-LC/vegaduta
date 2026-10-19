package com.agenticai.core.common.improve;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentImprovementSuggestionRepository extends JpaRepository<AgentImprovementSuggestion, UUID> {

    List<AgentImprovementSuggestion> findTop100ByAgentIdOrderByCreatedAtDesc(UUID agentId);

    boolean existsByAgentIdAndIssueIdAndStatusIn(
        UUID agentId, UUID issueId, Collection<ImprovementStatus> statuses);
}
