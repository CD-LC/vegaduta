package com.agenticai.core.agents.memory;

import com.agenticai.core.knowledge.SidecarClient;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.logging.LogSafe;
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
 * <p><b>Bounded memory space (universal-tier product decision):</b> each agent is capped at {@code
 * app.memory.limits.agent-max-memories} (default 50) stored memories total, across every subject it has ever talked to - not
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

    private final SidecarClient sidecarClient;
    private final JdbcTemplate jdbcTemplate;

    public AgentMemoryService(SidecarClient sidecarClient, JdbcTemplate jdbcTemplate) {
        this.sidecarClient = sidecarClient;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * {@code app.memory.limits.*}: the per-agent cap ({@code agent-max-memories}) and the recall top-k ({@code agent-recall-top-k}). A hand-built instance keeps the shipped
     * defaults, which is also what Spring supplies when the properties bean is absent.
     */
    private volatile MemoryLimitsProperties limits = new MemoryLimitsProperties();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setLimits(MemoryLimitsProperties limits) {
        this.limits = limits == null ? new MemoryLimitsProperties() : limits;
    }

    private volatile java.time.Clock clock = java.time.Clock.systemUTC();

    /** The memory clock ({@code memoryClock} bean, system UTC by default); tests pin it to control time. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void useClock(
            @org.springframework.beans.factory.annotation.Qualifier("memoryClock") java.time.Clock clock) {
        this.clock = clock == null ? java.time.Clock.systemUTC() : clock;
    }

    private volatile com.agenticai.core.agents.memorystore.MemoryTombstoneGuard tombstones =
            com.agenticai.core.agents.memorystore.MemoryTombstoneGuard.inactive();

    /** Makes a forgotten (tombstoned) fact stay forgotten: recall leaves it out and {@link #remember} refuses it. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setTombstoneGuard(com.agenticai.core.agents.memorystore.MemoryTombstoneGuard guard) {
        this.tombstones = guard == null ? com.agenticai.core.agents.memorystore.MemoryTombstoneGuard.inactive() : guard;
    }

    private volatile com.agenticai.core.settings.SettingsResolver settings;

    /**
     * The settings catalog, so a tenant's or an agent's {@code memory.recall.agentTopK} and {@code
     * memory.agent.maxMemories} apply to that agent's memory. Absent (hand-built instances): {@code
     * app.memory.limits.*}. Memory is read and written on the turn's own thread, where the tenant is set.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSettingsResolver(com.agenticai.core.settings.SettingsResolver resolver) {
        this.settings = resolver;
    }

    /**
     * {@code app.memory.limits.recall-enabled}: the platform's value of the {@code memory.recall.enabled} switch
     * (default on), asked as "off" so a mocked service answers the default (on). Read by the chat turn as the answer when no tenant or agent override exists.
     */
    public boolean recallSwitchedOffByPlatform() {
        return !limits.isRecallEnabled();
    }

    /** {@code app.memory.limits.write-enabled} asked as "off" (a mocked service answers the default, on). */
    public boolean writesSwitchedOffByPlatform() {
        return !limits.isWriteEnabled();
    }

    /** The settings-catalog key of the write switch (docs/settings.md). */
    public static final String WRITE_ENABLED_KEY = "memory.write.enabled";

    /**
     * {@code memory.write.enabled} for {@code agentId}, asked as "off" so a mocked service answers the default (on):
     * the agent's, else the workspace's, else the platform's ({@code app.memory.limits.write-enabled}). A failing
     * lookup leaves writes as the platform value has them.
     */
    public boolean writesSwitchedOff(UUID agentId) {
        com.agenticai.core.settings.SettingsResolver catalog = settings;
        if (catalog == null) {
            return !limits.isWriteEnabled();
        }
        try {
            return !catalog.getBool(WRITE_ENABLED_KEY, com.agenticai.core.settings.SettingContext.forAgent(agentId));
        } catch (RuntimeException e) {
            return !limits.isWriteEnabled();
        }
    }

    static final String RECALL_TOP_K_KEY = "memory.recall.agentTopK";
    static final String MAX_MEMORIES_KEY = "memory.agent.maxMemories";

    private int setting(String key, UUID agentId, int platform) {
        com.agenticai.core.settings.SettingsResolver catalog = settings;
        if (catalog == null) {
            return platform;
        }
        try {
            return catalog.getInt(key, com.agenticai.core.settings.SettingContext.forAgent(agentId));
        } catch (RuntimeException e) {
            return platform;
        }
    }

    private int recallTopK(UUID agentId) {
        return setting(RECALL_TOP_K_KEY, agentId, limits.getAgentRecallTopK());
    }

    private int maxMemories(UUID agentId) {
        return setting(MAX_MEMORIES_KEY, agentId, limits.getAgentMaxMemories());
    }

    /**
     * Persists one fact/preference, embedding it for later semantic recall, then prunes the oldest
     * memories for this agent if this insert pushed it over {@code app.memory.limits.agent-max-memories}.
     */
    public UUID remember(UUID agentId, String subjectKey, String content) {
        return remember(agentId, subjectKey, content, MemoryProvenance.TRUSTED);
    }

    /**
     * {@link #remember(UUID, String, String)} with explicit provenance. A QUARANTINED row is stored
     * but never recalled or injected into a prompt until {@link #approve} is called for it.
     */
    public UUID remember(UUID agentId, String subjectKey, String content, MemoryProvenance provenance) {
        if (tombstones.isForgotten(
                com.agenticai.core.agents.memorystore.MemoryTombstoneGuard.KIND_AGENT, subjectKey, agentId, content)) {
            // Resurrection guard: the person (or the nightly tidy-up) forgot this exact text; only a person re-adding it
            // by hand (rememberOwn) brings it back.
            throw new MemoryForgottenException();
        }
        return insertFact(agentId, subjectKey, content, provenance, AGENT_IMPORTANCE, false);
    }

    /** Importance prior of a fact the agent saved itself; a typed fact starts at 0.8 (design 03, section 3). */
    static final double AGENT_IMPORTANCE = 0.6;

    static final double TYPED_IMPORTANCE = 0.8;

    private UUID insertFact(
            UUID agentId,
            String subjectKey,
            String content,
            MemoryProvenance provenance,
            double importance,
            boolean personAuthored) {
        MemoryProvenance prov =
                provenance == null ? new MemoryProvenance(MemoryTrust.QUARANTINED, null) : provenance;
        float[] embedding = sidecarClient.embedQuery(content);
        String literal = toVectorLiteral(embedding);
        UUID id = UUID.randomUUID();
        String table = AgentMemorySchemaSupport.qualifiedTableName();

        jdbcTemplate.update(
                "INSERT INTO "
                        + table
                        + " (id, agent_id, subject_key, content, embedding, trust, source_taint, importance,"
                        + " person_authored) VALUES (?, ?, ?, ?, ?::public.vector, ?, ?, ?, ?)",
                id,
                agentId,
                subjectKey,
                content,
                literal,
                prov.trust().name(),
                prov.sourcesJson(),
                importance,
                personAuthored);
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
                        maxMemories(agentId));
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
        // A person typing a fact on purpose is the explicit re-confirmation that clears a tombstone of the same text.
        tombstones.reconfirm(
                com.agenticai.core.agents.memorystore.MemoryTombstoneGuard.KIND_AGENT, subjectKey, agentId, content);
        return insertFact(agentId, subjectKey, content, MemoryProvenance.TRUSTED, TYPED_IMPORTANCE, true);
    }

    /**
     * Keeps this agent's total memory count at or under {@code app.memory.limits.agent-max-memories} by
     * deleting its oldest rows (across every subject) once it's over - a bounded "memory space"
     * rather than unbounded accumulation.
     */
    private void pruneOverCap(String table, UUID agentId) {
        Integer count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM " + table + " WHERE agent_id = ?",
                        Integer.class,
                        agentId);
        int cap = maxMemories(agentId);
        if (count == null || count <= cap) {
            return;
        }
        int overflow = count - cap;
        jdbcTemplate.update(
                "DELETE FROM "
                        + table
                        + " WHERE id IN ("
                        + "SELECT id FROM "
                        + table
                        + " WHERE agent_id = ? ORDER BY (trust = 'QUARANTINED') DESC, created_at ASC"
                        + " LIMIT ?)",
                agentId,
                overflow);
        log.debug(
                "Pruned {} oldest memories for agent {} (over the {}-memory cap)",
                overflow,
                agentId,
                cap);
    }

    /** Semantic recall, scoped to this agent + subject, ordered by relevance to {@code query}. */
    public List<AgentMemoryEntry> recall(UUID agentId, String subjectKey, String query, int topK) {
        return recall(agentId, subjectKey, query, topK, queryEmbedding(query));
    }

    /** A shareable, lazily computed embedding of {@code query}; see {@link QueryEmbedding}. */
    public QueryEmbedding queryEmbedding(String query) {
        return new QueryEmbedding(query, this::embedShared);
    }

    private final RecentEmbeddings recentEmbeddings = new RecentEmbeddings();

    /**
     * The same sidecar query embedding {@link #queryEmbedding} uses, shared for a few seconds per tenant and text, so
     * the unified-memory store's embedder ({@code SidecarMemoryEmbedder}) does not embed the user message a second time
     * in the same turn. Callers must not modify the returned array.
     */
    public float[] embedShared(String text) {
        String tenant = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        return recentEmbeddings.get(tenant, text, sidecarClient::embedQuery);
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
                                + " use_count, trust, source_taint, embedding OPERATOR(public.<=>)"
                                + " ?::public.vector AS distance FROM "
                                + table
                                + " WHERE agent_id = ? AND subject_key = ? AND trust = 'TRUSTED'"
                                + tombstones.excludeAgentFacts(table)
                                + " ORDER BY distance ASC LIMIT ?",
                        (rs, rowNum) ->
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
                                MemoryProvenance.parseSources(rs.getString("source_taint"))),
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
                LogSafe.clean(agentId),
                LogSafe.clean(subjectKey),
                LogSafe.clean(query));
        return results;
    }

    /**
     * Convenience overload - default topK, used by {@link
     * com.agenticai.core.agents.turn.AgentChatService}.
     */
    public List<AgentMemoryEntry> recall(UUID agentId, String subjectKey, String query) {
        return recall(agentId, subjectKey, query, recallTopK(agentId));
    }

    /** Default-topK recall that shares {@code embedding}; see {@link QueryEmbedding}. */
    public List<AgentMemoryEntry> recall(
            UUID agentId, String subjectKey, String query, QueryEmbedding embedding) {
        return recall(agentId, subjectKey, query, recallTopK(agentId), embedding);
    }

    /**
     * Every memory this agent has for this subject, most recent first - for a future admin/debug UI
     * panel.
     */
    public List<AgentMemoryEntry> listAll(UUID agentId, String subjectKey) {
        String table = AgentMemorySchemaSupport.qualifiedTableName();
        return jdbcTemplate.query(
                "SELECT id, agent_id, subject_key, content, created_at, last_used_at, use_count,"
                        + " trust, source_taint, 0.0 AS distance FROM "
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
                                rs.getInt("use_count"),
                                MemoryTrust.parse(rs.getString("trust")),
                                MemoryProvenance.parseSources(rs.getString("source_taint"))),
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
                        + " trust, source_taint, 0.0 AS distance FROM "
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
                                rs.getInt("use_count"),
                                MemoryTrust.parse(rs.getString("trust")),
                                MemoryProvenance.parseSources(rs.getString("source_taint"))),
                subjectKey);
    }

    /**
     * Up to {@code limit} distinct people (subject keys) who have a private memory in the current
     * tenant. For background reviews that walk every person; not for request paths.
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

    /**
     * A person's approval of a QUARANTINED memory: it becomes TRUSTED (recallable) and keeps its
     * {@code source_taint} as provenance. False when it does not exist for this agent or was already trusted.
     */
    public boolean approve(UUID agentId, UUID memoryId) {
        String table = AgentMemorySchemaSupport.qualifiedTableName();
        boolean approved =
                jdbcTemplate.update(
                                "UPDATE " + table + " SET trust = 'TRUSTED' WHERE id = ? AND agent_id = ?"
                                        + " AND trust = 'QUARANTINED'",
                                memoryId,
                                agentId)
                        > 0;
        if (approved) {
            syncApprovedToGraph(agentId, memoryId);
        }
        return approved;
    }

    /** {@link #approve(UUID, UUID)} restricted to a memory about {@code subjectKey}. */
    public boolean approve(UUID agentId, UUID memoryId, String subjectKey) {
        String table = AgentMemorySchemaSupport.qualifiedTableName();
        boolean approved =
                jdbcTemplate.update(
                                "UPDATE " + table + " SET trust = 'TRUSTED' WHERE id = ? AND agent_id = ?"
                                        + " AND subject_key = ? AND trust = 'QUARANTINED'",
                                memoryId,
                                agentId,
                                subjectKey)
                        > 0;
        if (approved) {
            syncApprovedToGraph(agentId, memoryId);
        }
        return approved;
    }

    private volatile ApprovedFactGraphSync graphSync;

    /** Mirrors an approved fact into the knowledge graph (G6); absent in hand-built instances, then approval is vector-only. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setGraphSync(ApprovedFactGraphSync graphSync) {
        this.graphSync = graphSync;
    }

    /**
     * After an approval has committed: offers the fact to the graph, unless writes are switched off for the agent.
     * Best-effort and fail-open (see {@link ApprovedFactGraphSync}); never changes the approval's result.
     */
    private void syncApprovedToGraph(UUID agentId, UUID memoryId) {
        ApprovedFactGraphSync sync = graphSync;
        if (sync == null || writesSwitchedOff(agentId)) {
            return;
        }
        try {
            jdbcTemplate.query(
                    "SELECT subject_key, content FROM " + AgentMemorySchemaSupport.qualifiedTableName() + " WHERE id = ?",
                    rs -> {
                        sync.agentFactApproved(agentId, rs.getString("subject_key"), memoryId, rs.getString("content"));
                    },
                    memoryId);
        } catch (RuntimeException e) {
            log.warn("Could not offer approved memory {} to the graph; the approval stands", memoryId, e);
        }
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
        params[0] = Timestamp.from(clock.instant());
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
