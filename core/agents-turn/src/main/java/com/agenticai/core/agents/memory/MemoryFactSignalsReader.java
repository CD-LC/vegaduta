package com.agenticai.core.agents.memory;

import com.agenticai.core.agents.memorystore.MemoryTombstoneGuard;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Read-only extras about the fact stores for the nightly consolidation (ADR-0028 WP5c): the
 * importance prior, the pin and the authorship flag of each fact (migration V684), and which of a
 * person's facts are already tombstoned. They sit beside {@link AgentMemoryService} and {@link
 * TenantMemoryService} rather than on them (those files are at their size limit and their entry
 * records are used by recall); nothing here writes. Needs the tenant context on the calling thread.
 */
@Component
public class MemoryFactSignalsReader {

    /** The columns V684 added to a fact row. */
    public record Signals(double importance, boolean pinned, boolean personAuthored) {

        /** What a row carries when nothing was recorded: neutral prior, not pinned, not typed by a person. */
        public static final Signals NEUTRAL = new Signals(0.5, false, false);
    }

    private final ObjectProvider<JdbcTemplate> jdbc;

    public MemoryFactSignalsReader(ObjectProvider<JdbcTemplate> jdbc) {
        this.jdbc = jdbc;
    }

    /** Signals of the subject's rows in {@code agent_memory}, by row id. */
    public Map<UUID, Signals> agentSignals(String subjectKey) {
        return signals(AgentMemorySchemaSupport.qualifiedTableName(), subjectKey);
    }

    /** Signals of the subject's rows in {@code tenant_memory}, by row id. */
    public Map<UUID, Signals> sharedSignals(String subjectKey) {
        return signals(TenantMemorySchemaSupport.qualifiedTableName(), subjectKey);
    }

    private Map<UUID, Signals> signals(String table, String subjectKey) {
        Map<UUID, Signals> out = new HashMap<>();
        JdbcTemplate j = jdbc.getIfAvailable();
        if (j == null) {
            return out;
        }
        j.query(
                "SELECT id, importance, pinned, person_authored FROM " + table + " WHERE subject_key = ?",
                rs -> {
                    out.put(
                            UUID.fromString(rs.getString("id")),
                            new Signals(rs.getDouble("importance"), rs.getBoolean("pinned"), rs.getBoolean("person_authored")));
                },
                subjectKey);
        return out;
    }

    /**
     * Keys of the subject's tombstoned facts, as {@link #tombstoneKey}: a fact already forgotten is not
     * planned again the next night (its row stays, only recall hides it).
     */
    public Set<String> forgottenKeys(String subjectKey) {
        Set<String> out = new HashSet<>();
        JdbcTemplate j = jdbc.getIfAvailable();
        if (j == null) {
            return out;
        }
        String schema = com.agenticai.core.tenancy.TenantSchemaResolver.currentSchemaName();
        j.query(
                "SELECT memory_kind, agent_id, content_sha256 FROM \"" + schema.replace("\"", "\"\"")
                        + "\".memory_tombstone WHERE subject_key = ? AND memory_kind IN ('agent', 'shared')",
                rs -> {
                    String kind = rs.getString("memory_kind");
                    String agent = rs.getString("agent_id");
                    boolean agentTier = MemoryTombstoneGuard.KIND_AGENT.equals(kind);
                    out.add(kind + "|" + (!agentTier || agent == null ? "" : agent) + "|" + rs.getString("content_sha256"));
                },
                subjectKey);
        return out;
    }

    /** The key {@link #forgottenKeys} uses for a fact: tier, owning agent (agent tier only) and content hash. */
    public static String tombstoneKey(boolean shared, UUID agentId, String content) {
        return (shared ? MemoryTombstoneGuard.KIND_SHARED : MemoryTombstoneGuard.KIND_AGENT) + "|"
                + (shared || agentId == null ? "" : agentId.toString()) + "|" + MemoryTombstoneGuard.sha256(content);
    }
}
