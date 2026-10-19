package com.agenticai.core.omnichannel.teams;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamsCredentialRepository extends JpaRepository<TeamsCredential, UUID> {

    Optional<TeamsCredential> findFirstByOrderByCreatedAtAsc();
}
