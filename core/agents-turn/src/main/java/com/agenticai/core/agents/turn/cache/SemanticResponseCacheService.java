package com.agenticai.core.agents.turn.cache;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.knowledge.SidecarClient;
import com.agenticai.core.settings.SettingContext;
import com.agenticai.core.settings.SettingsResolver;
import com.agenticai.core.tenancy.TenantSchemaResolver;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Master Roadmap 2026 item #18 (cost optimization: routing + semantic caching,
 * §G) - the embedding-similarity tier {@link LlmResponseCacheService}'s javadoc
 * deliberately left as a "real, natural follow-up". Layered ON TOP of that
 * exact-match Redis cache, never replacing it: {@link AgentChatService} tries
 * the exact-match cache first (fastest, zero ambiguity), and only falls through
 * to this fuzzy tier when an agent has explicitly opted into it.
 *
 * <p><b>Why this is a separate, stronger opt-in (and quality-safe):</b> a
 * similarity cache can return a cached answer for a DIFFERENT question that
 * merely scored above a threshold - the exact wrong-answer risk
 * {@code LlmResponseCacheService} chose to avoid. This tier is gated four ways
 * so that risk is a deliberate, bounded choice rather than a silent trap:
 * <ol>
 *   <li>Per-agent opt-in via {@link Agent#semanticResponseCachingEnabled},
 *       distinct from the exact-match flag and default false.</li>
 *   <li>A high default cosine-similarity threshold (0.95) - only near-identical phrasings match.</li>
 *   <li>Only the plain default turn shape is ever cached (no attachment, no
 *       per-call extra tools - enforced at the {@link AgentChatService} call
 *       site), and {@link #put} is only invoked for turns that used NO tools,
 *       so a live/tool-derived answer never enters this cache.</li>
 *   <li>A wall-clock TTL (6h default) bounds how stale
 *       any entry can get.</li>
 * </ol>
 *
 * <p>Reuses the platform's established embedding/pgvector pattern verbatim
 * ({@link SidecarClient} for the 384-dim embedding, raw {@link JdbcTemplate}
 * against a schema-qualified table because pgvector's {@code vector} type has no
 * Hibernate mapping - see {@link com.agenticai.core.agents.memory.AgentMemoryService}
 * and {@code VectorSchemaInitializer}). Gated behind the {@code knowledge}
 * profile for the same reason as agent memory: that profile guarantees a
 * pgvector-enabled Postgres and a running sidecar. Tenant isolation is by
 * schema (the table lives in the tenant's own schema); within it every row is
 * additionally bound to the caller's principal via {@code subject_hash} (SEC-052,
 * {@code tenant/V365.5}), so a lookup matches only (agent, principal) - one user's
 * personalised answer is never served to another user. Rows written before that
 * column existed have {@code subject_hash IS NULL} and are never matched again.
 */
@Service
@Profile("knowledge")
public class SemanticResponseCacheService {

    private static final Logger log = LoggerFactory.getLogger(SemanticResponseCacheService.class);
    private final SidecarClient sidecarClient;
    private final JdbcTemplate jdbcTemplate;
    private final SemanticCacheProperties properties;

    /**
     * The settings catalog, so a tenant's or an agent's {@code chat.semanticCache.*} replace {@code
     * app.chat.semantic-cache.*} for that agent's cache. The cache is read and written on the turn's own thread, where
     * the tenant is set. Absent (hand-built instances) or failing: the platform value.
     */
    private volatile SettingsResolver settings;

    @Autowired
    public SemanticResponseCacheService(
        SidecarClient sidecarClient, JdbcTemplate jdbcTemplate, SemanticCacheProperties properties
    ) {
        this.sidecarClient = sidecarClient;
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    /** With explicit platform values (tests, embedded use). */
    public SemanticResponseCacheService(
        SidecarClient sidecarClient, JdbcTemplate jdbcTemplate, double similarityThreshold, long ttlHours, int maxEntriesPerAgent
    ) {
        this(sidecarClient, jdbcTemplate, platform(similarityThreshold, ttlHours, maxEntriesPerAgent));
    }

    private static SemanticCacheProperties platform(double similarityThreshold, long ttlHours, int maxEntriesPerAgent) {
        SemanticCacheProperties p = new SemanticCacheProperties();
        p.setSimilarityThreshold(similarityThreshold);
        p.setTtlHours(ttlHours);
        p.setMaxEntriesPerAgent(maxEntriesPerAgent);
        return p;
    }

    @Autowired(required = false)
    public void setSettingsResolver(SettingsResolver resolver) {
        this.settings = resolver;
    }

    static final String SIMILARITY_THRESHOLD_KEY = "chat.semanticCache.similarityThreshold";
    static final String TTL_KEY = "chat.semanticCache.ttl";
    static final String MAX_ENTRIES_KEY = "chat.semanticCache.maxEntriesPerAgent";

    private double similarityThreshold(UUID agentId) {
        SettingsResolver catalog = settings;
        if (catalog == null) {
            return properties.getSimilarityThreshold();
        }
        try {
            return catalog.getDecimal(SIMILARITY_THRESHOLD_KEY, SettingContext.forAgent(agentId));
        } catch (RuntimeException e) {
            log.warn("Could not read {}; using the platform value: {}", SIMILARITY_THRESHOLD_KEY, e.toString());
            return properties.getSimilarityThreshold();
        }
    }

    private Duration ttl(UUID agentId) {
        SettingsResolver catalog = settings;
        if (catalog == null) {
            return Duration.ofHours(properties.getTtlHours());
        }
        try {
            return catalog.getDuration(TTL_KEY, SettingContext.forAgent(agentId));
        } catch (RuntimeException e) {
            log.warn("Could not read {}; using the platform value: {}", TTL_KEY, e.toString());
            return Duration.ofHours(properties.getTtlHours());
        }
    }

    private int maxEntriesPerAgent(UUID agentId) {
        SettingsResolver catalog = settings;
        if (catalog == null) {
            return properties.getMaxEntriesPerAgent();
        }
        try {
            return catalog.getInt(MAX_ENTRIES_KEY, SettingContext.forAgent(agentId));
        } catch (RuntimeException e) {
            log.warn("Could not read {}; using the platform value: {}", MAX_ENTRIES_KEY, e.toString());
            return properties.getMaxEntriesPerAgent();
        }
    }

    /**
     * @return a cached answer whose stored question is at least the agent's similarity threshold
     *     ({@code chat.semanticCache.similarityThreshold}) cosine-similar to {@code userMessage} and not yet
     *     expired, or empty. Best-effort: any failure (sidecar down, no rows,
     *     Redis/DB hiccup) degrades to empty - a cache is never allowed to break
     *     or block the chat turn, same posture as {@link LlmResponseCacheService}.
     */
    public Optional<String> get(UUID agentId, String principal, String userMessage) {
        return get(agentId, principal, userMessage, "");
    }

    /**
     * As above for a message that follows an earlier conversation: {@code conversationKey} (a digest of the
     * turns before it, {@code ""} for a first message) is folded into the row's {@code subject_hash}, so a
     * near-identical "yes" is only matched against rows stored after the same context. {@code ""} is exactly
     * the principal-only hash (no schema change; rows stored before keep matching a first message).
     */
    public Optional<String> get(UUID agentId, String principal, String userMessage, String conversationKey) {
        try {
            float[] queryVector = sidecarClient.embedQuery(userMessage);
            String literal = toVectorLiteral(queryVector);
            String table = qualifiedTableName();

            // <=> is pgvector's cosine DISTANCE (0 = identical, up to 2). Cosine
            // similarity = 1 - distance, so "similarity >= threshold" is
            // "distance <= 1 - threshold". One scan: fetch the single nearest
            // non-expired row's distance + answer, then apply the threshold in
            // Java (so the log line can report the actual distance on a near-miss).
            List<CacheHit> hits = jdbcTemplate.query(
                "SELECT embedding OPERATOR(public.<=>) ?::public.vector AS distance, answer FROM " + table
                    + " WHERE agent_id = ? AND subject_hash = ? AND expires_at > now() ORDER BY distance ASC LIMIT 1",
                (rs, rowNum) -> new CacheHit(rs.getDouble("distance"), rs.getString("answer")),
                literal, agentId, scopeHash(principal, conversationKey)
            );
            if (hits.isEmpty()) {
                return Optional.empty();
            }
            CacheHit best = hits.get(0);
            double threshold = similarityThreshold(agentId);
            if (best.distance() > (1.0 - threshold)) {
                log.debug("Semantic cache near-miss for agent {} (distance {} > {})", agentId, best.distance(), 1.0 - threshold);
                return Optional.empty();
            }
            log.debug("Semantic cache hit for agent {} (distance {})", agentId, best.distance());
            return Optional.ofNullable(best.answer());
        } catch (RuntimeException e) {
            log.warn("Semantic response cache read failed for agent {} - falling through to a live LLM call: {}", agentId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Stores {@code reply} under {@code userMessage}'s embedding with a TTL, then
     * prunes expired and over-cap rows for this agent. Best-effort - a cache
     * write must never affect the reply the caller already has in hand.
     */
    public void put(UUID agentId, String principal, String userMessage, String reply) {
        put(agentId, principal, userMessage, "", reply);
    }

    /** As above, stored under the scope for a message that follows an earlier conversation; see {@link #get(UUID, String, String, String)}. */
    public void put(UUID agentId, String principal, String userMessage, String conversationKey, String reply) {
        if (reply == null || reply.isBlank()) {
            return;
        }
        try {
            float[] embedding = sidecarClient.embedQuery(userMessage);
            String literal = toVectorLiteral(embedding);
            String table = qualifiedTableName();
            Instant now = Instant.now();
            jdbcTemplate.update(
                "INSERT INTO " + table + " (id, agent_id, subject_hash, question, answer, embedding, created_at, expires_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?::public.vector, ?, ?)",
                UUID.randomUUID(), agentId, scopeHash(principal, conversationKey), userMessage, reply, literal,
                Timestamp.from(now), Timestamp.from(now.plus(ttl(agentId)))
            );
            pruneExpiredAndOverCap(table, agentId);
        } catch (RuntimeException e) {
            log.warn("Semantic response cache write failed for agent {} - reply was still returned normally: {}", agentId, e.getMessage());
        }
    }

    /**
     * Drops this agent's cached rows whose stored question is exactly {@code userMessage}. Used by
     * chat branching's "regenerate" so the answer being regenerated is not served straight back.
     * Best-effort.
     */
    public void evict(UUID agentId, String userMessage) {
        try {
            jdbcTemplate.update(
                "DELETE FROM " + qualifiedTableName() + " WHERE agent_id = ? AND question = ?",
                agentId, userMessage
            );
        } catch (RuntimeException e) {
            log.warn("Semantic response cache evict failed for agent {} - a regenerate may be answered from cache: {}", agentId, e.getMessage());
        }
    }

    /**
     * Deletes this agent's expired rows, then trims its live set back to the agent's cap
     * ({@code chat.semanticCache.maxEntriesPerAgent}) by dropping the oldest - a bounded cache, not
     * unbounded growth (same "fixed memory space" posture {@code
     * AgentMemoryService.pruneOverCap} takes).
     */
    private void pruneExpiredAndOverCap(String table, UUID agentId) {
        jdbcTemplate.update("DELETE FROM " + table + " WHERE agent_id = ? AND expires_at <= now()", agentId);
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM " + table + " WHERE agent_id = ?", Integer.class, agentId
        );
        int cap = maxEntriesPerAgent(agentId);
        if (count == null || count <= cap) {
            return;
        }
        int overflow = count - cap;
        jdbcTemplate.update(
            "DELETE FROM " + table + " WHERE id IN ("
                + "SELECT id FROM " + table + " WHERE agent_id = ? ORDER BY created_at ASC LIMIT ?)",
            agentId, overflow
        );
    }

    private static String qualifiedTableName() {
        // TenantSchemaResolver.currentSchemaName() is already restricted to
        // [a-z0-9_] (SchemaNameSanitizer), so this is safe to concatenate;
        // quoting is for correctness (case/reserved words), not injection
        // defense - identical to AgentMemorySchemaSupport.
        String schema = TenantSchemaResolver.currentSchemaName();
        return "\"" + schema.replace("\"", "\"\"") + "\".llm_response_cache";
    }

    // Package-private (not private) so SemanticResponseCacheServiceTest can
    // construct the exact row type the mocked JdbcTemplate must hand back.
    record CacheHit(double distance, String answer) {
    }

    /**
     * The {@code subject_hash} a row is bound to: the principal's hash (SEC-052), or, for a message that
     * follows an earlier conversation, the hash of the principal and the conversation key together.
     */
    static String scopeHash(String principal, String conversationKey) {
        return conversationKey == null || conversationKey.isBlank()
            ? subjectHash(principal)
            : subjectHash((principal == null ? "" : principal) + '\u001f' + conversationKey);
    }

    /** SEC-052: sha256 hex of the principal - never the raw subject/phone number/visitor id in a table column. */
    static String subjectHash(String principal) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                digest.digest((principal == null ? "" : principal).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String toVectorLiteral(float[] embedding) {
        return IntStream.range(0, embedding.length)
            .mapToObj(i -> String.valueOf(embedding[i]))
            .collect(Collectors.joining(",", "[", "]"));
    }
}
