package com.agenticai.core.agents.job.durability;

import com.agenticai.core.agents.job.AgentJob;
import com.agenticai.core.agents.job.AgentJobRepository;
import com.agenticai.core.agents.job.AgentJobStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/**
 * The boot report's read-only view of {@code agent_job}.
 *
 * <p><b>Why a second repository interface over the same entity.</b> Two Spring
 * Data interfaces on one entity is legal and is the mechanism this fan-out uses
 * to let a feature package add the queries it needs without opening {@link
 * AgentJobRepository}, which is shared with three other features in flight. It
 * deliberately extends the bare {@link Repository} marker rather than {@code
 * JpaRepository}: this view must never be able to write. Every state change in
 * PR-4 goes through {@link AgentJobRepository#requeueIfStillRunning} or {@link
 * AgentJobRepository#failIfStillRunning}, both guarded conditional UPDATEs owned
 * by the sweep - the boot report only ever counts and logs.
 */
public interface AgentJobRecoveryLookup extends Repository<AgentJob, UUID> {

    /** Total rows in this state in the CURRENT tenant schema (see {@link AgentJobBootReconciliation}). */
    long countByStatus(AgentJobStatus status);

    /**
     * How many of those carry a given {@code owner_instance}. Two counts rather
     * than a single {@code ...OwnerInstanceNot} query on purpose: SQL {@code <>}
     * does not match NULL, so a "not mine" query would silently drop any RUNNING
     * row whose owner column is null, and those are exactly the rows most worth
     * seeing in a boot report.
     */
    long countByStatusAndOwnerInstance(AgentJobStatus status, String ownerInstance);

    /**
     * A bounded sample for the per-row log lines. Capped in the method name
     * rather than by a {@code Pageable} so this can never allocate an unbounded
     * list during startup - the same reasoning as {@link
     * AgentJobRepository#findTop50ByStatusOrderByCreatedAtAsc}.
     */
    List<AgentJob> findTop20ByStatusOrderByCreatedAtAsc(AgentJobStatus status);
}
