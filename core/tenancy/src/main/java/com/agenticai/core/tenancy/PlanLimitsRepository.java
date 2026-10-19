package com.agenticai.core.tenancy;

import com.agenticai.core.security.TenantPlan;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data JPA repository over {@link PlanLimits} - three rows total (one per {@link TenantPlan}), PK-lookup only. */
public interface PlanLimitsRepository extends JpaRepository<PlanLimits, TenantPlan> {
}
