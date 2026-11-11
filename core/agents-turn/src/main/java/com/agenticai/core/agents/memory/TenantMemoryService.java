package com.agenticai.core.agents.memory;

import com.agenticai.core.knowledge.SidecarClient;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Shared cross-agent memory tier (#847) - the tenant-wide counterpart to {@link
 * AgentMemoryService}. Where {@link AgentMemoryService} scopes every query by {@code (agentId,
 * subjectKey)} so recall never leaks between agents, this class scopes only by {@code subjectKey} -
 * any agent in the tenant with sharing turned on ({@code Agent#isShareMemoryWithTenant}) can both
 * write here and have it automatically recalled, so a fact learned by one agent about a real person
 * follows that person into a conversation with a different agent, rather than staying siloed behind
 * whichever single agent happened to learn it first (the gap the "agent long-term memory" feature
 * left open per-agent by original design).
 *
 * <p>Reuses the exact same embedding/pgvector pattern {@link AgentMemoryService} established - same
 * sidecar embedding call, same raw {@link JdbcTemplate} approach against a schema-qualified table
 * (see {@link TenantMemorySchemaSupport}), same "knowledge" profile gate (the profile that
 * guarantees a pgvector-enabled Postgres and a running sidecar exist).
 *
 * <p><b>Bounded, tenant-wide memory space:</b> capped at {@code
 * app.memory.limits.tenant-max-memories} (default 300) total across every subject and every
 * contributing agent - larger than {@link AgentMemoryService}'s 50/agent cap since this single pool
 * is shared by every sharing-enabled agent in the tenant, not one agent's own bank. Same
 * oldest-first pruning strategy after an over-cap insert.
 */
@Service
@Profile("knowledge")
public class TenantMemoryService {

    private static final Logger log = LoggerFactory.getLogger(TenantMemoryService.class);

    final SidecarClient sidecarClient;
    final JdbcTemplate jdbcTemplate;
    private final boolean blocksEnabled;
    private final int maxCharsPerBlock;
    private final int maxProjectBlocks;
    private final int maxTotalChars;
    private final boolean activeProjectEnabled;

    public TenantMemoryService(
            SidecarClient sidecarClient,
            JdbcTemplate jdbcTemplate,
            @Value("${app.memory.blocks.enabled:true}") boolean blocksEnabled,
            @Value("${app.memory.blocks.max-chars-per-block:1200}") int maxCharsPerBlock,
            @Value("${app.memory.blocks.max-project-blocks:20}") int maxProjectBlocks,
            @Value(
                            "${app.memory.blocks.max-total-chars:"
                                    + MemoryBlocks.DEFAULT_MAX_TOTAL_CHARS
                                    + "}")
                    int maxTotalChars,
            @Value("${app.memory.blocks.active-project-enabled:true}")
                    boolean activeProjectEnabled) {
        this.sidecarClient = sidecarClient;
        this.jdbcTemplate = jdbcTemplate;
        this.blocksEnabled = blocksEnabled;
        this.maxCharsPerBlock = Math.max(1, maxCharsPerBlock);
        this.maxProjectBlocks = Math.max(0, maxProjectBlocks);
        this.maxTotalChars = Math.max(1, maxTotalChars);
        this.activeProjectEnabled = activeProjectEnabled;
    }

    /**
     * {@code app.memory.limits.*}: the per-tenant cap ({@code tenant-max-memories}) and the recall
     * top-k ({@code tenant-recall-top-k}). A hand-built instance keeps the shipped defaults, which
     * is also what Spring supplies when the properties bean is absent.
     */
    private volatile MemoryLimitsProperties limits = new MemoryLimitsProperties();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setLimits(MemoryLimitsProperties limits) {
        this.limits = limits == null ? new MemoryLimitsProperties() : limits;
    }

    volatile java.time.Clock clock = java.time.Clock.systemUTC();

    /**
     * The memory clock ({@code memoryClock} bean, system UTC by default); tests pin it to control
     * time.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void useClock(
            @org.springframework.beans.factory.annotation.Qualifier("memoryClock")
                    java.time.Clock clock) {
        this.clock = clock == null ? java.time.Clock.systemUTC() : clock;
    }

    volatile com.agenticai.core.agents.memorystore.MemoryTombstoneGuard tombstones =
            com.agenticai.core.agents.memorystore.MemoryTombstoneGuard.inactive();

    /**
     * Makes a forgotten (tombstoned) shared fact stay forgotten: recall leaves it out and {@link
     * #remember} refuses it.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setTombstoneGuard(
            com.agenticai.core.agents.memorystore.MemoryTombstoneGuard guard) {
        this.tombstones =
                guard == null
                        ? com.agenticai.core.agents.memorystore.MemoryTombstoneGuard.inactive()
                        : guard;
    }

    private volatile com.agenticai.core.settings.SettingsResolver settings;

    /**
     * The settings catalog, so a tenant's or an agent's {@code memory.recall.tenantTopK} applies to
     * the shared recall made for that agent. Absent (hand-built instances): {@code
     * app.memory.limits.tenant-recall-top-k}. The recall runs on the turn's own thread, where the
     * tenant is set.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSettingsResolver(com.agenticai.core.settings.SettingsResolver resolver) {
        this.settings = resolver;
    }

    /**
     * {@code memory.write.enabled} for {@code agentId} (the shared tool writes on an agent's
     * behalf), asked as "off".
     */
    public boolean writesSwitchedOff(UUID agentId) {
        com.agenticai.core.settings.SettingsResolver catalog = settings;
        if (catalog == null) {
            return !limits.isWriteEnabled();
        }
        try {
            return !catalog.getBool(
                    AgentMemoryService.WRITE_ENABLED_KEY,
                    com.agenticai.core.settings.SettingContext.forAgent(agentId));
        } catch (RuntimeException e) {
            return !limits.isWriteEnabled();
        }
    }

    static final String RECALL_TOP_K_KEY = "memory.recall.tenantTopK";
    static final String MAX_MEMORIES_KEY = "memory.tenant.maxMemories";

    /**
     * How many shared memories the calling tenant keeps: its {@code memory.tenant.maxMemories}
     * setting, else {@code app.memory.limits.tenant-max-memories}. Shared memory belongs to the
     * workspace, so the key has no agent scope.
     */
    int maxMemories() {
        com.agenticai.core.settings.SettingsResolver catalog = settings;
        if (catalog == null) {
            return limits.getTenantMaxMemories();
        }
        try {
            return catalog.getInt(
                    MAX_MEMORIES_KEY, com.agenticai.core.settings.SettingContext.tenant());
        } catch (RuntimeException e) {
            return limits.getTenantMaxMemories();
        }
    }

    /**
     * The recall top-k for {@code agentId} (null = the tenant level): the catalog's value, else the
     * platform's.
     */
    int recallTopK(UUID agentId) {
        com.agenticai.core.settings.SettingsResolver catalog = settings;
        if (catalog == null) {
            return limits.getTenantRecallTopK();
        }
        try {
            return catalog.getInt(
                    RECALL_TOP_K_KEY, com.agenticai.core.settings.SettingContext.forAgent(agentId));
        } catch (RuntimeException e) {
            return limits.getTenantRecallTopK();
        }
    }

    /**
     * False = the {@code active_project} label is rejected on write, hidden from lists and ignored
     * when rendering.
     */
    public boolean activeProjectEnabled() {
        return activeProjectEnabled;
    }

    public boolean blocksEnabled() {
        return blocksEnabled;
    }

    public int maxCharsPerBlock() {
        return maxCharsPerBlock;
    }

    public int maxTotalChars() {
        return maxTotalChars;
    }

    public int maxProjectBlocks() {
        return maxProjectBlocks;
    }

    // ---- user-visible memory blocks (gap-analysis item 11) - see MemoryBlocks for the storage
    // scheme ----

    /**
     * One stored block; {@code updatedAt} is the row's {@code last_used_at}, set on every write.
     */
    public record MemoryBlockEntry(String label, String content, Instant updatedAt) {}

    /** Every block this subject has, fixed labels first then projects, by label. */
    public List<MemoryBlockEntry> listBlocks(String subjectKey) {
        if (!MemoryBlocks.isSafeSubject(subjectKey)) {
            return List.of();
        }
        String table = TenantMemorySchemaSupport.qualifiedTableName();
        List<MemoryBlockEntry> rows =
                jdbcTemplate.query(
                        // codeql[java/sql-injection]
                        "SELECT subject_key, content, last_used_at FROM "
                                + table
                                + " WHERE starts_with(subject_key, ?)",
                        (rs, rowNum) ->
                                new MemoryBlockEntry(
                                        MemoryBlocks.labelOf(
                                                subjectKey, rs.getString("subject_key")),
                                        rs.getString("content"),
                                        rs.getTimestamp("last_used_at").toInstant()),
                        MemoryBlocks.storagePrefix(subjectKey));
        return rows.stream()
                .filter(b -> b.label() != null)
                .filter(b -> activeProjectEnabled || !MemoryBlocks.ACTIVE_PROJECT.equals(b.label()))
                .sorted(
                        java.util.Comparator.comparingInt(
                                        (MemoryBlockEntry b) -> {
                                            int i = MemoryBlocks.FIXED_LABELS.indexOf(b.label());
                                            return i < 0 ? MemoryBlocks.FIXED_LABELS.size() : i;
                                        })
                                .thenComparing(MemoryBlockEntry::label))
                .toList();
    }

    /**
     * Creates or replaces one block. Blank content deletes it. Throws {@link
     * IllegalArgumentException} (message safe to show the user) for an illegal label, an over-cap
     * body or too many project blocks. The reserved {@code active_project} label takes a project
     * slug that must already have a note.
     */
    public void putBlock(String subjectKey, String rawLabel, String content) {
        TenantMemoryBlockWrites.putBlock(
                jdbcTemplate,
                sidecarClient,
                clock,
                activeProjectEnabled,
                maxCharsPerBlock,
                maxProjectBlocks,
                subjectKey,
                rawLabel,
                content);
    }

    public void deleteBlock(String subjectKey, String rawLabel) {
        TenantMemoryBlockWrites.deleteBlock(jdbcTemplate, subjectKey, rawLabel);
    }

    /**
     * The compact always-in-context block text for a chat turn, or {@code null} (feature off, no
     * blocks, or nothing relevant). Never throws into chat.
     */
    public String renderPromptBlocks(String subjectKey, String userMessage) {
        MemoryBlocks.Rendered rendered = renderPromptBlocksWithLabels(subjectKey, userMessage);
        return rendered == null ? null : rendered.text();
    }

    /**
     * {@link #renderPromptBlocks} plus the labels of the blocks that were rendered, for the "memory
     * used" step event. Same text, same {@code null} cases.
     */
    public MemoryBlocks.Rendered renderPromptBlocksWithLabels(
            String subjectKey, String userMessage) {
        if (!blocksEnabled || subjectKey == null || subjectKey.isBlank()) {
            return null;
        }
        Map<String, String> byLabel = new LinkedHashMap<>();
        for (MemoryBlockEntry b : listBlocks(subjectKey)) {
            byLabel.put(b.label(), b.content());
        }
        return MemoryBlocks.renderWithLabels(byLabel, userMessage, maxCharsPerBlock, maxTotalChars);
    }

    // ---- user-managed shared facts (edit / delete by owner) ----

    /**
     * Shared facts about this subject, newest first (block rows have a different subject_key and
     * never appear).
     */
    public List<TenantMemoryEntry> listFactsForSubject(String subjectKey) {
        String table = TenantMemorySchemaSupport.qualifiedTableName();
        return jdbcTemplate.query(
                // codeql[java/sql-injection]
                "SELECT id, subject_key, content, created_by_agent_id, created_at, last_used_at,"
                        + " use_count, trust, source_taint, superseded_at, 0.0 AS distance FROM "
                        + table
                        + " WHERE subject_key = ? ORDER BY created_at DESC",
                TenantMemoryFactRows.entryMapper(),
                subjectKey);
    }

    /**
     * Up to {@code limit} distinct people (subject keys) who have a shared fact in the current
     * tenant; the user-visible memory blocks (a different key) are left out. For background
     * reviews, not request paths.
     */
    public List<String> listFactSubjects(int limit) {
        String table = TenantMemorySchemaSupport.qualifiedTableName();
        return jdbcTemplate.queryForList(
                // codeql[java/sql-injection]
                "SELECT DISTINCT subject_key FROM "
                        + table
                        + " WHERE position('"
                        + MemoryBlocks.KEY_MARKER
                        + "' in subject_key) = 0 LIMIT ?",
                String.class,
                Math.max(1, limit));
    }

    /**
     * Re-embeds and rewrites one shared fact owned by {@code subjectKey}; returns false when it is
     * not theirs.
     */
    public boolean updateFactForSubject(UUID memoryId, String subjectKey, String content) {
        String table = TenantMemorySchemaSupport.qualifiedTableName();
        String literal = toVectorLiteral(sidecarClient.embedQuery(content));
        return jdbcTemplate.update(
                        // codeql[java/sql-injection]
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

    /**
     * A person's approval of a QUARANTINED shared fact about {@code subjectKey} (any subject when
     * null): it becomes TRUSTED and recallable by every sharing agent.
     */
    public boolean approveFact(UUID memoryId, String subjectKey) {
        String table = TenantMemorySchemaSupport.qualifiedTableName();
        boolean approved = TenantMemoryFactRows.approve(jdbcTemplate, table, memoryId, subjectKey);
        if (approved) {
            FactSupersession sup = supersession;
            if (sup != null) {
                sup.applyPending(table, memoryId, clock.instant());
            }
            TenantMemoryFactRows.syncApprovedToGraph(jdbcTemplate, graphSync, table, memoryId);
        }
        return approved;
    }

    private volatile ApprovedFactGraphSync graphSync;

    /**
     * Mirrors an approved shared fact into the shared graph tier (G6); absent in hand-built
     * instances.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setGraphSync(ApprovedFactGraphSync graphSync) {
        this.graphSync = graphSync;
    }

    public boolean forgetFactForSubject(UUID memoryId, String subjectKey) {
        String table = TenantMemorySchemaSupport.qualifiedTableName();
        return jdbcTemplate.update(
                        // codeql[java/sql-injection]
                        "DELETE FROM " + table + " WHERE id = ? AND subject_key = ?",
                        memoryId,
                        subjectKey)
                > 0;
    }

    /**
     * Persists one shared fact, embedding it for later semantic recall, then prunes if over the
     * tenant-wide cap.
     */
    public UUID remember(String subjectKey, String content, UUID createdByAgentId) {
        return remember(subjectKey, content, createdByAgentId, MemoryProvenance.TRUSTED);
    }

    /**
     * {@link #remember(String, String, UUID)} with explicit provenance; a QUARANTINED row is not
     * recalled until {@link #approveFact} is called for it.
     */
    public UUID remember(
            String subjectKey, String content, UUID createdByAgentId, MemoryProvenance provenance) {
        return remember(subjectKey, content, createdByAgentId, provenance, null);
    }

    /**
     * As above; {@code replaces} names the earlier shared fact this one corrects (see {@link
     * AgentMemoryService#remember}).
     */
    public UUID remember(
            String subjectKey,
            String content,
            UUID createdByAgentId,
            MemoryProvenance provenance,
            String replaces) {
        if (tombstones.isForgotten(
                com.agenticai.core.agents.memorystore.MemoryTombstoneGuard.KIND_SHARED,
                subjectKey,
                null,
                content)) {
            throw new MemoryForgottenException(); // resurrection guard; only rememberOwn (a person)
            // clears it
        }
        return insertFact(subjectKey, content, createdByAgentId, provenance, 0.6, false, replaces);
    }

    private volatile FactSupersession supersession;

    /**
     * {@code app.memory.supersession.*} (V685); absent or disabled: every shared fact is a new row,
     * as before.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSupersession(MemorySupersessionProperties properties) {
        this.supersession =
                properties == null || !properties.isEnabled()
                        ? null
                        : new FactSupersession(jdbcTemplate, properties);
    }

    /**
     * Whether a new TRUSTED shared fact replaces the fact it corrects (then the tool offers {@code
     * replaces}).
     */
    public boolean supersessionEnabled() {
        return supersession != null;
    }

    private UUID insertFact(
            String subjectKey,
            String content,
            UUID createdByAgentId,
            MemoryProvenance provenance,
            double importance,
            boolean personAuthored,
            String replaces) {
        MemoryProvenance prov =
                provenance == null
                        ? new MemoryProvenance(MemoryTrust.QUARANTINED, null)
                        : provenance;
        if (subjectKey != null && subjectKey.contains(MemoryBlocks.KEY_MARKER)) {
            // Would collide with another subject's memory-block rows (stored as
            // '<subject>#mem:<label>').
            throw new IllegalArgumentException(
                    "Subject key must not contain '" + MemoryBlocks.KEY_MARKER + "'.");
        }
        String table = TenantMemorySchemaSupport.qualifiedTableName();
        FactSupersession sup = supersession;
        boolean trusted = prov.trust() == MemoryTrust.TRUSTED;
        FactSupersession.Scope scope =
                new FactSupersession.Scope(table, "subject_key = ?", subjectKey);
        if (sup != null && trusted) {
            java.util.Optional<UUID> same = sup.reconfirm(scope, content, clock.instant());
            if (same.isPresent()) {
                return same.get(); // the same fact told again: re-confirmed, not stored twice
            }
        }
        float[] embedding = sidecarClient.embedQuery(content);
        String literal = toVectorLiteral(embedding);
        UUID id = UUID.randomUUID();

        jdbcTemplate.update(
                // codeql[java/sql-injection]
                "INSERT INTO "
                        + table
                        + " (id, subject_key, content, embedding, created_by_agent_id, trust,"
                        + " source_taint, importance, person_authored)"
                        + " VALUES (?, ?, ?, ?::public.vector, ?, ?, ?, ?, ?)",
                id,
                subjectKey,
                content,
                literal,
                createdByAgentId,
                prov.trust().name(),
                prov.sourcesJson(),
                importance,
                personAuthored);
        if (sup != null) {
            sup.afterInsert(
                    scope,
                    id,
                    literal,
                    replaces,
                    text -> toVectorLiteral(sidecarClient.embedQuery(text)),
                    personAuthored,
                    trusted,
                    clock.instant());
        }
        pruneOverCap(table);
        log.debug(
                "Remembered shared fact for subject '{}' (contributed by agent {}): {}",
                subjectKey,
                createdByAgentId,
                content);
        return id;
    }

    /**
     * {@link #remember} for a shared fact the user typed in by hand. Unlike an agent-initiated save
     * it can never evict another subject's row: the caller may hold at most {@code maxOwn} shared
     * facts, and when the tenant-wide pool is full room is made from the caller's own oldest facts
     * (see {@link OwnFactAdmission}).
     *
     * @throws MemoryCapExceededException when the caller is at their cap or cannot make room
     */
    public UUID rememberOwn(String subjectKey, String content, int maxOwn) {
        if (subjectKey == null || subjectKey.contains(MemoryBlocks.KEY_MARKER)) {
            throw new IllegalArgumentException(
                    "Subject key must not contain '" + MemoryBlocks.KEY_MARKER + "'.");
        }
        String table = TenantMemorySchemaSupport.qualifiedTableName();
        Integer own =
                jdbcTemplate.queryForObject(
                        // codeql[java/sql-injection]
                        "SELECT COUNT(*) FROM " + table + " WHERE subject_key = ?",
                        Integer.class,
                        subjectKey);
        Integer total =
                jdbcTemplate.queryForObject(
                        // codeql[java/sql-injection]
                        "SELECT COUNT(*) FROM "
                                + table
                                + " WHERE position('"
                                + MemoryBlocks.KEY_MARKER
                                + "' in subject_key) = 0",
                        Integer.class);
        int evict =
                OwnFactAdmission.ownRowsToEvict(
                        own == null ? 0 : own, total == null ? 0 : total, maxOwn, maxMemories());
        if (evict > 0) {
            jdbcTemplate.update(
                    // codeql[java/sql-injection]
                    "DELETE FROM "
                            + table
                            + " WHERE id IN ("
                            + "SELECT id FROM "
                            + table
                            + " WHERE subject_key = ? ORDER BY created_at ASC LIMIT ?)",
                    subjectKey,
                    evict);
        }
        // A person typing a fact on purpose is the explicit re-confirmation that clears a tombstone
        // of the same text.
        tombstones.reconfirm(
                com.agenticai.core.agents.memorystore.MemoryTombstoneGuard.KIND_SHARED,
                subjectKey,
                null,
                content);
        return insertFact(subjectKey, content, null, MemoryProvenance.TRUSTED, 0.8, true, null);
    }

    private void pruneOverCap(String table) {
        TenantMemoryFactRows.pruneOverCap(jdbcTemplate, table, maxMemories());
    }

    /**
     * Semantic recall, scoped to this subject only (across every contributing agent), ordered by
     * relevance to {@code query}.
     */
    public List<TenantMemoryEntry> recall(String subjectKey, String query, int topK) {
        return recall(subjectKey, query, topK, queryEmbedding(query));
    }

    /** A shareable, lazily computed embedding of {@code query}; see {@link QueryEmbedding}. */
    public QueryEmbedding queryEmbedding(String query) {
        return new QueryEmbedding(query, sidecarClient::embedQuery);
    }

    /**
     * {@link #recall(String, String, int)} with an embedding the caller may share with other
     * recalls of the same text; see {@link TenantMemoryReads#recall}.
     */
    public List<TenantMemoryEntry> recall(
            String subjectKey, String query, int topK, QueryEmbedding embedding) {
        return reads.recall(subjectKey, query, topK, embedding);
    }

    /** Default-topK recall that shares {@code embedding}; see {@link TenantMemoryReads#recall}. */
    public List<TenantMemoryEntry> recall(
            String subjectKey, String query, QueryEmbedding embedding) {
        return recall(subjectKey, query, embedding, null);
    }

    /**
     * As {@link #recall(String, String, QueryEmbedding)} for the shared recall made on behalf of
     * {@code agentId}.
     */
    public List<TenantMemoryEntry> recall(
            String subjectKey, String query, QueryEmbedding embedding, UUID agentId) {
        return recall(subjectKey, query, recallTopK(agentId), embedding);
    }

    /**
     * Convenience overload - default topK, used by {@link
     * com.agenticai.core.agents.turn.AgentChatService}.
     */
    public List<TenantMemoryEntry> recall(String subjectKey, String query) {
        return recall(subjectKey, query, recallTopK(null));
    }

    /**
     * Deletes facts created before {@code cutoff} (retention), oldest first, at most {@code limit}.
     */
    public int purgeCreatedBefore(java.time.Instant cutoff, int limit) {
        return reads.purgeCreatedBefore(cutoff, limit);
    }

    /** Data-subject erasure: every shared-memory row about {@code subjectKey}. */
    public int forgetAllForSubject(String subjectKey) {
        return reads.forgetAllForSubject(subjectKey);
    }

    private final TenantMemoryReads reads = new TenantMemoryReads(this);

    static String toVectorLiteral(float[] embedding) {
        List<Double> boxed =
                IntStream.range(0, embedding.length)
                        .mapToDouble(i -> embedding[i])
                        .boxed()
                        .toList();
        return boxed.stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]"));
    }
}
