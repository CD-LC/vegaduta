package com.agenticai.core.payments;

import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnEditionFeature(EditionFeature.BILLING_PAYMENTS)
public interface MandateLedgerRepository extends JpaRepository<MandateLedgerEntry, Long> {

    /**
     * The cap-enforcement read (§E1): {@code FOR UPDATE} on the mandate's
     * LATEST ledger row so a concurrent second transaction for the same
     * mandate blocks until the first caller's check+insert commits - running
     * totals are locked-and-carried, never recomputed from history. Must be
     * called inside an open transaction.
     */
    @Query(
        value = "SELECT * FROM mandate_ledger WHERE mandate_id = :mandateId ORDER BY id DESC LIMIT 1 FOR UPDATE",
        nativeQuery = true
    )
    Optional<MandateLedgerEntry> findLatestForUpdate(@Param("mandateId") UUID mandateId);

    /** The CHARGE a REVERSAL must mirror - the reversal's amount comes from THIS row, never from whatever happens to be latest. */
    Optional<MandateLedgerEntry> findByTransactionIdAndEntryKind(UUID transactionId, String entryKind);

    /** Non-locking running-total read for the spend-vs-cap UI meter. */
    Optional<MandateLedgerEntry> findFirstByMandateIdOrderByIdDesc(UUID mandateId);
}
