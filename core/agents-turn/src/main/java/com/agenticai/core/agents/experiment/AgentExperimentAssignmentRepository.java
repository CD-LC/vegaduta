package com.agenticai.core.agents.experiment;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentExperimentAssignmentRepository extends JpaRepository<AgentExperimentAssignment, UUID> {

    /** Stored-assignment-wins lookup - see {@link AgentExperimentAssignment}'s javadoc. */
    Optional<AgentExperimentAssignment> findByExperimentIdAndSubjectKey(UUID experimentId, String subjectKey);
}
