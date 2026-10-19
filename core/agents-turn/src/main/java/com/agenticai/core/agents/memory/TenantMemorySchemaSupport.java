package com.agenticai.core.agents.memory;

import com.agenticai.core.tenancy.TenantSchemaResolver;

/**
 * Shared cross-agent memory tier (#847) - the {@code tenant_memory} table's
 * counterpart to {@link AgentMemorySchemaSupport}. Same reasoning: {@link
 * TenantMemoryService} runs raw JDBC against {@code tenant_memory} instead
 * of going through Hibernate (pgvector's {@code vector} column type has no
 * Hibernate mapping), so it needs its own tenant-schema-qualified table
 * reference rather than the routing Hibernate entities get for free.
 */
final class TenantMemorySchemaSupport {

    private TenantMemorySchemaSupport() {
        // utility class
    }

    static String qualifiedTableName() {
        String schema = TenantSchemaResolver.currentSchemaName();
        return quoteIdentifier(schema) + ".tenant_memory";
    }

    private static String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
