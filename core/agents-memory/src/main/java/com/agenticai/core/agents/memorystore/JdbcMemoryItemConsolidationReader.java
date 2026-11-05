package com.agenticai.core.agents.memorystore;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

/** Raw-JDBC {@link MemoryItemConsolidationReader} over the V681 tables; SELECT statements only. */
public class JdbcMemoryItemConsolidationReader implements MemoryItemConsolidationReader {

    private final ObjectProvider<JdbcTemplate> jdbcProvider;

    public JdbcMemoryItemConsolidationReader(ObjectProvider<JdbcTemplate> jdbcProvider) {
        this.jdbcProvider = jdbcProvider;
    }

    private JdbcTemplate jdbc() {
        JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        if (jdbc == null) {
            throw new IllegalStateException("no JDBC access for the memory store");
        }
        return jdbc;
    }

    @Override
    public List<UUID> agentIds(int limit) {
        return jdbc().query(
                        "SELECT DISTINCT agent_id FROM " + JdbcMemoryItemStorage.schema() + ".memory_item"
                                + " WHERE status = 'ACTIVE' AND trust = 'TRUSTED' LIMIT ?",
                        (rs, n) -> rs.getObject("agent_id", UUID.class),
                        Math.max(1, limit));
    }

    @Override
    public List<Row> activeItems(UUID agentId, int limit) {
        return jdbc().query(
                        "SELECT " + JdbcMemoryItemStorage.COLUMNS + ", importance, pinned, use_count, last_used_at FROM "
                                + JdbcMemoryItemStorage.schema() + ".memory_item"
                                + " WHERE agent_id = ? AND status = 'ACTIVE' AND trust = 'TRUSTED'"
                                // An item already forgotten (tombstoned) is not planned again the next night.
                                + new MemoryTombstoneGuard(true, jdbcProvider)
                                        .excludeItems(JdbcMemoryItemStorage.schema() + ".memory_item")
                                + " ORDER BY created_at ASC, id LIMIT ?",
                        (rs, n) -> {
                            java.sql.Timestamp used = rs.getTimestamp("last_used_at");
                            Instant lastUsed = used == null ? null : used.toInstant();
                            return new Row(
                                    JdbcMemoryItemStorage.map(rs),
                                    rs.getDouble("importance"),
                                    rs.getBoolean("pinned"),
                                    rs.getInt("use_count"),
                                    lastUsed);
                        },
                        agentId,
                        Math.max(1, limit));
    }

    @Override
    public Set<UUID> sourcesOfPendingProposals(UUID agentId) {
        String schema = JdbcMemoryItemStorage.schema();
        List<String> keys = jdbc().queryForList(
                        "SELECT DISTINCT s.source_key FROM " + schema + ".memory_item_source s JOIN " + schema
                                + ".memory_item i ON i.id = s.item_id WHERE i.agent_id = ? AND i.status = 'PENDING'"
                                + " AND s.source_table = 'memory_item' AND i.source_type IN ('"
                                + MemoryItem.SOURCE_CONSOLIDATION_MERGE + "', '" + MemoryItem.SOURCE_CONSOLIDATION_SUMMARY
                                + "')",
                        String.class,
                        agentId);
        Set<UUID> out = new HashSet<>();
        for (String key : keys) {
            try {
                out.add(UUID.fromString(key));
            } catch (IllegalArgumentException ignored) {
                // a lineage key that is not a uuid is not a memory_item id
            }
        }
        return out;
    }
}
