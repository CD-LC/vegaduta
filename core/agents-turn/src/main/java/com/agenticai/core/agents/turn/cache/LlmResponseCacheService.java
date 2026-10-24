package com.agenticai.core.agents.turn.cache;

import com.agenticai.core.resilience.RateLimitedLog;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Master Roadmap 2026 item #18 (cost optimization: routing + semantic
 * caching, §G) - the "semantic caching" half (GPTCache's stated role in the
 * roadmap entry). Redis-backed, same infrastructure {@link
 * com.agenticai.core.agents.memory.SessionMemoryService} already uses for
 * conversation memory - a cache is inherently ephemeral by nature, so Redis
 * (with a TTL) is the right store, not a new Postgres table.
 *
 * <p><b>Exact-match (with light normalization), not embedding-based semantic
 * similarity - a deliberate scope decision for this first pass.</b> GPTCache
 * itself supports both an exact-match tier and a vector-similarity tier; this
 * class only implements the former. Reasoning: a similarity-based cache can
 * return a cached answer for a DIFFERENT question that merely scored above a
 * similarity threshold - a wrong-answer risk that's easy to introduce and
 * hard to notice, especially for a first pass with no compiled/tested
 * embedding-distance tuning available in this environment. Exact-match
 * (after trimming/lowercasing/whitespace-collapsing, so trivial formatting
 * differences don't cause spurious misses) captures the highest-confidence,
 * zero-ambiguity slice of the same idea - the literal repeated question -
 * with none of that risk. Embedding-based fuzzy matching (reusing the
 * platform's existing pgvector/embedding-sidecar infrastructure from the
 * Knowledge Base pipeline) is a real, natural follow-up, not a permanently
 * skipped gap.
 *
 * <p><b>Opt-in per agent, not automatic for every agent.</b> See {@link
 * Agent#responseCachingEnabled}'s javadoc for the full reasoning: whether
 * identical questions should get identical cached answers is a property of
 * what an agent is FOR (an FAQ/informational agent: yes; an agent whose
 * replies depend on live/per-caller data: no), which only the tenant who
 * configured that agent actually knows.
 *
 * <p><b>Scope note on WHEN a cache read or write happens</b>: {@link
 * com.agenticai.core.agents.turn.AgentChatService#chat} uses this cache
 * only for the plain default turn shape - no attachment, no per-call extra
 * delegation tools, and (since {@code app.chat.cache.eligible-with-tools} defaults to
 * false) no agent that offers tools at all, so a turn that read live data (a stock
 * price, "what time is it") is neither served from nor stored in the cache. The key
 * is the normalised message plus, for a message that is not the first of its
 * conversation, a digest of the turns before it ({@code app.chat.cache.key-on-conversation}),
 * so "yes" is not answered with another conversation's "yes". An operator who sets
 * {@code eligible-with-tools} back to true gets the old behaviour, including its
 * staleness caveat.
 */
@Service
public class LlmResponseCacheService {

    private static final Logger log = LoggerFactory.getLogger(LlmResponseCacheService.class);
    /** One WARN per minute per call site while a dependency is down, instead of one (with a stack trace) per request. */
    private static final RateLimitedLog REDIS_DOWN_LOG = new RateLimitedLog(log, Duration.ofMinutes(1));
    private static final String KEY_PREFIX = "llmcache:";

    private final StringRedisTemplate redisTemplate;
    private final Duration ttl;

    public LlmResponseCacheService(
        StringRedisTemplate redisTemplate,
        @Value("${app.chat.response-cache-ttl-hours:6}") long ttlHours
    ) {
        this.redisTemplate = redisTemplate;
        this.ttl = Duration.ofHours(ttlHours);
    }

    /**
     * @param principal SEC-052: the caller's stable identity (the chat {@code subjectKey} -
     *     user subject, API-key label, channel external id or share visitor). Part of the key,
     *     so an answer personalised by one user's memories or OAuth tools is never served to
     *     another user who asks the same question.
     */
    public Optional<String> get(String tenantId, UUID agentId, String principal, String userMessage) {
        return get(tenantId, agentId, principal, userMessage, "");
    }

    /**
     * As above for a message that follows an earlier conversation: {@code conversationKey} (a digest of the
     * turns before it, {@code ""} for a first message) is part of the key, so "yes" after one question is a
     * different entry from "yes" after another. {@code ""} is exactly the message-only key.
     */
    public Optional<String> get(
            String tenantId, UUID agentId, String principal, String userMessage, String conversationKey) {
        try {
            String cached = redisTemplate.opsForValue().get(buildKey(tenantId, agentId, principal, userMessage, conversationKey));
            return Optional.ofNullable(cached);
        } catch (RuntimeException e) {
            // Best-effort, same posture as SessionMemoryService's own Redis
            // failure handling elsewhere - a cache outage must degrade to
            // "always call the LLM", never fail the chat turn itself.
            REDIS_DOWN_LOG.warnf("LLM response cache read failed for agent {} - falling through to a live LLM call: {}", agentId, e.getMessage());
            return Optional.empty();
        }
    }

    public void put(String tenantId, UUID agentId, String principal, String userMessage, String reply) {
        put(tenantId, agentId, principal, userMessage, "", reply);
    }

    /** As above, under the key for a message that follows an earlier conversation; see {@link #get(String, UUID, String, String, String)}. */
    public void put(
            String tenantId, UUID agentId, String principal, String userMessage, String conversationKey, String reply) {
        if (reply == null || reply.isBlank()) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(buildKey(tenantId, agentId, principal, userMessage, conversationKey), reply, ttl);
        } catch (RuntimeException e) {
            REDIS_DOWN_LOG.warnf("LLM response cache write failed for agent {} - reply was still returned to the caller normally: {}", agentId, e.getMessage());
        }
    }

    /**
     * Drops the cached reply for this exact message (same normalisation as {@link #get}). Used by
     * chat branching's "regenerate": without it the answer being regenerated would simply be served
     * straight back from this cache. {@code principal} is the same caller identity {@link #get} keys on
     * (SEC-052), so only this caller's cached reply is dropped. Best-effort, like every other call here.
     */
    public void evict(String tenantId, UUID agentId, String principal, String userMessage) {
        evict(tenantId, agentId, principal, userMessage, "");
    }

    /** As above, for the key of a message that follows an earlier conversation. */
    public void evict(
            String tenantId, UUID agentId, String principal, String userMessage, String conversationKey) {
        try {
            redisTemplate.delete(buildKey(tenantId, agentId, principal, userMessage, conversationKey));
        } catch (RuntimeException e) {
            REDIS_DOWN_LOG.warnf("LLM response cache evict failed for agent {} - a regenerate may be answered from cache: {}", agentId, e.getMessage());
        }
    }

    private String buildKey(
            String tenantId, UUID agentId, String principal, String userMessage, String conversationKey) {
        String scope = tenantId == null || tenantId.isBlank() ? "public" : tenantId;
        // SEC-052: "u" segment = sha256 of the principal (hashed so no raw
        // subject/phone number/visitor id sits in a Redis key name). The old
        // tenant:agent:msg keys are never read again and age out on their TTL.
        // The conversation segment is present only when there is a conversation before the message, so the
        // key of a first message (and of a message-only caller) is byte-for-byte what it always was.
        String conversation = conversationKey == null || conversationKey.isBlank() ? "" : ":c" + sha256(conversationKey);
        return KEY_PREFIX + scope + ":" + agentId + ":u" + sha256(principal == null ? "" : principal)
            + ":" + sha256(normalize(userMessage)) + conversation;
    }

    /** Trim + lowercase + collapse internal whitespace - catches trivial formatting variance without any embedding/similarity logic. */
    private static String normalize(String userMessage) {
        return userMessage == null ? "" : userMessage.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a JDK-guaranteed algorithm (JLS/JCA standard
            // provider) - this branch is unreachable in practice, kept only
            // because MessageDigest.getInstance's checked exception forces a
            // handler. Fails the cache lookup gracefully rather than the
            // whole chat turn if it somehow ever did occur.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
