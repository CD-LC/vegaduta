package com.agenticai.core.omnichannel.imessage;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImessageCredentialRepository extends JpaRepository<ImessageCredential, UUID> {

    Optional<ImessageCredential> findFirstByOrderByCreatedAtAsc();
}
