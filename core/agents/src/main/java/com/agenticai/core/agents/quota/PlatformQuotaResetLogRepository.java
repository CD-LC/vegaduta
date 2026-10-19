package com.agenticai.core.agents.quota;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlatformQuotaResetLogRepository extends JpaRepository<PlatformQuotaResetLog, UUID> {

    /** Most recent reset row for a job - used at startup to detect a missed UTC-midnight boundary. */
    Optional<PlatformQuotaResetLog> findTopByJobNameOrderByResetDateDesc(String jobName);
}
