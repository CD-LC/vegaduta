package com.agenticai.core.agents.memory;

import java.sql.Timestamp;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * Row-level SQL helpers behind {@link TenantMemoryService}: the shared-fact row mapper and the
 * approve, graph-sync, prune and usage statements. Stateless; the service passes its {@link
 * JdbcTemplate} and settings in, so behaviour is exactly that of the formerly inlined code.
 */
final class TenantMemoryFactRows {

    private static final Logger log = LoggerFactory.getLogger(TenantMemoryService.class);

    private TenantMemoryFactRows() {}

    /** Maps {@code id, subject_key, content, distance, created_by_agent_id, ...} to an entry. */
    static RowMapper<TenantMemoryEntry> entryMapper() {
        return (rs, rowNum) ->
                new TenantMemoryEntry(
                        UUID.fromString(rs.getString("id")),
                        rs.getString("subject_key"),
                        rs.getString("content"),
                        rs.getDouble("distance"),
                        rs.getString("created_by_agent_id") == null
                                ? null
                                : UUID.fromString(rs.getString("created_by_agent_id")),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("last_used_at").toInstant(),
                        rs.getInt("use_count"),
                        MemoryTrust.parse(rs.getString("trust")),
                        MemoryProvenance.parseSources(rs.getString("source_taint")),
                        AgentMemoryRows.supersededAt(rs));
    }

    /** QUARANTINED to TRUSTED for one row; {@code subjectKey} null = any subject. */
    static boolean approve(JdbcTemplate jdbc, String table, UUID memoryId, String subjectKey) {
        return subjectKey == null
                ? jdbc.update(
                                "UPDATE " + table + " SET trust = 'TRUSTED' WHERE id = ?"
                                        + " AND trust = 'QUARANTINED'",
                                memoryId)
                        > 0
                : jdbc.update(
                                "UPDATE " + table + " SET trust = 'TRUSTED' WHERE id = ? AND subject_key = ?"
                                        + " AND trust = 'QUARANTINED'",
                                memoryId,
                                subjectKey)
                        > 0;
    }

    /** After an approval has committed; best-effort and fail-open, never changes the approval's result. */
    static void syncApprovedToGraph(
            JdbcTemplate jdbc, ApprovedFactGraphSync sync, String table, UUID memoryId) {
        if (sync == null) {
            return;
        }
        try {
            jdbc.query(
                    "SELECT subject_key, content, created_by_agent_id FROM " + table + " WHERE id = ?",
                    rs -> {
                        sync.sharedFactApproved(
                                rs.getObject("created_by_agent_id", UUID.class),
                                rs.getString("subject_key"),
                                memoryId,
                                rs.getString("content"));
                    },
                    memoryId);
        } catch (RuntimeException e) {
            log.warn("Could not offer approved shared memory {} to the graph; the approval stands", memoryId, e);
        }
    }

    /** Deletes the overflow beyond {@code cap}; memory blocks are neither counted nor pruned. */
    static void pruneOverCap(JdbcTemplate jdbc, String table, int cap) {
        // User-visible memory blocks (subject_key contains '#mem:') are neither counted nor pruned.
        Integer count =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM "
                                + table
                                + " WHERE position('"
                                + MemoryBlocks.KEY_MARKER
                                + "' in subject_key) = 0",
                        Integer.class);
        if (count == null || count <= cap) {
            return;
        }
        int overflow = count - cap;
        jdbc.update(
                "DELETE FROM "
                        + table
                        + " WHERE id IN ("
                        + "SELECT id FROM "
                        + table
                        + " WHERE position('"
                        + MemoryBlocks.KEY_MARKER
                        + "' in subject_key) = 0 ORDER BY (superseded_by IS NOT NULL) DESC,"
                        + " (trust = 'QUARANTINED') DESC,"
                        + " last_used_at ASC, use_count ASC, created_at ASC LIMIT ?)",
                overflow);
        log.debug(
                "Pruned {} oldest shared memories (over the {}-memory tenant-wide cap)",
                overflow,
                cap);
    }

    /** Bumps {@code last_used_at} and {@code use_count} for recalled rows. */
    static void touchUsage(JdbcTemplate jdbc, String table, Timestamp now, List<UUID> ids) {
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        Object[] params = new Object[ids.size() + 1];
        params[0] = now;
        for (int i = 0; i < ids.size(); i++) {
            params[i + 1] = ids.get(i);
        }
        jdbc.update(
                "UPDATE "
                        + table
                        + " SET last_used_at = ?, use_count = use_count + 1 WHERE id IN ("
                        + placeholders
                        + ")",
                params);
    }
}
