package com.agenticai.core.agents.turn;

import com.agenticai.core.guardrails.taint.ConversationTaintStore;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis-backed {@link ConversationTaintStore}: one key per conversation, {@code
 * agentic:session-taint:<tenant>:<scoped session id>}, on the same TTL as the session history
 * ({@code app.chat.session-ttl-hours}) and refreshed whenever the flag is read, so the flag
 * outlives the history it describes. The scoped session id is the SEC-004 digest of tenant, agent,
 * principal and client session id; the tenant id is in the key as well, so no id can ever collide
 * across tenants. No migration: Redis is already where the session lives.
 */
@Component
class RedisConversationTaintStore implements ConversationTaintStore {

    static final String KEY_PREFIX = "agentic:session-taint:";

    private final StringRedisTemplate redis;
    private final Duration ttl;

    RedisConversationTaintStore(
            StringRedisTemplate redis,
            @Value("${app.chat.session-ttl-hours:24}") long sessionTtlHours) {
        this.redis = redis;
        this.ttl = Duration.ofHours(Math.max(1, sessionTtlHours));
    }

    static String key(String tenantId, String conversationId) {
        return KEY_PREFIX + tenantId + ":" + conversationId;
    }

    @Override
    public boolean isTainted(String tenantId, String conversationId) {
        String key = key(tenantId, conversationId);
        boolean tainted = Boolean.TRUE.equals(redis.hasKey(key));
        if (tainted) {
            redis.expire(key, ttl);
        }
        return tainted;
    }

    @Override
    public void markTainted(String tenantId, String conversationId) {
        redis.opsForValue().set(key(tenantId, conversationId), "1", ttl);
    }
}
