package com.agenticai.core.approval;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApprovalPolicyOverrideRepository extends JpaRepository<ApprovalPolicyOverride, UUID> {

    Optional<ApprovalPolicyOverride> findByActionType(String actionType);

    /**
     * Atomic upsert on the {@code uq_approval_policy_override_action_type}
     * unique constraint - closes {@link ApprovalPolicyService#setOverride}'s
     * documented read-then-write race, where two concurrent inserts for the
     * same brand-new action type left the loser with an unretryable
     * (aborted-transaction) constraint violation. The {@code id} is generated
     * caller-side rather than via {@code gen_random_uuid()} (not guaranteed
     * available everywhere - see V145's migration comment) and is only used
     * when the row doesn't exist yet; on conflict the existing row keeps its
     * id and just has its flag and timestamp updated, matching the previous
     * load-modify-save semantics exactly.
     */
    @Modifying(clearAutomatically = true)
    @Query(
        value = """
            INSERT INTO approval_policy_override (id, action_type, requires_approval, updated_at)
            VALUES (:id, :actionType, :requiresApproval, :updatedAt)
            ON CONFLICT (action_type)
            DO UPDATE SET requires_approval = EXCLUDED.requires_approval,
                          updated_at = EXCLUDED.updated_at
            """,
        nativeQuery = true)
    void upsert(
        @Param("id") UUID id,
        @Param("actionType") String actionType,
        @Param("requiresApproval") boolean requiresApproval,
        @Param("updatedAt") Instant updatedAt);
}
