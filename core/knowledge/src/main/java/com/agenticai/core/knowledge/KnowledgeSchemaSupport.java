package com.agenticai.core.knowledge;

import com.agenticai.core.tenancy.TenantSchemaResolver;

/**
 * Shared helper for {@link KnowledgeIngestionService} and
 * {@link KnowledgeSearchService}: both run raw JDBC against
 * {@code knowledge_chunks} instead of going through Hibernate (pgvector's
 * {@code vector} column type has no Hibernate mapping, see
 * {@link VectorSchemaInitializer}'s javadoc), so neither gets the
 * tenant-schema routing every other table gets for free. This computes the
 * same schema {@link TenantSchemaResolver} resolves for Hibernate and
 * quotes it into a table reference, so both call sites use one identical,
 * correctly-quoted answer instead of each rebuilding the string themselves.
 *
 * <p>{@link TenantSchemaResolver#currentSchemaName()} already restricts its
 * output to {@code [a-z0-9_]} (see {@code SchemaNameSanitizer}), so this
 * string is safe to concatenate directly into SQL - quoting here is for
 * correctness (case, reserved words), not injection defense, but it is
 * still quoted rather than trusted implicitly, same posture as
 * {@code TenantProvisioningService.quoteIdentifier}.
 */
public final class KnowledgeSchemaSupport {

    private KnowledgeSchemaSupport() {
        // utility class
    }

    static String qualifiedTableName() {
        return qualifiedTableName("knowledge_chunks");
    }

    /**
     * RAG audit fix (2026-07-29) - generalized so raw-JDBC statements that
     * need to reference OTHER tables in the same tenant schema (the
     * keyword-search join onto {@code knowledge_collection} for its
     * per-collection {@code text_search_config}) reuse the exact same
     * schema-resolution-and-quoting instead of rebuilding it. Made public
     * (2026-08-06, face recognition) so {@code core/agents}' own raw-JDBC
     * table ({@code knowledge_face_enrollment}, same pgvector-has-no-
     * Hibernate-mapping reasoning) can reuse it too - {@code core/agents}
     * already depends on {@code core/knowledge} (never the reverse), so this
     * is a one-way widening, not a new coupling.
     */
    public static String qualifiedTableName(String tableName) {
        String schema = TenantSchemaResolver.currentSchemaName();
        return quoteIdentifier(schema) + "." + tableName;
    }

    private static String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
