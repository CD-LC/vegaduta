package com.agenticai.core.graphmemory;

import com.agenticai.core.tenancy.MissingTenantContextException;
import com.agenticai.core.tenancy.SchemaNameSanitizer;
import com.agenticai.core.tenancy.TenantSchemaGuard;
import com.agenticai.core.tenancy.TenantSchemaResolver;

/**
 * Computes the current tenant's Apache AGE graph name - {@code
 * <tenant_schema>_kg} - the same derivation {@code V44__graph_memory.sql}
 * uses at migration time ({@code current_schema() || '_kg'}), kept in sync
 * here so {@link GraphMemoryService} always targets the graph that migration
 * actually created for this tenant.
 *
 * <p>Unlike {@code AgentMemorySchemaSupport} (which quotes a schema-qualified
 * table name for ordinary SQL), an AGE graph name is passed as a plain
 * string literal argument to {@code ag_catalog.cypher(...)}, not a SQL
 * identifier - no quoting needed, just the same restricted-to-{@code
 * [a-z0-9_]} name {@link TenantSchemaResolver#currentSchemaName()} already
 * guarantees (see that method's own javadoc / {@code SchemaNameSanitizer}).
 */
final class GraphMemorySchemaSupport {

    private static final String GRAPH_SUFFIX = "_kg";

    private GraphMemorySchemaSupport() {
        // utility class
    }

    /**
     * SEC-001/SEC-017: refuses to derive a graph name from the resolver's
     * no-tenant-context sentinel or from a platform/system schema
     * ({@code control_plane}, {@code ag_catalog}, {@code pg_*}, ...) - graph
     * memory is tenant data, so "no tenant" must fail rather than read or
     * write some shared graph. {@code public} stays allowed: it is the legacy
     * single-tenant / insecure-profile schema and is only ever resolved there
     * or under an explicit platform scope.
     */
    static String currentGraphName() {
        String schema = TenantSchemaResolver.currentSchemaName();
        if (!SchemaNameSanitizer.DEFAULT_SCHEMA.equals(schema) && TenantSchemaGuard.isProtectedSchema(schema)) {
            throw new MissingTenantContextException(
                "Graph memory needs a tenant schema, but the current thread resolved '" + schema
                    + "' - refusing to derive a graph name from it");
        }
        return schema + GRAPH_SUFFIX;
    }
}
