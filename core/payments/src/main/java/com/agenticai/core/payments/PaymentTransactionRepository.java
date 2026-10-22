package com.agenticai.core.payments;

import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnEditionFeature(EditionFeature.BILLING_PAYMENTS)
public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, UUID> {

    /** Idempotency replay: a repeated tool call resolves to the SAME row. */
    Optional<PaymentTransaction> findByIdempotencyKey(String idempotencyKey);

    /** Webhook/reconciliation lookup - the gateway ref (plink_.../qr_...) stamped at initiation. */
    Optional<PaymentTransaction> findByRazorpayEntityId(String razorpayEntityId);

    List<PaymentTransaction> findByAgentIdOrderByCreatedAtDesc(UUID agentId, Pageable pageable);

    List<PaymentTransaction> findByMandateIdOrderByCreatedAtDesc(UUID mandateId, Pageable pageable);

    /**
     * Offset-stable variants (id breaks createdAt ties) for {@code GET /api/payments/transactions}.
     */
    List<PaymentTransaction> findByAgentIdOrderByCreatedAtDescIdDesc(
            UUID agentId, Pageable pageable);

    List<PaymentTransaction> findByMandateIdOrderByCreatedAtDescIdDesc(
            UUID mandateId, Pageable pageable);

    long countByAgentId(UUID agentId);

    long countByMandateId(UUID mandateId);

    /** The reconciliation poller's due set (status AWAITING_PAYMENT, next_poll_at elapsed). */
    List<PaymentTransaction> findByStatusAndNextPollAtLessThanEqual(
            PaymentTransactionStatus status, Instant now);

    /**
     * Row-locked load for every status transition ({@code SELECT ... FOR UPDATE}). The Razorpay
     * webhook and the reconciliation poller can settle the same transaction concurrently; with this
     * lock the second caller blocks until the first commits and then reads the committed (terminal)
     * status, so exactly one of them performs the transition and its side effects (cap release,
     * event, audit). A captured payment can therefore never be overwritten by EXPIRED/FAILED.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from PaymentTransaction t where t.id = :id")
    Optional<PaymentTransaction> findByIdForUpdate(@Param("id") UUID id);
}
