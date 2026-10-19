package com.agenticai.core.payments;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PaymentSigningKeyRepository extends JpaRepository<PaymentSigningKey, java.util.UUID> {

    Optional<PaymentSigningKey> findFirstByStatusOrderByCreatedAtDesc(String status);

    Optional<PaymentSigningKey> findByKeyId(String keyId);
}
