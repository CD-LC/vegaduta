package com.agenticai.core.knowledge;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Review of knowledge chunks an agent saved on a turn that handled untrusted content ({@code trust =
 * 'QUARANTINED'}, migration V677). Quarantined chunks are excluded from every retrieval path; a person
 * either approves them (they become retrievable, keeping their {@code source_taint} as provenance) or
 * rejects them (deleted).
 */
@Service
@Profile("knowledge")
public class KnowledgeQuarantineService {

    /** One quarantined chunk as a reviewer sees it. */
    public record QuarantinedChunk(
            UUID id, UUID collectionId, String source, int chunkIndex, String content, List<String> sourceTaint) {}

    private final JdbcTemplate jdbcTemplate;

    public KnowledgeQuarantineService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Quarantined chunks in one collection (all collections when null), oldest source first. */
    public List<QuarantinedChunk> list(UUID collectionId, int limit) {
        String table = KnowledgeSchemaSupport.qualifiedTableName();
        String sql = "SELECT id, collection_id, source, chunk_index, content, source_taint FROM " + table
                + " WHERE trust = 'QUARANTINED'"
                + (collectionId != null ? " AND collection_id = ?" : "")
                + " ORDER BY source, chunk_index LIMIT ?";
        Object[] args = collectionId != null
                ? new Object[] {collectionId, Math.max(1, limit)}
                : new Object[] {Math.max(1, limit)};
        return jdbcTemplate.query(
                sql,
                (rs, i) -> new QuarantinedChunk(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("collection_id")),
                        rs.getString("source"),
                        rs.getInt("chunk_index"),
                        rs.getString("content"),
                        parseSources(rs.getString("source_taint"))),
                args);
    }

    /** Makes one quarantined chunk retrievable. False when it does not exist or is already trusted. */
    public boolean approve(UUID chunkId) {
        String table = KnowledgeSchemaSupport.qualifiedTableName();
        return jdbcTemplate.update(
                        "UPDATE " + table + " SET trust = 'TRUSTED' WHERE id = ? AND trust = 'QUARANTINED'", chunkId)
                > 0;
    }

    /** Approves every quarantined chunk of the same saved note (same collection and source). */
    public int approveNote(UUID collectionId, String source) {
        String table = KnowledgeSchemaSupport.qualifiedTableName();
        return jdbcTemplate.update(
                "UPDATE " + table + " SET trust = 'TRUSTED' WHERE collection_id = ? AND source = ?"
                        + " AND trust = 'QUARANTINED'",
                collectionId,
                source);
    }

    /** Deletes one quarantined chunk. Never touches a trusted chunk. */
    public boolean reject(UUID chunkId) {
        String table = KnowledgeSchemaSupport.qualifiedTableName();
        return jdbcTemplate.update("DELETE FROM " + table + " WHERE id = ? AND trust = 'QUARANTINED'", chunkId) > 0;
    }

    /** Deletes every quarantined chunk of the same saved note (same collection and source). */
    public int rejectNote(UUID collectionId, String source) {
        String table = KnowledgeSchemaSupport.qualifiedTableName();
        return jdbcTemplate.update(
                "DELETE FROM " + table + " WHERE collection_id = ? AND source = ? AND trust = 'QUARANTINED'",
                collectionId,
                source);
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
