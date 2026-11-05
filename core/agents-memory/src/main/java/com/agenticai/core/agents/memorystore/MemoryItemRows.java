package com.agenticai.core.agents.memorystore;

import com.agenticai.core.agents.memory.spi.MemoryKind;
import com.agenticai.core.agents.memory.spi.MemoryTrustLevel;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Row mappers and small SQL value helpers for {@link JdbcMemoryItemStorage}, extracted so the
 * storage class stays under the file-size cap. Package-private.
 */
final class MemoryItemRows {

    private MemoryItemRows() {
        // utility class
    }

    static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    static String vector(float[] embedding) {
        if (embedding == null || embedding.length != MemoryEmbedder.DIMENSION) {
            throw new IllegalArgumentException(
                    "embedding must have " + MemoryEmbedder.DIMENSION + " dimensions");
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (!Float.isFinite(embedding[i])) {
                throw new IllegalArgumentException("embedding has a non-finite value");
            }
            if (i > 0) {
                sb.append(',');
            }
            sb.append(embedding[i]);
        }
        return sb.append(']').toString();
    }

    static String taintJson(List<String> taint) {
        if (taint == null || taint.isEmpty()) {
            return null;
        }
        try {
            return JdbcMemoryItemStorage.MAPPER.writeValueAsString(taint);
        } catch (Exception e) {
            throw new IllegalStateException("cannot serialise source taint", e);
        }
    }

    static List<String> parseTaint(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return JdbcMemoryItemStorage.MAPPER.readValue(json, JdbcMemoryItemStorage.STRINGS);
        } catch (Exception e) {
            // Unreadable taint is treated as tainted: the marker keeps the row from being trusted
            // unreviewed.
            return List.of("unparseable");
        }
    }

    static List<String> subjects(ResultSet rs) throws SQLException {
        Array array = rs.getArray("subject_keys");
        if (array == null) {
            return List.of();
        }
        Object[] values = (Object[]) array.getArray();
        return Arrays.stream(values).map(String::valueOf).collect(Collectors.toList());
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    static MemoryItem map(ResultSet rs) throws SQLException {
        return new MemoryItem(
                rs.getObject("id", UUID.class),
                rs.getObject("agent_id", UUID.class),
                MemoryKind.valueOf(rs.getString("kind")),
                rs.getString("content"),
                MemoryTrustLevel.parse(rs.getString("trust")),
                MemoryItemStatus.parse(rs.getString("status")),
                MemoryWriter.parse(rs.getString("writer")),
                rs.getString("source_type"),
                rs.getString("source_ref"),
                rs.getString("turn_id"),
                parseTaint(rs.getString("source_taint")),
                rs.getBoolean("model_generated"),
                rs.getInt("generation"),
                subjects(rs),
                rs.getInt("version"),
                rs.getObject("supersedes_id", UUID.class),
                instant(rs, "created_at"),
                instant(rs, "expires_at"),
                rs.getString("approved_by"),
                instant(rs, "approved_at"),
                rs.getBoolean("pinned"));
    }

    static MemoryItemStatus nullableStatus(String value) {
        return value == null ? null : MemoryItemStatus.parse(value);
    }
}
