package com.agenticai.core.agents.memory;

import com.agenticai.core.knowledge.SidecarClient;
import java.sql.Timestamp;
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
 * <p><b>Bounded, tenant-wide memory space:</b> capped at {@value #MAX_MEMORIES_PER_TENANT} total
 * across every subject and every contributing agent - larger than {@link AgentMemoryService}'s
 * 50/agent cap since this single pool is shared by every sharing-enabled agent in the tenant, not
 * one agent's own bank. Same oldest-first pruning strategy after an over-cap insert.
 */
@Service
@Profile("knowledge")
public class TenantMemoryService {

    private static final Logger log = LoggerFactory.getLogger(TenantMemoryService.class);
    private static final int DEFAULT_RECALL_TOP_K = 5;
    private static final int MAX_MEMORIES_PER_TENANT = 300;

    private final SidecarClient sidecarClient;
    private final JdbcTemplate jdbcTemplate;
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
            @Value("${app.memory.blocks.max-total-chars:4000}") int maxTotalChars,
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
        String label = MemoryBlocks.normalizeLabel(rawLabel);
        if (label == null || (!activeProjectEnabled && MemoryBlocks.ACTIVE_PROJECT.equals(label))) {
            throw new IllegalArgumentException("Unknown memory block label.");
        }
        if (content == null || content.isBlank()) {
            deleteBlock(subjectKey, label);
            return;
        }
        if (MemoryBlocks.ACTIVE_PROJECT.equals(label)) {
            content = requireExistingProjectSlug(subjectKey, content);
        }
        if (content.length() > maxCharsPerBlock) {
            throw new IllegalArgumentException(
                    "A memory block can hold at most " + maxCharsPerBlock + " characters.");
        }
        String key = MemoryBlocks.storageKey(subjectKey, label);
        if (key == null) {
            throw new IllegalArgumentException("Memory block cannot be stored for this user.");
        }
        String table = TenantMemorySchemaSupport.qualifiedTableName();
        List<UUID> existing =
                jdbcTemplate.query(
                        "SELECT id FROM " + table + " WHERE subject_key = ?",
                        (rs, rowNum) -> UUID.fromString(rs.getString("id")),
                        key);
        if (existing.isEmpty() && MemoryBlocks.isProjectLabel(label)) {
            Integer projects =
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM " + table + " WHERE starts_with(subject_key, ?)",
                            Integer.class,
                            MemoryBlocks.storagePrefix(subjectKey) + MemoryBlocks.PROJECT_PREFIX);
            if (projects != null && projects >= maxProjectBlocks) {
                throw new IllegalArgumentException(
                        "You can keep at most " + maxProjectBlocks + " project notes.");
            }
        }
        // Embedding is NOT NULL on the table; blocks are always-in-context so it is never queried.
        String literal = toVectorLiteral(sidecarClient.embedQuery(content));
        if (existing.isEmpty()) {
            jdbcTemplate.update(
                    "INSERT INTO "
                            + table
                            + " (id, subject_key, content, embedding, created_by_agent_id) "
                            + "VALUES (?, ?, ?, ?::public.vector, NULL)",
                    UUID.randomUUID(),
                    key,
                    content,
                    literal);
        } else {
            jdbcTemplate.update(
                    "UPDATE "
                            + table
                            + " SET content = ?, embedding = ?::public.vector, last_used_at = ?"
                            + " WHERE id = ?",
                    content,
                    literal,
                    Timestamp.from(Instant.now()),
                    existing.get(0));
        }
    }

    /**
     * Validates an {@code active_project} value: a legal slug whose {@code project:<slug>} note
     * exists. Returns the slug.
     */
    private String requireExistingProjectSlug(String subjectKey, String content) {
        String slug = MemoryBlocks.activeProjectSlug(content);
        String projectKey =
                slug == null
                        ? null
                        : MemoryBlocks.storageKey(subjectKey, MemoryBlocks.PROJECT_PREFIX + slug);
        if (projectKey == null) {
            throw new IllegalArgumentException("Choose one of your project notes to make active.");
        }
        Integer found =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM "
                                + TenantMemorySchemaSupport.qualifiedTableName()
                                + " WHERE subject_key = ?",
                        Integer.class,
                        projectKey);
        if (found == null || found == 0) {
            throw new IllegalArgumentException(
                    "There is no project note named '" + slug + "'. Add the note first.");
        }
        return slug;
    }

    public void deleteBlock(String subjectKey, String rawLabel) {
        String label = MemoryBlocks.normalizeLabel(rawLabel);
        String key = label == null ? null : MemoryBlocks.storageKey(subjectKey, label);
        if (key == null) {
            return;
        }
        String table = TenantMemorySchemaSupport.qualifiedTableName();
        jdbcTemplate.update("DELETE FROM " + table + " WHERE subject_key = ?", key);
        if (MemoryBlocks.isProjectLabel(label)) {
            // A deleted project cannot stay the active one: drop the pointer only if it points
            // here.
            String activeKey = MemoryBlocks.storageKey(subjectKey, MemoryBlocks.ACTIVE_PROJECT);
            if (activeKey != null) {
                jdbcTemplate.update(
                        "DELETE FROM " + table + " WHERE subject_key = ? AND content = ?",
                        activeKey,
                        label.substring(MemoryBlocks.PROJECT_PREFIX.length()));
            }
        }
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
                "SELECT id, subject_key, content, created_by_agent_id, created_at, last_used_at,"
                        + " use_count, 0.0 AS distance FROM "
                        + table
                        + " WHERE subject_key = ? ORDER BY created_at DESC",
                (rs, rowNum) ->
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
                                rs.getInt("use_count")),
                subjectKey);
    }

    /**
     * Up to {@code limit} distinct people (subject keys) who have a shared fact in the current tenant; the
     * user-visible memory blocks (a different key) are left out. For background reviews, not request paths.
     */
    public List<String> listFactSubjects(int limit) {
        String table = TenantMemorySchemaSupport.qualifiedTableName();
        return jdbcTemplate.queryForList(
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

    public boolean forgetFactForSubject(UUID memoryId, String subjectKey) {
        String table = TenantMemorySchemaSupport.qualifiedTableName();
        return jdbcTemplate.update(
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
        if (subjectKey != null && subjectKey.contains(MemoryBlocks.KEY_MARKER)) {
            // Would collide with another subject's memory-block rows (stored as
            // '<subject>#mem:<label>').
            throw new IllegalArgumentException(
                    "Subject key must not contain '" + MemoryBlocks.KEY_MARKER + "'.");
        }
        float[] embedding = sidecarClient.embedQuery(content);
        String literal = toVectorLiteral(embedding);
        UUID id = UUID.randomUUID();
        String table = TenantMemorySchemaSupport.qualifiedTableName();

        jdbcTemplate.update(
                "INSERT INTO "
                        + table
                        + " (id, subject_key, content, embedding, created_by_agent_id) VALUES (?,"
                        + " ?, ?, ?::public.vector, ?)",
                id,
                subjectKey,
                content,
                literal,
                createdByAgentId);
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
                        "SELECT COUNT(*) FROM " + table + " WHERE subject_key = ?",
                        Integer.class,
                        subjectKey);
        Integer total =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM "
                                + table
                                + " WHERE position('"
                                + MemoryBlocks.KEY_MARKER
                                + "' in subject_key) = 0",
                        Integer.class);
        int evict =
                OwnFactAdmission.ownRowsToEvict(
                        own == null ? 0 : own,
                        total == null ? 0 : total,
                        maxOwn,
                        MAX_MEMORIES_PER_TENANT);
        if (evict > 0) {
            jdbcTemplate.update(
                    "DELETE FROM "
                            + table
                            + " WHERE id IN ("
                            + "SELECT id FROM "
                            + table
                            + " WHERE subject_key = ? ORDER BY created_at ASC LIMIT ?)",
                    subjectKey,
                    evict);
        }
        return remember(subjectKey, content, null);
    }

    private void pruneOverCap(String table) {
        // User-visible memory blocks (subject_key contains '#mem:') are neither counted nor pruned.
        Integer count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM "
                                + table
                                + " WHERE position('"
                                + MemoryBlocks.KEY_MARKER
                                + "' in subject_key) = 0",
                        Integer.class);
        if (count == null || count <= MAX_MEMORIES_PER_TENANT) {
            return;
        }
        int overflow = count - MAX_MEMORIES_PER_TENANT;
        jdbcTemplate.update(
                "DELETE FROM "
                        + table
                        + " WHERE id IN ("
                        + "SELECT id FROM "
                        + table
                        + " WHERE position('"
                        + MemoryBlocks.KEY_MARKER
                        + "' in subject_key) = 0 ORDER BY created_at ASC LIMIT ?)",
                overflow);
        log.debug(
                "Pruned {} oldest shared memories (over the {}-memory tenant-wide cap)",
                overflow,
                MAX_MEMORIES_PER_TENANT);
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
     * recalls of the same text: the sidecar is asked at most once for all of them.
     */
    public List<TenantMemoryEntry> recall(
            String subjectKey, String query, int topK, QueryEmbedding embedding) {
        float[] queryVector = embedding.vector();
        String literal = toVectorLiteral(queryVector);
        String table = TenantMemorySchemaSupport.qualifiedTableName();

        List<TenantMemoryEntry> results =
                jdbcTemplate.query(
                        "SELECT id, subject_key, content, created_by_agent_id, created_at,"
                                + " last_used_at, use_count, embedding OPERATOR(public.<=>)"
                                + " ?::public.vector AS distance FROM "
                                + table
                                + " WHERE subject_key = ? ORDER BY distance ASC LIMIT ?",
                        (rs, rowNum) ->
                                new TenantMemoryEntry(
                                        UUID.fromString(rs.getString("id")),
                                        rs.getString("subject_key"),
                                        rs.getString("content"),
                                        rs.getDouble("distance"),
                                        rs.getString("created_by_agent_id") == null
                                                ? null
                                                : UUID.fromString(
                                                        rs.getString("created_by_agent_id")),
                                        rs.getTimestamp("created_at").toInstant(),
                                        rs.getTimestamp("last_used_at").toInstant(),
                                        rs.getInt("use_count")),
                        literal,
                        subjectKey,
                        topK);

        if (!results.isEmpty()) {
            touchUsage(table, results.stream().map(TenantMemoryEntry::id).toList());
        }
        log.debug(
                "Recalled {} shared memories for subject '{}' (query='{}')",
                results.size(),
                subjectKey,
                query);
        return results;
    }

    /**
     * Convenience overload - default topK, used by {@link
     * com.agenticai.core.agents.turn.AgentChatService}.
     */
    public List<TenantMemoryEntry> recall(String subjectKey, String query) {
        return recall(subjectKey, query, DEFAULT_RECALL_TOP_K);
    }

    /** Default-topK recall that shares {@code embedding}; see {@link QueryEmbedding}. */
    public List<TenantMemoryEntry> recall(
            String subjectKey, String query, QueryEmbedding embedding) {
        return recall(subjectKey, query, DEFAULT_RECALL_TOP_K, embedding);
    }

    /**
     * Retention purge (DPDP-oriented controls): deletes up to {@code limit} rows created before
     * {@code cutoff} in the current tenant's schema, oldest first, and returns how many went. The
     * caller loops until it returns less than {@code limit}.
     */
    public int purgeCreatedBefore(java.time.Instant cutoff, int limit) {
        String table = TenantMemorySchemaSupport.qualifiedTableName();
        int deleted =
                jdbcTemplate.update(
                        "DELETE FROM "
                                + table
                                + " WHERE id IN (SELECT id FROM "
                                + table
                                + " WHERE created_at < ? ORDER BY created_at LIMIT ?)",
                        java.sql.Timestamp.from(cutoff),
                        limit);
        log.info("Retention purge removed {} tenant-memory rows older than {}", deleted, cutoff);
        return deleted;
    }

    /**
     * #815 Governance follow-up parity with {@link AgentMemoryService#forgetAllForSubject} -
     * deletes every shared memory this tenant holds about one subject, for the same data-subject
     * erasure reasoning. Returns the number of rows deleted.
     */
    public int forgetAllForSubject(String subjectKey) {
        String table = TenantMemorySchemaSupport.qualifiedTableName();
        // Also sweeps this subject's user-visible memory blocks (subject_key =
        // '<subject>#mem:<label>').
        int deleted =
                jdbcTemplate.update(
                        "DELETE FROM "
                                + table
                                + " WHERE subject_key = ? OR starts_with(subject_key, ?)",
                        subjectKey,
                        MemoryBlocks.storagePrefix(subjectKey));
        log.info(
                "Erased {} shared tenant-memory rows for subject key (data-subject erasure"
                        + " request)",
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
