package com.agenticai.core.payments.wallet;

import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnEditionFeature(EditionFeature.BILLING_PAYMENTS)
public interface WalletLedgerRepository extends JpaRepository<WalletLedgerEntry, Long> {

    /** Idempotent replay lookup - the UNIQUE constraint makes this the fold-in point. */
    Optional<WalletLedgerEntry> findByIdempotencyKey(String idempotencyKey);

    List<WalletLedgerEntry> findByWalletIdOrderByIdDesc(UUID walletId, Pageable pageable);

    long countByWalletId(UUID walletId);
}
