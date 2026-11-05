package com.agenticai.core.agents.memorystore;

import com.agenticai.core.tenancy.TenantSchemaResolver;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The read side of {@code memory_tombstone} (V683): what makes "forgotten" mean forgotten. A
 * tombstone is a hash-only marker the consolidation leaves for a fact it forgot (the fact row is
 * not deleted), so two things must consult it:
 *
 * <ul>
 *   <li><b>Recall</b>: every recall query appends {@link #excludeAgentFacts}, {@link
 *       #excludeSharedFacts} or {@link #excludeItems}, a {@code NOT EXISTS} over the indexed
 *       {@code (subject_key, content_sha256)} pair. It is part of the recall statement itself, so
 *       it costs no extra database round trip; with the guard inactive the fragment is the empty
 *       string and the statement is byte-for-byte what it was.
 *   <li><b>Resurrection</b>: {@link #isForgotten} lets an importer or the agent's {@code
 *       remember_fact} tools refuse to re-add a fact whose exact text was forgotten, and {@link
 *       #reconfirm} lets a person who types it again on purpose clear the marker.
 * </ul>
 *
 * <p>Active only when {@code app.memory.tombstone.enforce} is true; unset, it follows {@code
 * app.memory.consolidation.enabled} (the only writer of tombstones), so a deployment with every
 * memory flag off runs exactly the queries it ran before. The hash is SHA-256 of the exact UTF-8
 * text, lowercase hex, the same as {@code MemoryTombstoneWriter}; a reworded duplicate is a
 * different fact and is not caught. A tombstone for a {@code memory_item} row uses the subject key
 * {@code item:<agentId>} and kind {@code item}.
 */
public final class MemoryTombstoneGuard {

    public static final String KIND_AGENT = "agent";
    public static final String KIND_SHARED = "shared";
    public static final String KIND_ITEM = "item";

    /** SQL for the lowercase-hex SHA-256 of a text column; equals {@link #sha256}. */
    private static final String HASH = "encode(sha256(convert_to(%s, 'UTF8')), 'hex')";

    private final boolean active;
    private final ObjectProvider<JdbcTemplate> jdbc;

    public MemoryTombstoneGuard(boolean active, ObjectProvider<JdbcTemplate> jdbc) {
        this.active = active;
        this.jdbc = jdbc;
    }

    /** An inactive guard: no SQL fragments, no lookups. */
    public static MemoryTombstoneGuard inactive() {
        return new MemoryTombstoneGuard(false, null);
    }

    public boolean active() {
        return active;
    }

    /** Subject key a {@code memory_item} tombstone is stored under. */
    public static String itemSubject(UUID agentId) {
        return "item:" + agentId;
    }

    private static String table() {
        String schema = TenantSchemaResolver.currentSchemaName();
        return "\"" + schema.replace("\"", "\"\"") + "\".memory_tombstone";
    }

    /** {@code AND NOT EXISTS (...)} for a query over the agent fact table {@code factTable}; empty when inactive. */
    public String excludeAgentFacts(String factTable) {
        if (!active) {
            return "";
        }
        return " AND NOT EXISTS (SELECT 1 FROM " + table() + " t WHERE t.memory_kind = '" + KIND_AGENT
                + "' AND t.subject_key = " + factTable + ".subject_key AND t.agent_id = " + factTable
                + ".agent_id AND t.content_sha256 = " + HASH.formatted(factTable + ".content") + ")";
    }

    /** As {@link #excludeAgentFacts} for the tenant-shared fact table. */
    public String excludeSharedFacts(String factTable) {
        if (!active) {
            return "";
        }
        return " AND NOT EXISTS (SELECT 1 FROM " + table() + " t WHERE t.memory_kind = '" + KIND_SHARED
                + "' AND t.subject_key = " + factTable + ".subject_key AND t.content_sha256 = "
                + HASH.formatted(factTable + ".content") + ")";
    }

    /** As {@link #excludeAgentFacts} for {@code memory_item} (keyed by agent, not by subject). */
    public String excludeItems(String itemTable) {
        if (!active) {
            return "";
        }
        return " AND NOT EXISTS (SELECT 1 FROM " + table() + " t WHERE t.memory_kind = '" + KIND_ITEM
                + "' AND t.subject_key = 'item:' || " + itemTable + ".agent_id::text AND t.content_sha256 = "
                + HASH.formatted(itemTable + ".content") + ")";
    }

    /**
     * Whether {@code content} was forgotten for this subject (and agent, for the agent tier). One
     * indexed lookup; false when the guard is inactive or there is no database. Fails open on a
     * database error: a write path must not break because a lookup did, and recall is still filtered.
     */
    public boolean isForgotten(String kind, String subjectKey, UUID agentId, String content) {
        if (!active || content == null || subjectKey == null) {
            return false;
        }
        JdbcTemplate j = jdbc == null ? null : jdbc.getIfAvailable();
        if (j == null) {
            return false;
        }
        List<Object> args = new ArrayList<>();
        args.add(subjectKey);
        args.add(kind);
        args.add(sha256(content));
        String agent = "";
        if (KIND_AGENT.equals(kind) && agentId != null) {
            agent = " AND agent_id = ?";
            args.add(agentId);
        }
        try {
            Boolean found = j.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM " + table()
                            + " WHERE subject_key = ? AND memory_kind = ? AND content_sha256 = ?" + agent + ")",
                    Boolean.class,
                    args.toArray());
            return Boolean.TRUE.equals(found);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * A person re-adds a forgotten fact on purpose: clears its tombstones so it is recalled again.
     * Only the human paths (the Memory page, an approved lesson) call this. Returns the rows cleared.
     */
    public int reconfirm(String kind, String subjectKey, UUID agentId, String content) {
        if (!active || content == null || subjectKey == null) {
            return 0;
        }
        JdbcTemplate j = jdbc == null ? null : jdbc.getIfAvailable();
        if (j == null) {
            return 0;
        }
        List<Object> args = new ArrayList<>();
        args.add(subjectKey);
        args.add(kind);
        args.add(sha256(content));
        String agent = "";
        if (KIND_AGENT.equals(kind) && agentId != null) {
            agent = " AND agent_id = ?";
            args.add(agentId);
        }
        return j.update(
                "DELETE FROM " + table() + " WHERE subject_key = ? AND memory_kind = ? AND content_sha256 = ?" + agent,
                args.toArray());
    }

    /** SHA-256 of the exact UTF-8 text, lowercase hex (what {@code memory_tombstone.content_sha256} holds). */
    public static String sha256(String content) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
