package com.agenticai.core.omnichannel.signal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SignalCredentialRepository extends JpaRepository<SignalCredential, UUID> {

    Optional<SignalCredential> findFirstByOrderByCreatedAtAsc();
}
