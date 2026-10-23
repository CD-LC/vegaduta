package com.agenticai.core.payments;

import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnEditionFeature(EditionFeature.BILLING_PAYMENTS)
public interface PaymentSigningKeyRepository
        extends JpaRepository<PaymentSigningKey, java.util.UUID> {

    Optional<PaymentSigningKey> findFirstByStatusOrderByCreatedAtDesc(String status);

    Optional<PaymentSigningKey> findByKeyId(String keyId);
}
