package com.agenticai.core.tenancy.demo;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DemoGeneratedVideoRepository extends JpaRepository<DemoGeneratedVideo, UUID> {

    long countBySessionId(UUID sessionId);

    /** The platform-wide daily $ budget check in {@link DemoVideoService#isGlobalBudgetAvailable} - every anonymous visitor draws from one shared allowance, since video is billed per second across ALL sandboxes, not per-tenant. */
    long countByCreatedAtAfter(Instant after);
}
