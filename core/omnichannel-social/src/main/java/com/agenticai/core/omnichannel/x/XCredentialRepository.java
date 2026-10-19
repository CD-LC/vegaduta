package com.agenticai.core.omnichannel.x;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface XCredentialRepository extends JpaRepository<XCredential, UUID> {

    Optional<XCredential> findFirstByOrderByCreatedAtAsc();

    Optional<XCredential> findFirstByStatusOrderByCreatedAtAsc(XCredentialStatus status);
}
