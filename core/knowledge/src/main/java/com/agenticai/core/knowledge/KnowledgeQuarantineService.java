package com.agenticai.core.knowledge;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Review of knowledge chunks an agent saved on a turn that handled untrusted content ({@code trust
 * = 'QUARANTINED'}, migration V677). Quarantined chunks are excluded from every retrieval path; a
 * person either approves them (they become retrievable, keeping their {@code source_taint} as
 * provenance) or rejects them (deleted).
 */
@Service
@Profile("knowledge")
public class KnowledgeQuarantineService {

    /** One quarantined chunk as a reviewer sees it. */
    public record QuarantinedChunk(
            UUID id,
            UUID collectionId,
            String source,
            int chunkIndex,
            String content,
            List<String> sourceTaint) {}

    private final JdbcTemplate jdbcTemplate;

    public KnowledgeQuarantineService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Quarantined chunks in one collection (all collections when null), oldest source first. */
    public List<QuarantinedChunk> list(UUID collectionId, int limit) {
        String table = KnowledgeSchemaSupport.qualifiedTableName();
        // codeql[java/sql-injection]
        String sql =
                "SELECT id, collection_id, source, chunk_index, content, source_taint FROM "
                        + table
                        + " WHERE trust = 'QUARANTINED'"
                        + (collectionId != null ? " AND collection_id = ?" : "")
                        + " ORDER BY source, chunk_index LIMIT ?";
        Object[] args =
                collectionId != null
                        ? new Object[] {collectionId, Math.max(1, limit)}
                        : new Object[] {Math.max(1, limit)};
        return jdbcTemplate.query(
                // codeql[java/sql-injection]
                sql,
                (rs, i) ->
                        new QuarantinedChunk(
                                UUID.fromString(rs.getString("id")),
                                UUID.fromString(rs.getString("collection_id")),
                                rs.getString("source"),
                                rs.getInt("chunk_index"),
                                rs.getString("content"),
                                parseSources(rs.getString("source_taint"))),
                args);
    }

    /**
     * Makes one quarantined chunk retrievable. False when it does not exist or is already trusted.
     * Its parent passage (V680) is released with it only once no sibling is still held, because the
     * parent carries the siblings' text too.
     */
    @Transactional
    public boolean approve(UUID chunkId) {
        String table = KnowledgeSchemaSupport.qualifiedTableName();
        Affected affected = affectedParents("id = ?", chunkId);
        boolean done =
                jdbcTemplate.update(
                                // codeql[java/sql-injection]
                                "UPDATE "
                                        + table
                                        + " SET trust = 'TRUSTED' WHERE id = ? AND trust = 'QUARANTINED'",
                                chunkId)
                        > 0;
        if (done) {
            settleParents(affected);
        }
        return done;
    }

    /** Approves every quarantined chunk of the same saved note (same collection and source). */
    @Transactional
    public int approveNote(UUID collectionId, String source) {
        String table = KnowledgeSchemaSupport.qualifiedTableName();
        Affected affected =
                affectedParents("collection_id = ? AND source = ?", collectionId, source);
        int n =
                jdbcTemplate.update(
                        // codeql[java/sql-injection]
                        "UPDATE "
                                + table
                                + " SET trust = 'TRUSTED' WHERE collection_id = ? AND source = ?"
                                + " AND trust = 'QUARANTINED'",
                        collectionId,
                        source);
        if (n > 0) {
            settleParents(affected);
        }
        return n;
    }

    /** Deletes one quarantined chunk. Never touches a trusted chunk. */
    @Transactional
    public boolean reject(UUID chunkId) {
        String table = KnowledgeSchemaSupport.qualifiedTableName();
        Affected affected = affectedParents("id = ?", chunkId);
        // codeql[java/sql-injection]
        boolean done =
                jdbcTemplate.update(
                                // codeql[java/sql-injection]
                                "DELETE FROM " + table + " WHERE id = ? AND trust = 'QUARANTINED'",
                                chunkId)
                        > 0;
        if (done) {
            settleParents(affected);
        }
        return done;
    }

    /** Deletes every quarantined chunk of the same saved note (same collection and source). */
    @Transactional
    public int rejectNote(UUID collectionId, String source) {
        String table = KnowledgeSchemaSupport.qualifiedTableName();
        Affected affected =
                affectedParents("collection_id = ? AND source = ?", collectionId, source);
        int n =
                jdbcTemplate.update(
                        // codeql[java/sql-injection]
                        "DELETE FROM "
                                + table
                                + " WHERE collection_id = ? AND source = ? AND trust = 'QUARANTINED'",
                        collectionId,
                        source);
        if (n > 0) {
            settleParents(affected);
        }
        return n;
    }

    /**
     * The parent passages the quarantined chunks matching a filter point to (V432
     * metadata.parent_id).
     */
    private record Affected(UUID collectionId, List<String> parentIds) {}

    private Affected affectedParents(String where, Object... args) {
        String table = KnowledgeSchemaSupport.qualifiedTableName();
        List<UUID> collections = new ArrayList<>();
        List<String> parents = new ArrayList<>();
        jdbcTemplate.query(
                // codeql[java/sql-injection]
                "SELECT collection_id, metadata->>'parent_id' AS parent_id FROM "
                        + table
                        + " WHERE "
                        + where
                        + " AND trust = 'QUARANTINED'",
                rs -> {
                    collections.add(UUID.fromString(rs.getString("collection_id")));
                    String parentId = rs.getString("parent_id");
                    if (parentId != null && !parents.contains(parentId)) {
                        parents.add(parentId);
                    }
                },
                args);
        return new Affected(collections.isEmpty() ? null : collections.get(0), parents);
    }

    /**
     * Brings the QUARANTINED parents of just-reviewed chunks in line with their children (G7): a
     * parent with a chunk still held stays held; one whose remaining children are all approved
     * becomes TRUSTED; one with no child left (all rejected) is deleted. Fails open on a schema
     * that has not applied V680: nothing to settle.
     */
    private void settleParents(Affected affected) {
        if (affected.collectionId() == null || affected.parentIds().isEmpty()) {
            return;
        }
        String chunks = KnowledgeSchemaSupport.qualifiedTableName();
        String parents = KnowledgeSchemaSupport.qualifiedTableName("knowledge_parent_chunk");
        String ids = affected.parentIds().stream().collect(Collectors.joining(",", "{", "}"));
        try {
            jdbcTemplate.update(
                    // codeql[java/sql-injection]
                    "UPDATE "
                            + parents
                            + " p SET trust = 'TRUSTED' WHERE p.collection_id = ? AND p.trust = 'QUARANTINED'"
                            + " AND p.id::text = ANY(?::text[])"
                            + " AND NOT EXISTS (SELECT 1 FROM "
                            + chunks
                            + " c WHERE c.collection_id = p.collection_id"
                            + " AND c.trust = 'QUARANTINED' AND c.metadata->>'parent_id' = p.id::text)"
                            + " AND EXISTS (SELECT 1 FROM "
                            + chunks
                            + " c WHERE c.collection_id = p.collection_id"
                            + " AND c.metadata->>'parent_id' = p.id::text)",
                    affected.collectionId(),
                    ids);
            jdbcTemplate.update(
                    // codeql[java/sql-injection]
                    "DELETE FROM "
                            + parents
                            + " p WHERE p.collection_id = ? AND p.trust = 'QUARANTINED'"
                            + " AND p.id::text = ANY(?::text[])"
                            + " AND NOT EXISTS (SELECT 1 FROM "
                            + chunks
                            + " c WHERE c.collection_id = p.collection_id"
                            + " AND c.metadata->>'parent_id' = p.id::text)",
                    affected.collectionId(),
                    ids);
        } catch (DataAccessException e) {
            // pre-V680 schema: no parent trust to settle
        }
    }

    private static List<String> parseSources(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return JsonMappers.plain().readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
