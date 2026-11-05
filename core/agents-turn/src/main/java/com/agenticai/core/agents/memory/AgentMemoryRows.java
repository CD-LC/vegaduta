package com.agenticai.core.agents.memory;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;

/** Row mapping behind {@link AgentMemoryService}: one mapper for recall and both listings. */
final class AgentMemoryRows {

    private AgentMemoryRows() {}

    /**
     * Maps {@code id, agent_id, subject_key, content, distance, created_at, last_used_at,
     * use_count, trust, source_taint}.
     */
    static RowMapper<AgentMemoryEntry> entryMapper() {
        return (rs, rowNum) ->
                new AgentMemoryEntry(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("agent_id")),
                        rs.getString("subject_key"),
                        rs.getString("content"),
                        rs.getDouble("distance"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("last_used_at").toInstant(),
                        rs.getInt("use_count"),
                        MemoryTrust.parse(rs.getString("trust")),
                        MemoryProvenance.parseSources(rs.getString("source_taint")),
                        supersededAt(rs));
    }

    /**
     * {@code superseded_at} when the query selected it (the listings do, recall never returns a
     * superseded row).
     */
    static Instant supersededAt(ResultSet rs) throws SQLException {
        for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
            if ("superseded_at".equalsIgnoreCase(rs.getMetaData().getColumnLabel(i))) {
                return rs.getTimestamp(i) == null ? null : rs.getTimestamp(i).toInstant();
            }
        }
        return null;
    }
}
