package com.agenticai.core.agents.tools.x402;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface X402PaymentRepository extends JpaRepository<X402PaymentRecord, UUID> {

    /** The replay guard - a nonce (EIP-3009 or delegation jti:call_nonce) settles once. */
    Optional<X402PaymentRecord> findByNonce(String nonce);

    List<X402PaymentRecord> findByOfferIdOrderByCreatedAtDesc(UUID offerId, Pageable pageable);
}
