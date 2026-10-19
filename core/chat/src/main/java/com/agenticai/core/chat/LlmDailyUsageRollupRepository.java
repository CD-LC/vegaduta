package com.agenticai.core.chat;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Reads/writes the {@code llm_daily_usage_rollup} table (V119) within the
 * current tenant schema - see {@link LlmDailyUsageRollup} and the migration
 * comment for the rollup design. The daily job upserts via {@link
 * #findGrain} (find-then-update, else insert); reports read a date range via
 * {@link #findByRange}.
 */
public interface LlmDailyUsageRollupRepository extends JpaRepository<LlmDailyUsageRollup, UUID> {

    /**
     * The one existing row for a grain, if any - the upsert probe. {@code
     * agentId} is matched with an IS-NULL-aware predicate so the null-agent
     * (system) grain resolves to its single row, matching the COALESCE
     * sentinel the unique index uses.
     */
    @Query(
        "SELECT r FROM LlmDailyUsageRollup r WHERE r.usageDate = :usageDate "
            + "AND r.providerName = :providerName AND r.model = :model AND r.modality = :modality "
            + "AND ((:agentId IS NULL AND r.agentId IS NULL) OR r.agentId = :agentId)"
    )
    Optional<LlmDailyUsageRollup> findGrain(
        @Param("usageDate") LocalDate usageDate,
        @Param("providerName") String providerName,
        @Param("model") String model,
        @Param("agentId") UUID agentId,
        @Param("modality") String modality
    );

    /** Every rolled-up grain in {@code [fromDate, toDate]} (inclusive), for the report breakdowns. */
    @Query(
        "SELECT r FROM LlmDailyUsageRollup r WHERE r.usageDate >= :fromDate AND r.usageDate <= :toDate "
            + "ORDER BY r.usageDate ASC"
    )
    List<LlmDailyUsageRollup> findByRange(
        @Param("fromDate") LocalDate fromDate,
        @Param("toDate") LocalDate toDate
    );
}
