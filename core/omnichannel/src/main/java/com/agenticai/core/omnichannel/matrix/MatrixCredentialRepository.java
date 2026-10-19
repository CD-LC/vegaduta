package com.agenticai.core.omnichannel.matrix;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MatrixCredentialRepository extends JpaRepository<MatrixCredential, UUID> {

    Optional<MatrixCredential> findFirstByOrderByCreatedAtAsc();
}
