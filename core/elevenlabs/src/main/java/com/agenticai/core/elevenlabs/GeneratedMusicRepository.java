package com.agenticai.core.elevenlabs;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GeneratedMusicRepository extends JpaRepository<GeneratedMusic, UUID> {

    List<GeneratedMusic> findAllByOrderByCreatedAtDesc();

    /** Platform-spend quota ledger - only rows the platform key paid for count against a tenant's cap. */
    long countByBilledToAndCreatedAtAfter(String billedTo, Instant after);
}
