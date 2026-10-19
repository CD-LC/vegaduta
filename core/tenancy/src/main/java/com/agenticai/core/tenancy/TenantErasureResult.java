package com.agenticai.core.tenancy;

/**
 * {@link TenantDataErasureService#eraseTenantById}'s return value - the
 * caller (the platform-admin orchestrator in {@code core/platform}) needs
 * {@code schemaName} for the code-intel cleanup call AFTER this call
 * returns: every OTHER {@code CodeIntelClient} call site sends {@code
 * X-Tenant-Id} as the tenant's SCHEMA NAME (that is what {@code
 * TenantContext.getTenantId()} actually holds throughout this codebase, not
 * the {@link Tenant#getId()} UUID) - code-intel's own {@code
 * data/tenants/<tenant_id>/} directories are therefore named by schema, not
 * UUID. The orchestrator must send the same value core has always sent, or
 * it would delete nothing (or, far worse, something at an unrelated path).
 */
public record TenantErasureResult(String schemaName) {
}
