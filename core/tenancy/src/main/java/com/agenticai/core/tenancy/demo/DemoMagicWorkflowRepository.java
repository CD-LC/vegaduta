package com.agenticai.core.tenancy.demo;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface DemoMagicWorkflowRepository extends JpaRepository<DemoMagicWorkflow, UUID> {

    Optional<DemoMagicWorkflow> findByShareId(String shareId);

    long countBySessionId(UUID sessionId);

    /**
     * The durable half of the public run cap, taken atomically: one UPDATE
     * that only lands while there is allowance left. Returns 0 when the cap
     * is already spent - the caller treats that as "cap reached" without a
     * read-then-write race. (The per-IP-once rule lives in Redis next to it;
     * this row-level ceiling is what survives a Redis flush.)
     */
    @Modifying
    @Transactional
    @Query("""
        UPDATE DemoMagicWorkflow w
           SET w.runsUsed = w.runsUsed + 1
         WHERE w.id = :id AND w.runsUsed < w.runCap
        """)
    int tryConsumeRun(@Param("id") UUID id);
}
