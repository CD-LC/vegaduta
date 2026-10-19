package com.agenticai.core.tenancy.demo;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DemoGeneratedMusicRepository extends JpaRepository<DemoGeneratedMusic, UUID> {

    /** The platform-wide daily budget check in {@link DemoMusicService#isGlobalBudgetAvailable} - every anonymous visitor draws from one shared allowance. */
    long countByCreatedAtAfter(Instant after);
}
