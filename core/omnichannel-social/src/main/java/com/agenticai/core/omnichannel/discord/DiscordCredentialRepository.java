package com.agenticai.core.omnichannel.discord;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiscordCredentialRepository extends JpaRepository<DiscordCredential, UUID> {

    Optional<DiscordCredential> findFirstByOrderByCreatedAtAsc();
}
