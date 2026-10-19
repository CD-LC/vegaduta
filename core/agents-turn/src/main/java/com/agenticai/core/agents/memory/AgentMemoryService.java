package com.agenticai.core.agents.memory;

import com.agenticai.core.knowledge.SidecarClient;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Long-term, cross-session agent memory - the first capability from
 * Agentic-AI-Platform-Competitive-Analysis-2026.md's "smarter, more dynamic agents" roadmap.
 * Deliberately separate from {@link SessionMemoryService}: that class is Redis-backed conversation
 * *history* for one session (resets when the session ends); this is Postgres-backed, persists
 * indefinitely, and is scoped by {@code (agentId, subjectKey)} rather than an ephemeral sessionId -
 * {@code subjectKey} is whatever stable identity the caller has for the real person on the other
 * end (a Keycloak principal name for the web console, a WhatsApp phone number via {@code
 * ChannelSession.externalId} for omnichannel), so the same person is recognized across many
 * separate conversations, not just reset every time a session expires.
 *
 * <p>Reuses the exact same embedding/pgvector pattern Stage 5/6's knowledge base established
 * ({@link SidecarClient} for embeddings, raw {@link JdbcTemplate} against a schema-qualified table
 * because pgvector's {@code vector} column type has no Hibernate mapping - see {@code
 * VectorSchemaInitializer}'s javadoc) rather than inventing a second way to talk to the same
 * embedding model. Gated behind the "knowledge" profile for the same reason: it's the profile that
 * guarantees a pgvector-enabled Postgres and a running sidecar exist.
 *
 * <p><b>Bounded memory space (universal-tier product decision):</b> each agent is capped at {@value
 * #MAX_MEMORIES_PER_AGENT} stored memories total, across every subject it has ever talked to - not
 * per-subject, the whole bank. {@link #remember} prunes the oldest rows (by {@code created_at})
 * immediately after an insert that pushes an agent over the cap, so the bank behaves like a
 * fixed-size space that keeps only what's been added most recently rather than growing without
 * bound. No de-duplication (the same fact told twice is stored twice - relying on {@code recall}'s
 * topK + distance ordering to surface the most relevant rather than merging near-duplicates), and
 * {@code remember} is entirely agent-initiated (a model decides something is worth saving via the
 * {@code remember_fact} tool) rather than an automatic background extraction pass over every
 * conversation - the latter is real V2 scope (see "procedural memory" / trajectory-informed
 * extraction in the competitive analysis doc's research), not faked here.
 */
@Service
@Profile("knowledge")
public class AgentMemoryService {

    private static final Logger log = LoggerFactory.getLogger(AgentMemoryService.class);
    private static final int DEFAULT_RECALL_TOP_K = 5;

    // Universal-tier product decision: every agent gets the same bounded
    // memory space, regardless of the tenant's AccountType - see
    // TenantLimitsServiceImpl's javadoc for the matching 2-user/5-agent
    // caps this pairs with.
    private static final int MAX_MEMORIES_PER_AGENT = 50;

    private final SidecarClient sidecarClient;
    private final JdbcTemplate jdbcTemplate;

    public AgentMemoryService(SidecarClient sidecarClient, JdbcTemplate jdbcTemplate) {
        this.sidecarClient = sidecarClient;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Persists one fact/preference, embedding it for later semantic recall, then prunes the oldest
     * memories for this agent if this insert pushed it over {@value #MAX_MEMORIES_PER_AGENT}.
     */
    public UUID remember(UUID agentId, String subjectKey, String content) {
        float[] embedding = sidecarClient.embedQuery(content);
        String literal = toVectorLiteral(embedding);
        UUID id = UUID.randomUUID();
        String table = AgentMemorySchemaSupport.qualifiedTableName();

        jdbcTemplate.update(
                "INSERT INTO "
                        + table
                        + " (id, agent_id, subject_key, content, embedding) VALUES (?, ?, ?, ?,"
                        + " ?::public.vector)",
                id,
                agentId,
                subjectKey,
                content,
                literal);
        pruneOverCap(table, agentId);
        log.debug("Remembered fact for agent {} subject '{}': {}", agentId, subjectKey, content);
        return id;
    }

    /**
     * {@link #remember} for a fact the user typed in by hand. Unlike an agent-initiated save it can
     * never evict another subject's row: the caller may hold at most {@code maxOwn} rows on this
     * agent, and when the agent's bank is full room is made from the caller's own oldest rows (see
     * {@link OwnFactAdmission}).
     *
     * @throws MemoryCapExceededException when the caller is at their cap or cannot make room
     */
    public UUID rememberOwn(UUID agentId, String subjectKey, String content, int maxOwn) {
        String table = AgentMemorySchemaSupport.qualifiedTableName();
        Integer own =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM " + table + " WHERE agent_id = ? AND subject_key = ?",
                        Integer.class,
                        agentId,
                        subjectKey);
        Integer total =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM " + table + " WHERE agent_id = ?",
                        Integer.class,
                        agentId);
        int evict =
                OwnFactAdmission.ownRowsToEvict(
                        own == null ? 0 : own,
                        total == null ? 0 : total,
                        maxOwn,
                        MAX_MEMORIES_PER_AGENT);
        if (evict > 0) {
            jdbcTemplate.update(
                    "DELETE FROM "
                            + table
                            + " WHERE id IN ("
                            + "SELECT id FROM "
                            + table
                            + " WHERE agent_id = ? AND subject_key = ? ORDER BY created_at ASC"
                            + " LIMIT ?)",
                    agentId,
                    subjectKey,
                    evict);
        }
        return remember(agentId, subjectKey, content);
    }

    /**
     * Keeps this agent's total memory count at or under {@value #MAX_MEMORIES_PER_AGENT} by
     * deleting its oldest rows (across every subject) once it's over - a bounded "memory space"
     * rather than unbounded accumulation.
     */
    private void pruneOverCap(String table, UUID agentId) {
        Integer count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM " + table + " WHERE agent_id = ?",
                        Integer.class,
                        agentId);
        if (count == null || count <= MAX_MEMORIES_PER_AGENT) {
            return;
        }
        int overflow = count - MAX_MEMORIES_PER_AGENT;
        jdbcTemplate.update(
                "DELETE FROM "
                        + table
                        + " WHERE id IN ("
                        + "SELECT id FROM "
                        + table
                        + " WHERE agent_id = ? ORDER BY created_at ASC LIMIT ?)",
                agentId,
                overflow);
        log.debug(
                "Pruned {} oldest memories for agent {} (over the {}-memory cap)",
                overflow,
                agentId,
                MAX_MEMORIES_PER_AGENT);
    }

    /** Semantic recall, scoped to this agent + subject, ordered by relevance to {@code query}. */
    public List<AgentMemoryEntry> recall(UUID agentId, String subjectKey, String query, int topK) {
        return recall(agentId, subjectKey, query, topK, queryEmbedding(query));
    }

    /** A shareable, lazily computed embedding of {@code query}; see {@link QueryEmbedding}. */
    public QueryEmbedding queryEmbedding(String query) {
        return new QueryEmbedding(query, sidecarClient::embedQuery);
    }

    /**
     * {@link #recall(UUID, String, String, int)} with an embedding the caller may share with other
     * recalls of the same text: the sidecar is asked at most once for all of them.
     */
    public List<AgentMemoryEntry> recall(
            UUID agentId, String subjectKey, String query, int topK, QueryEmbedding embedding) {
        float[] queryVector = embedding.vector();
        String literal = toVectorLiteral(queryVector);
        String table = AgentMemorySchemaSupport.qualifiedTableName();

        // <=> is pgvector's cosine distance operator - exact sequential scan
        // within the (agent_id, subject_key) filter, same "no ANN index yet"
        // call as knowledge_chunks (correctness over premature tuning at MVP
        // data volumes - see idx_agent_memory_agent_subject's comment in
        // V18__agent_memory.sql).
        List<AgentMemoryEntry> results =
                jdbcTemplate.query(
                        "SELECT id, agent_id, subject_key, content, created_at, last_used_at,"
                                + " use_count, embedding OPERATOR(public.<=>) ?::public.vector AS"
                                + " distance FROM "
                                + table
                                + " WHERE agent_id = ? AND subject_key = ? ORDER BY distance ASC"
                                + " LIMIT ?",
                        (rs, rowNum) ->
                                new AgentMemoryEntry(
                                        UUID.fromString(rs.getString("id")),
                                        UUID.fromString(rs.getString("agent_id")),
                                        rs.getString("subject_key"),
                                        rs.getString("content"),
                                        rs.getDouble("distance"),
                                        rs.getTimestamp("created_at").toInstant(),
                                        rs.getTimestamp("last_used_at").toInstant(),
                                        rs.getInt("use_count")),
                        literal,
                        agentId,
                        subjectKey,
                        topK);

        if (!results.isEmpty()) {
            touchUsage(table, results.stream().map(AgentMemoryEntry::id).toList());
        }
        log.debug(
                "Recalled {} memories for agent {} subject '{}' (query='{}')",
                results.size(),
                agentId,
                subjectKey,
                query);
        return results;
    }

    /**
     * Convenience overload - default topK, used by {@link
     * com.agenticai.core.agents.turn.AgentChatService}.
     */
    public List<AgentMemoryEntry> recall(UUID agentId, String subjectKey, String query) {
        return recall(agentId, subjectKey, query, DEFAULT_RECALL_TOP_K);
    }

    /** Default-topK recall that shares {@code embedding}; see {@link QueryEmbedding}. */
    public List<AgentMemoryEntry> recall(
            UUID agentId, String subjectKey, String query, QueryEmbedding embedding) {
        return recall(agentId, subjectKey, query, DEFAULT_RECALL_TOP_K, embedding);
    }

    /**
     * Every memory this agent has for this subject, most recent first - for a future admin/debug UI
     * panel.
     */
    public List<AgentMemoryEntry> listAll(UUID agentId, String subjectKey) {
        String table = AgentMemorySchemaSupport.qualifiedTableName();
        return jdbcTemplate.query(
                "SELECT id, agent_id, subject_key, content, created_at, last_used_at, use_count,"
                        + " 0.0 AS distance FROM "
                        + table
                        + " WHERE agent_id = ? AND subject_key = ? ORDER BY created_at DESC",
                (rs, rowNum) ->
                        new AgentMemoryEntry(
                                UUID.fromString(rs.getString("id")),
                                UUID.fromString(rs.getString("agent_id")),
                                rs.getString("subject_key"),
                                rs.getString("content"),
                                rs.getDouble("distance"),
                                rs.getTimestamp("created_at").toInstant(),
                                rs.getTimestamp("last_used_at").toInstant(),
                                rs.getInt("use_count")),
                agentId,
                subjectKey);
    }

    /**
     * Every private memory any agent holds about this subject, newest first - the signed-in user's
     * own view of what they have been remembered as (gap-analysis item 11). Not agent-scoped on
     * purpose: the owner sees all of it in one place; each row still carries its {@code agentId}.
     */
    public List<AgentMemoryEntry> listAllForSubject(String subjectKey) {
        String table = AgentMemorySchemaSupport.qualifiedTableName();
        return jdbcTemplate.query(
                "SELECT id, agent_id, subject_key, content, created_at, last_used_at, use_count,"
                        + " 0.0 AS distance FROM "
                        + table
                        + " WHERE subject_key = ? ORDER BY created_at DESC",
                (rs, rowNum) ->
                        new AgentMemoryEntry(
                                UUID.fromString(rs.getString("id")),
                                UUID.fromString(rs.getString("agent_id")),
                                rs.getString("subject_key"),
                                rs.getString("content"),
                                rs.getDouble("distance"),
                                rs.getTimestamp("created_at").toInstant(),
                                rs.getTimestamp("last_used_at").toInstant(),
                                rs.getInt("use_count")),
                subjectKey);
    }

    /**
     * Up to {@code limit} distinct people (subject keys) who have a private memory in the current tenant. For
     * background reviews that walk every person; not for request paths.
     */
    public List<String> listSubjects(int limit) {
        String table = AgentMemorySchemaSupport.qualifiedTableName();
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT subject_key FROM " + table + " LIMIT ?",
                String.class,
                Math.max(1, limit));
    }

    /**
     * Re-embeds and rewrites one memory owned by {@code subjectKey}; false when it is not theirs.
     */
    public boolean updateForSubject(UUID memoryId, String subjectKey, String content) {
        String table = AgentMemorySchemaSupport.qualifiedTableName();
        String literal = toVectorLiteral(sidecarClient.embedQuery(content));
        return jdbcTemplate.update(
                        "UPDATE "
                                + table
                                + " SET content = ?, embedding = ?::public.vector WHERE id = ? AND"
                                + " subject_key = ?",
                        content,
                        literal,
                        memoryId,
                        subjectKey)
                > 0;
    }

    /** Deletes one memory only if it belongs to {@code subjectKey}; false when it is not theirs. */
    public boolean forgetForSubject(UUID memoryId, String subjectKey) {
        String table = AgentMemorySchemaSupport.qualifiedTableName();
        return jdbcTemplate.update(
                        "DELETE FROM " + table + " WHERE id = ? AND subject_key = ?",
                        memoryId,
                        subjectKey)
                > 0;
    }

    public void forget(UUID agentId, UUID memoryId) {
        String table = AgentMemorySchemaSupport.qualifiedTableName();
        jdbcTemplate.update(
                "DELETE FROM " + table + " WHERE id = ? AND agent_id = ?", memoryId, agentId);
    }

    /** Deletes one of this agent's memories only if it is about {@code subjectKey}. */
    public boolean forget(UUID agentId, UUID memoryId, String subjectKey) {
        String table = AgentMemorySchemaSupport.qualifiedTableName();
        return jdbcTemplate.update(
                        "DELETE FROM "
                                + table
                                + " WHERE id = ? AND agent_id = ? AND subject_key = ?",
                        memoryId,
                        agentId,
                        subjectKey)
                > 0;
    }

    /**
     * Retention purge (DPDP-oriented controls): deletes up to {@code limit} rows created before
     * {@code cutoff} in the current tenant's schema, oldest first, and returns how many went. The
     * caller loops until it returns less than {@code limit}.
     */
    public int purgeCreatedBefore(java.time.Instant cutoff, int limit) {
        String table = AgentMemorySchemaSupport.qualifiedTableName();
        int deleted =
                jdbcTemplate.update(
                        "DELETE FROM "
                                + table
                                + " WHERE id IN (SELECT id FROM "
                                + table
                                + " WHERE created_at < ? ORDER BY created_at LIMIT ?)",
                        java.sql.Timestamp.from(cutoff),
                        limit);
        log.info("Retention purge removed {} agent-memory rows older than {}", deleted, cutoff);
        return deleted;
    }

    /**
     * #815 Governance follow-up (PII handling review / data-subject erasure) - deletes every memory
     * this TENANT's agents hold about one subject, across ALL agents, not just whichever agent
     * currently handles that subject's conversations. Necessary because {@code subjectKey} scoping
     * is {@code (agentId, subjectKey)}, and {@code ChannelSessionService#rebindAgent} means the
     * same real person may have left memories under more than one agent over time - a single-agent
     * {@link #forget} sweep would silently leave those behind. Returns the number of rows deleted,
     * purely for the caller to log/report - callers should not branch on it.
     */
    public int forgetAllForSubject(String subjectKey) {
        String table = AgentMemorySchemaSupport.qualifiedTableName();
        int deleted =
                jdbcTemplate.update("DELETE FROM " + table + " WHERE subject_key = ?", subjectKey);
        log.info(
                "Erased {} agent-memory rows for subject key (data-subject erasure request)",
                deleted);
        return deleted;
    }

    private void touchUsage(String table, List<UUID> ids) {
        String placeholders = ids.stream().map(id -> "?").collect(Collectors.joining(","));
        Object[] params = new Object[ids.size() + 1];
        params[0] = Timestamp.from(Instant.now());
        for (int i = 0; i < ids.size(); i++) {
            params[i + 1] = ids.get(i);
        }
        jdbcTemplate.update(
                "UPDATE "
                        + table
                        + " SET last_used_at = ?, use_count = use_count + 1 WHERE id IN ("
                        + placeholders
                        + ")",
                params);
    }

    private static String toVectorLiteral(float[] embedding) {
        List<Double> boxed =
                IntStream.range(0, embedding.length)
                        .mapToDouble(i -> embedding[i])
                        .boxed()
                        .toList();
        return boxed.stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]"));
    }
}
