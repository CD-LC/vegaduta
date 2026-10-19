package com.agenticai.core.agents.memory;

import com.agenticai.core.security.TenantContext;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The session owner record ({@code agentic:session-owner:<id>}, see {@link
 * SessionMemoryService#OWNER_KEY_PREFIX}): written once by the first append made with a tenant
 * context, read only by {@link SessionMemoryService#isOwnedBy} (chat export). Not a Spring bean.
 */
final class SessionOwnerRecords {

    private static final Logger log = LoggerFactory.getLogger(SessionMemoryService.class);

    private final StringRedisTemplate redisTemplate;
    private final Duration sessionTtl;

    SessionOwnerRecords(StringRedisTemplate redisTemplate, Duration sessionTtl) {
        this.redisTemplate = redisTemplate;
        this.sessionTtl = sessionTtl;
    }

    static String ownerValue(String tenantId, String subject) {
        if (tenantId == null || tenantId.isBlank() || subject == null || subject.isBlank()) {
            return null;
        }
        return tenantId + "\n" + subject;
    }

    /**
     * Records who started this session, once: the first append made with a tenant context wins
     * ({@code SETNX}) and the record shares the history list's TTL. Never throws and never blocks
     * the chat path - an unrecorded session is simply not exportable ({@link SessionMemoryService#isOwnedBy}), which
     * fails closed.
     */
    void bindOwner(String sessionId) {
        try {
            Optional<TenantContext> ctx = TenantContext.current();
            if (ctx.isEmpty()) {
                return;
            }
            String owner = ownerValue(ctx.get().getTenantId(), ctx.get().getSubject());
            if (owner == null) {
                return;
            }
            String ownerKey = SessionMemoryService.ownerKey(sessionId);
            redisTemplate.opsForValue().setIfAbsent(ownerKey, owner, sessionTtl);
            redisTemplate.expire(ownerKey, sessionTtl);
        } catch (RuntimeException e) {
            log.debug("Could not record the owner of session {}", sessionId, e);
        }
    }

    /**
     * True only when this session has a recorded owner and it is exactly the given tenant and
     * subject. A session with no record (started before owner records existed, expired record,
     * Redis trouble) answers false.
     */
    boolean isOwnedBy(String sessionId, String tenantId, String subject) {
        String expected = ownerValue(tenantId, subject);
        if (expected == null || sessionId == null) {
            return false;
        }
        try {
            String stored = redisTemplate.opsForValue().get(SessionMemoryService.ownerKey(sessionId));
            return expected.equals(stored);
        } catch (RuntimeException e) {
            log.debug("Could not read the owner of session {}", sessionId, e);
            return false;
        }
    }
}
