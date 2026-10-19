package com.agenticai.core.tenancy;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlatformTenantDeletionLogRepository extends JpaRepository<PlatformTenantDeletionLog, UUID> {
}
