package com.agenticai.core.quality.dataset;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Tenant-scoped repository for {@link EvalDatasetCase}. */
@Repository
public interface EvalDatasetCaseRepository extends JpaRepository<EvalDatasetCase, UUID> {

    /**
     * Replay + display order: explicit position first, then insertion order
     * as the tiebreaker so two cases sharing a position stay stable.
     */
    List<EvalDatasetCase> findByDatasetIdOrderByPositionAscCreatedAtAsc(UUID datasetId);

    long countByDatasetId(UUID datasetId);
}
