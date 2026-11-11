package com.agenticai.core.security;

import java.util.Locale;

/**
 * The one definition of "this tenant id names the legacy default schema or a platform/system
 * schema, so it is never a tenant". Shared by {@link TenantContextFilter} (early 403 for JWT,
 * API-key and delegation-token callers) and by {@code TenantSchemaResolver} in core/tenancy (the
 * enforcement chokepoint every tenant query passes through, whoever built the {@link
 * TenantContext}).
 */
public final class TenantSchemaClaims {

    /** The legacy default schema every pre-multitenancy table lived in. */
    public static final String DEFAULT_SCHEMA = "public";

    private TenantSchemaClaims() {
        // utility class
    }

    /**
     * True for a tenant id that is (case-insensitively, after trimming) the legacy {@code public}
     * schema or a reserved platform/system schema. A null id is not a system schema (it is "no
     * tenant", handled by the callers).
     */
    public static boolean isSystemSchemaClaim(String tenantId) {
        if (tenantId == null) {
            return false;
        }
        String n = tenantId.trim().toLowerCase(Locale.ROOT);
        return DEFAULT_SCHEMA.equals(n) || isReservedSchema(n);
    }

    /** A reserved platform/system schema other than {@code public}; input must be lowercase. */
    public static boolean isReservedSchema(String lowerCaseSchema) {
        return "control_plane".equals(lowerCaseSchema)
                || "information_schema".equals(lowerCaseSchema)
                || "ag_catalog".equals(lowerCaseSchema)
                || lowerCaseSchema.startsWith("pg_");
    }
}
