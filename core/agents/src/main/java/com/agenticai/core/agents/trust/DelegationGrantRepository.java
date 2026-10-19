package com.agenticai.core.agents.trust;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface DelegationGrantRepository extends JpaRepository<DelegationGrant, UUID> {

    List<DelegationGrant> findByAgentIdOrderByCreatedAtDesc(UUID agentId);

    Optional<DelegationGrant> findFirstByAgentIdAndStatusOrderByCreatedAtDesc(
        UUID agentId, DelegationStatus status);

    /** Row lock for the spend-counter critical section at settlement time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT g FROM DelegationGrant g WHERE g.id = :id")
    Optional<DelegationGrant> findByIdForUpdate(@Param("id") UUID id);
}
