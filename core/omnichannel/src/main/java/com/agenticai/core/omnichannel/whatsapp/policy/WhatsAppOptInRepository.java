package com.agenticai.core.omnichannel.whatsapp.policy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WhatsAppOptInRepository extends JpaRepository<WhatsAppOptIn, UUID> {
    Optional<WhatsAppOptIn> findByPhoneDigits(String phoneDigits);

    /** Newest consent first; {@code id} breaks ties so offset pages never repeat or skip a row. */
    List<WhatsAppOptIn> findAllByOrderByConsentedAtDescIdDesc(Pageable pageable);

    List<WhatsAppOptIn> findByRevokedAtIsNullOrderByConsentedAtDescIdDesc(Pageable pageable);

    long countByRevokedAtIsNull();
}
