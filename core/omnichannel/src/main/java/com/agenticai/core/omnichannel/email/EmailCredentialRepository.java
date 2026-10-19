package com.agenticai.core.omnichannel.email;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailCredentialRepository extends JpaRepository<EmailCredential, UUID> {

    Optional<EmailCredential> findFirstByOrderByCreatedAtAsc();
}
