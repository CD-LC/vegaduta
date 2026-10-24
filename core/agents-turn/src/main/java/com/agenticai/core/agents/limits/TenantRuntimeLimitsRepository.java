package com.agenticai.core.agents.limits;

import org.springframework.data.jpa.repository.JpaRepository;

/** Tenant-schema store of {@link TenantRuntimeLimitsRow}; the only row has id 1. */
interface TenantRuntimeLimitsRepository extends JpaRepository<TenantRuntimeLimitsRow, Short> {}
