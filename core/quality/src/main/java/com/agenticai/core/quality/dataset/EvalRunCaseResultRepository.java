package com.agenticai.core.quality.dataset;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Tenant-scoped repository for {@link EvalRunCaseResult}. */
@Repository
public interface EvalRunCaseResultRepository extends JpaRepository<EvalRunCaseResult, UUID> {

    List<EvalRunCaseResult> findByRunIdOrderByPositionAscCreatedAtAsc(UUID runId);
}
