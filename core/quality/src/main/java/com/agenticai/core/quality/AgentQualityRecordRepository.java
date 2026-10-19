package com.agenticai.core.quality;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Repository for AgentQualityRecord entities.
 *
 * Provides queries for retrieving historical quality data for analysis and dashboards.
 */
@Repository
public interface AgentQualityRecordRepository extends JpaRepository<AgentQualityRecord, UUID> {

    /**
     * Find all quality records for a specific agent, sorted by recency.
     */
    Page<AgentQualityRecord> findByAgentIdOrderByCreatedAtDesc(UUID agentId, Pageable pageable);

    /**
     * Find quality records for an agent within a time range.
     */
    List<AgentQualityRecord> findByAgentIdAndCreatedAtBetweenOrderByCreatedAtAsc(
        UUID agentId,
        Instant startTime,
        Instant endTime
    );

    /**
     * Count quality records for an agent.
     */
    long countByAgentId(UUID agentId);

    /**
     * Calculate average overall score for an agent.
     */
    @Query("SELECT AVG(r.overallScore) FROM AgentQualityRecord r WHERE r.agentId = :agentId")
    Double getAverageScoreForAgent(@Param("agentId") UUID agentId);

    /**
     * Calculate average score for a specific evaluation type.
     */
    @Query("""
        SELECT AVG(
            CAST(
                (SELECT r2.scores FROM AgentQualityRecord r2
                 WHERE r2.agentId = :agentId
                 AND KEY(r2.scores) = :evalType)
                AS DOUBLE
            )
        )
        FROM AgentQualityRecord r
        WHERE r.agentId = :agentId
        """)
    Double getAverageScoreForEvaluationType(
        @Param("agentId") UUID agentId,
        @Param("evalType") String evalType
    );

    /**
     * Get the latest quality record for an agent.
     */
    AgentQualityRecord findFirstByAgentIdOrderByCreatedAtDesc(UUID agentId);
}
