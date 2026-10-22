package com.agenticai.core.platform.abuse;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.resilience.RateLimitedLog;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Phase P5 (Fraud & Anomaly) - the reversible THROTTLE enforcement. Holds a
 * TTL-bounded set of throttled principals ({@code tenantId:userId}) that other
 * services can consult via {@link #isThrottled} to tighten that one principal's
 * rate/budget, without the anomaly layer having to reach into every hot-path
 * limiter. Same pragmatic posture as {@code LlmGatewayRateLimiterService}: this
 * is a safeguard marker, not a correctness gate, and it self-expires - a lost
 * marker fails OPEN (the principal is un-throttled), which is the correct
 * failure direction for a safeguard.
 *
 * <p><b>Store ({@code app.abuse.throttle-store}):</b> {@code memory} (default,
 * a per-JVM {@link ConcurrentHashMap}: each replica's sweep sets its own
 * marker, so with N replicas a throttle set by one is invisible to the others
 * and {@link #current()} lists only this replica's) or {@code redis} (a marker
 * key {@code abuse:throttle:<tenantId>:<userId|*>} written with the throttle's
 * TTL, so it is global, expires on its own, and {@link #clear} on any replica
 * lifts it everywhere). Keys embed the tenant id, so markers are tenant-scoped.
 * The write is a plain {@code SET ... EX}, NOT {@code SET NX}: a repeated
 * throttle deliberately REFRESHES the expiry (documented behaviour of
 * {@link #throttle}), which NX would defeat. The in-memory map is still
 * written in redis mode and is the fallback when Redis errors (an error is
 * logged, never thrown - same fail-open posture as
 * {@code ToolCircuitBreakerService}); while Redis answers it is authoritative.
 *
 * <p>Every set/clear is audited via {@link AuditLogService} so the action is
 * traceable and, crucially, reversible on the record: {@link #clear} is the
 * explicit un-throttle an admin endpoint calls, and expiry is the automatic one.
 */
@ConditionalOnEditionFeature(EditionFeature.OPERATOR_CONSOLE)
@Service
public class AbuseThrottleService {

    private static final Logger log = LoggerFactory.getLogger(AbuseThrottleService.class);
    /** One WARN per minute per call site while a dependency is down, instead of one (with a stack trace) per request. */
    private static final RateLimitedLog REDIS_DOWN_LOG = new RateLimitedLog(log, Duration.ofMinutes(1));
    private static final String ACTION_THROTTLE = "abuse.throttle.set";
    private static final String ACTION_CLEAR = "abuse.throttle.clear";
    private static final String TARGET_TYPE = "abuse-principal";
    private static final String KEY_PREFIX = "abuse:throttle:";

    private final AuditLogService auditLogService;
    private final Map<String, Throttle> throttled = new ConcurrentHashMap<>();
    /** Non-null only when {@code app.abuse.throttle-store=redis} and a template is available. */
    private final StringRedisTemplate redis;

    /** In-memory store - the default, and the constructor unit tests use. */
    public AbuseThrottleService(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
        this.redis = null;
    }

    @Autowired
    public AbuseThrottleService(
        AuditLogService auditLogService,
        ObjectProvider<StringRedisTemplate> redisProvider,
        @Value("${app.abuse.throttle-store:memory}") String store
    ) {
        this(auditLogService, "redis".equalsIgnoreCase(store) ? redisProvider.getIfAvailable() : null);
    }

    /** Test seam - Redis-backed when {@code redis} is non-null. */
    AbuseThrottleService(AuditLogService auditLogService, StringRedisTemplate redis) {
        this.auditLogService = auditLogService;
        this.redis = redis;
    }

    /** Principal key - the same {@code tenantId:userId} form used as the issue {@code sourceId}. */
    public static String principalKey(String tenantId, String userId) {
        return tenantId + ":" + (userId == null ? "*" : userId);
    }

    /** Throttle a principal for {@code ttl}, recording who/why. A repeated throttle refreshes the expiry. */
    public void throttle(String tenantId, String userId, String reason, Duration ttl, String actor) {
        String key = principalKey(tenantId, userId);
        Instant expiresAt = Instant.now().plus(ttl);
        throttled.put(key, new Throttle(reason, expiresAt));
        redisPut(key, reason, expiresAt, ttl);
        safeAudit(ACTION_THROTTLE, key, AuditOutcome.SUCCESS,
            "Throttled until " + expiresAt + " - " + reason, actor);
        log.info("Throttled principal {} until {} - {}", key, expiresAt, reason);
    }

    /** Explicitly un-throttle (the reversible action) - a no-op, still audited, if not currently throttled. */
    public void clear(String tenantId, String userId, String actor) {
        String key = principalKey(tenantId, userId);
        Throttle removed = throttled.remove(key);
        redisDelete(key);
        safeAudit(ACTION_CLEAR, key, AuditOutcome.SUCCESS,
            removed == null ? "Cleared throttle (was not throttled)" : "Cleared throttle", actor);
        log.info("Cleared throttle for principal {}", key);
    }

    /** Whether this principal is currently throttled - expired entries are lazily purged and read as false. */
    public boolean isThrottled(String tenantId, String userId) {
        String key = principalKey(tenantId, userId);
        if (redis != null) {
            try {
                return Boolean.TRUE.equals(redis.hasKey(KEY_PREFIX + key));
            } catch (RuntimeException e) {
                REDIS_DOWN_LOG.warnf("Throttle check for {} failed - Redis unreachable? Using this replica's in-memory marker.", key, e);
            }
        }
        Throttle t = throttled.get(key);
        if (t == null) {
            return false;
        }
        if (t.expiresAt().isBefore(Instant.now())) {
            throttled.remove(key, t);
            return false;
        }
        return true;
    }

    /** Live snapshot of currently-throttled principals (expired ones purged first) - backs the admin read endpoint. */
    public List<ThrottleEntry> current() {
        if (redis != null) {
            List<ThrottleEntry> fromRedis = currentFromRedis();
            if (fromRedis != null) {
                return fromRedis;
            }
        }
        Instant now = Instant.now();
        List<ThrottleEntry> out = new ArrayList<>();
        throttled.forEach((key, t) -> {
            if (t.expiresAt().isBefore(now)) {
                throttled.remove(key, t);
            } else {
                out.add(new ThrottleEntry(key, t.reason(), t.expiresAt()));
            }
        });
        return out;
    }

    // ---- Redis mode -------------------------------------------------------

    /** Value is {@code <expiresAtEpochMs>|<reason>}; the key's TTL does the expiry, the value only feeds the admin view. */
    private void redisPut(String key, String reason, Instant expiresAt, Duration ttl) {
        if (redis == null) {
            return;
        }
        try {
            if (ttl.isNegative() || ttl.isZero()) {
                redis.delete(KEY_PREFIX + key); // already expired: same as the in-memory lazy purge
                return;
            }
            redis.opsForValue().set(KEY_PREFIX + key, expiresAt.toEpochMilli() + "|" + reason, ttl);
        } catch (RuntimeException e) {
            REDIS_DOWN_LOG.warnf("Throttle marker for {} could not be written to Redis - this replica's in-memory marker only", key, e);
        }
    }

    private void redisDelete(String key) {
        if (redis == null) {
            return;
        }
        try {
            redis.delete(KEY_PREFIX + key);
        } catch (RuntimeException e) {
            REDIS_DOWN_LOG.warnf("Throttle marker for {} could not be deleted from Redis - it will lapse at its TTL", key, e);
        }
    }

    /** @return the live entries, or null when Redis is unreachable (caller falls back to the in-memory view). */
    private List<ThrottleEntry> currentFromRedis() {
        try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions().match(KEY_PREFIX + "*").count(500).build())) {
            List<ThrottleEntry> out = new ArrayList<>();
            Instant now = Instant.now();
            while (cursor.hasNext()) {
                String redisKey = cursor.next();
                String value = redis.opsForValue().get(redisKey);
                if (value == null) {
                    continue; // expired between SCAN and GET
                }
                int sep = value.indexOf('|');
                Instant expiresAt = null;
                try {
                    expiresAt = Instant.ofEpochMilli(Long.parseLong(sep < 0 ? value : value.substring(0, sep)));
                } catch (NumberFormatException ignored) {
                    // unreadable marker: still throttled (the key exists), expiry unknown -> skip below
                }
                if (expiresAt == null || expiresAt.isBefore(now)) {
                    continue;
                }
                out.add(new ThrottleEntry(redisKey.substring(KEY_PREFIX.length()),
                    sep < 0 ? "" : value.substring(sep + 1), expiresAt));
            }
            return out;
        } catch (RuntimeException e) {
            REDIS_DOWN_LOG.warnf("Throttle listing from Redis failed - showing this replica's in-memory view", e);
            return null;
        }
    }

    private void safeAudit(String action, String key, AuditOutcome outcome, String detail, String actor) {
        try {
            auditLogService.record(actor, action, TARGET_TYPE, key, outcome, detail);
        } catch (RuntimeException e) {
            // Auditing must never break the safeguard itself (same posture as PlatformIssueService's callers).
            log.warn("Failed to audit {} for {} (swallowed): {}", action, key, e.toString());
        }
    }

    private record Throttle(String reason, Instant expiresAt) {}

    /** Admin-facing view of one active throttle. */
    public record ThrottleEntry(String principal, String reason, Instant expiresAt) {}
}
