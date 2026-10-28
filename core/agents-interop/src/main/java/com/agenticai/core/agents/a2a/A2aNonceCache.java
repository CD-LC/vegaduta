package com.agenticai.core.agents.a2a;

import com.agenticai.core.security.TenantContext;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Replay protection for signed A2A requests: remembers each accepted {@code (tenant, key id,
 * nonce)} for {@code 2 x timestamp-window} (a nonce can be replayed from the moment its timestamp
 * enters the window until it leaves it, which is at most that long after it was first accepted).
 *
 * <p>Two layers, both bounded:
 *
 * <ul>
 *   <li><b>in memory</b>, per tenant, insertion ordered (every entry has the same TTL, so the head
 *       is always the oldest) and capped at {@code max-nonces-per-tenant}. When the cap is reached
 *       with only live nonces, {@link #claim} refuses rather than evicting one - an evicted live
 *       nonce would be a replay hole. Per replica.
 *   <li><b>Redis</b> ({@code SET NX} with the TTL), used when {@code app.a2a.task-store=redis} and a
 *       Redis connection exists, so a replay sent to a different core replica is caught too. A Redis
 *       error falls back to the in-memory answer (logged), the same posture as the task store.
 * </ul>
 *
 * Callers claim a nonce only AFTER the signature verified, so unauthenticated garbage cannot fill
 * the cache.
 */
@Component
public class A2aNonceCache {

    private static final Logger log = LoggerFactory.getLogger(A2aNonceCache.class);
    private static final String REDIS_PREFIX = "a2a:nonce:";
    private static final String NO_TENANT = "_none";

    /** Result of {@link #claim}. */
    public enum Claim {
        /** First sighting; the nonce is now remembered. */
        FRESH,
        /** Seen before inside its TTL: a replay. */
        REPLAYED,
        /** The tenant's cache is full of live nonces; the request must be refused. */
        FULL
    }

    private final Duration ttl;
    private final int maxPerTenant;
    private final Clock clock;
    private final StringRedisTemplate redis;
    private final Map<String, LinkedHashMap<String, Long>> byTenant = new HashMap<>();

    @Autowired
    public A2aNonceCache(
            A2aMessageSecurityProperties security,
            A2aProperties properties,
            ObjectProvider<StringRedisTemplate> redisProvider) {
        this(
                security.getServer().getTimestampWindow().multipliedBy(2),
                security.getServer().getMaxNoncesPerTenant(),
                Clock.systemUTC(),
                "redis".equalsIgnoreCase(properties.getTaskStore())
                        ? redisProvider.getIfAvailable()
                        : null);
    }

    A2aNonceCache(Duration ttl, int maxPerTenant, Clock clock, StringRedisTemplate redis) {
        this.ttl = ttl;
        this.maxPerTenant = Math.max(1, maxPerTenant);
        this.clock = clock;
        this.redis = redis;
    }

    /** Claims {@code nonce} for the CURRENT tenant. */
    public Claim claim(String keyId, String nonce) {
        String tenant = TenantContext.current().map(TenantContext::getTenantId).orElse(NO_TENANT);
        return claim(tenant == null ? NO_TENANT : tenant, keyId, nonce);
    }

    Claim claim(String tenant, String keyId, String nonce) {
        String key = keyId + "|" + nonce;
        Claim local = claimLocal(tenant, key);
        if (local != Claim.FRESH || redis == null) {
            return local;
        }
        try {
            Boolean first =
                    redis.opsForValue().setIfAbsent(REDIS_PREFIX + tenant + ":" + key, "1", ttl);
            if (Boolean.FALSE.equals(first)) {
                return Claim.REPLAYED;
            }
        } catch (RuntimeException e) {
            log.warn("A2A nonce cache: Redis unavailable, using the per-replica cache only", e);
        }
        return Claim.FRESH;
    }

    private Claim claimLocal(String tenant, String key) {
        long now = clock.millis();
        synchronized (byTenant) {
            LinkedHashMap<String, Long> nonces =
                    byTenant.computeIfAbsent(tenant, t -> new LinkedHashMap<>());
            for (Iterator<Long> it = nonces.values().iterator(); it.hasNext(); ) {
                if (it.next() > now) {
                    break; // insertion order == expiry order: the rest is live
                }
                it.remove();
            }
            Long seen = nonces.get(key);
            if (seen != null && seen > now) {
                return Claim.REPLAYED;
            }
            if (nonces.size() >= maxPerTenant) {
                return Claim.FULL;
            }
            nonces.put(key, now + ttl.toMillis());
            return Claim.FRESH;
        }
    }

    int size(String tenant) {
        synchronized (byTenant) {
            LinkedHashMap<String, Long> nonces = byTenant.get(tenant);
            return nonces == null ? 0 : nonces.size();
        }
    }
}
