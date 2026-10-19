package com.agenticai.core.agents.memory;

import com.agenticai.core.tenancy.TenantSchemaResolver;

/**
 * Same reasoning and pattern as knowledge's {@code KnowledgeSchemaSupport}
 * (not reused directly - that class is package-private to
 * {@code com.agenticai.core.knowledge}): {@link AgentMemoryService} runs raw
 * JDBC against {@code agent_memory} instead of going through Hibernate
 * (pgvector's {@code vector} column type has no Hibernate mapping), so it
 * doesn't get the tenant-schema routing every other table gets for free.
 * This computes the same schema {@link TenantSchemaResolver} resolves for
 * Hibernate and quotes it into a table reference.
 *
 * <p>{@link TenantSchemaResolver#currentSchemaName()} already restricts its
 * output to {@code [a-z0-9_]} (see {@code SchemaNameSanitizer}), so this
 * string is safe to concatenate directly into SQL - quoting here is for
 * correctness (case, reserved words), not injection defense.
 */
final class AgentMemorySchemaSupport {

    private AgentMemorySchemaSupport() {
        // utility class
    }

    static String qualifiedTableName() {
        String schema = TenantSchemaResolver.currentSchemaName();
        return quoteIdentifier(schema) + ".agent_memory";
    }

    private static String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
