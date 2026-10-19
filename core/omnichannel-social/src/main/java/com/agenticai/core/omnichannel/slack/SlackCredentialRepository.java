package com.agenticai.core.omnichannel.slack;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SlackCredentialRepository extends JpaRepository<SlackCredential, UUID> {

    Optional<SlackCredential> findFirstByOrderByCreatedAtAsc();
}
