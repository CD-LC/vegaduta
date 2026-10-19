package com.agenticai.core.quality.dataset;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Tenant-scoped repository for {@link EvalDataset}. No tenant predicate in
 * any query - schema-per-tenant routing via the connection's search_path
 * handles isolation, like every other tenant repository.
 */
@Repository
public interface EvalDatasetRepository extends JpaRepository<EvalDataset, UUID> {

    List<EvalDataset> findByAgentIdOrderByCreatedAtDesc(UUID agentId);
}
