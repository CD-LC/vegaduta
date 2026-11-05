package com.agenticai.core.agents.a2a;

import com.agenticai.core.security.TenantContext;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Single-use nonce cache for inbound A2A requests, scoped per tenant (the same nonce under two
 * tenants is two different nonces).
 *
 * <p><b>Backends.</b> With {@code app.a2a.task-store=redis} (the existing multi-replica switch for
 * A2A state) and a {@link StringRedisTemplate} present, claims are an atomic {@code SET NX EX},
 * shared by every replica. Otherwise the cache is in-memory and PER REPLICA: behind a load balancer
 * with N replicas a replay can succeed once per replica inside the window. A Redis error falls back
 * to the local cache (the same fail-open-to-local stance as {@link A2aTaskStore}).
 *
 * <p>The in-memory side is bounded: entries expire after the TTL and each tenant is capped at
 * {@code nonceCacheMaxPerTenant}; a full tenant gets {@link Outcome#FULL} (refused), so one noisy
 * tenant cannot evict another's nonces. The Redis side is bounded by key TTL only.
 */
@Component
public class A2aNonceStore {

    private static final Logger log = LoggerFactory.getLogger(A2aNonceStore.class);
    private static final String NO_TENANT = "_none";

    public enum Outcome {
        ACCEPTED,
        REPLAY,
        FULL
    }

    private final Clock clock;
    private final int maxPerTenant;
    private final StringRedisTemplate redis;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Autowired
    public A2aNonceStore(
            A2aProperties properties,
            A2aMessageSecurityProperties security,
            ObjectProvider<StringRedisTemplate> redisProvider) {
        this(
                Clock.systemUTC(),
                security.getNonceCacheMaxPerTenant(),
                "redis".equalsIgnoreCase(properties.getTaskStore())
                        ? redisProvider.getIfAvailable()
                        : null);
    }

    A2aNonceStore(Clock clock, int maxPerTenant, StringRedisTemplate redis) {
        this.clock = clock;
        this.maxPerTenant = Math.max(1, maxPerTenant);
        this.redis = redis;
    }

    /** Atomically claims {@code nonce} for the current tenant for {@code ttl}. */
    public Outcome claim(String nonce, Duration ttl) {
        String tenant = TenantContext.current().map(TenantContext::getTenantId).orElse(NO_TENANT);
        if (tenant == null || tenant.isBlank()) {
            tenant = NO_TENANT;
        }
        return claim(tenant, nonce, ttl);
    }

    Outcome claim(String tenant, String nonce, Duration ttl) {
        if (redis != null) {
            try {
                Boolean fresh =
                        redis.opsForValue()
                                .setIfAbsent("a2a:nonce:" + tenant + ":" + nonce, "1", ttl);
                return Boolean.TRUE.equals(fresh) ? Outcome.ACCEPTED : Outcome.REPLAY;
            } catch (RuntimeException e) {
                log.warn("A2A nonce Redis claim failed; using the local cache", e);
            }
        }
        Bucket bucket = buckets.computeIfAbsent(tenant, t -> new Bucket());
        return bucket.claim(nonce, clock.millis(), ttl.toMillis(), maxPerTenant);
    }

    /** Drops tenants with no live nonces. */
    void sweep() {
        long now = clock.millis();
        buckets.entrySet().removeIf(e -> e.getValue().purgeAndIsEmpty(now));
    }

    private static final class Bucket {
        private final Map<String, Long> expiry = new HashMap<>();
        private long lastPurge;

        synchronized Outcome claim(String nonce, long now, long ttlMillis, int max) {
            if (expiry.size() >= max || now - lastPurge > 1000) {
                purge(now);
            }
            Long existing = expiry.get(nonce);
            if (existing != null && existing > now) {
                return Outcome.REPLAY;
            }
            if (existing == null && expiry.size() >= max) {
                return Outcome.FULL;
            }
            expiry.put(nonce, now + ttlMillis);
            return Outcome.ACCEPTED;
        }

        synchronized boolean purgeAndIsEmpty(long now) {
            purge(now);
            return expiry.isEmpty();
        }

        private void purge(long now) {
            lastPurge = now;
            Iterator<Map.Entry<String, Long>> it = expiry.entrySet().iterator();
            while (it.hasNext()) {
                if (it.next().getValue() <= now) {
                    it.remove();
                }
            }
        }
    }
}
