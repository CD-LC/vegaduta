package com.agenticai.core.omnichannel.telegram;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TelegramCredentialRepository extends JpaRepository<TelegramCredential, UUID> {

    Optional<TelegramCredential> findFirstByOrderByCreatedAtAsc();
}
