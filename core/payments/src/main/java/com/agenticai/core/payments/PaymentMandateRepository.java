package com.agenticai.core.payments;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface PaymentMandateRepository extends JpaRepository<PaymentMandate, UUID> {

    /** The mandate a tool call authorizes against - newest ACTIVE wins. */
    Optional<PaymentMandate> findFirstByAgentIdAndStatusOrderByCreatedAtDesc(UUID agentId, MandateStatus status);

    List<PaymentMandate> findByAgentIdOrderByCreatedAtDesc(UUID agentId);

    List<PaymentMandate> findByStatus(MandateStatus status);

    /**
     * The per-mandate serialization point for cap enforcement (§E1):
     * {@code SELECT ... FOR UPDATE} on the {@code payment_mandate} row itself.
     * Every CHARGE/REVERSAL takes this lock before reading the ledger's
     * running totals, so a concurrent second charge for the same mandate
     * blocks until the first commits and then (READ COMMITTED: a fresh
     * snapshot per statement) sees the first charge's ledger row. Locking the
     * latest LEDGER row instead was not enough: a mandate's first charge has
     * no row to lock, and a charge blocked on row R re-reads R's stale totals
     * after the holder inserts R+1. Returns the mandate id (as text) when the
     * row exists. Must be called inside an open transaction.
     */
    @Query(value = "SELECT CAST(id AS VARCHAR) FROM payment_mandate WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<String> lockForCapUpdate(@Param("id") UUID id);
}
