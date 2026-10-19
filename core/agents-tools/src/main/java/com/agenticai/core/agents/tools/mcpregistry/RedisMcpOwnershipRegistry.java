package com.agenticai.core.agents.tools.mcpregistry;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiPredicate;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@code app.mcp.registry-store=redis}. Lease key {@code mcp:owner:{tenant}:{registrationId}} =
 * owner replica id, with a TTL renewed by {@link #renewOwned}. A crashed owner's lease simply
 * expires; only then can another replica {@link #claim} it (SET NX), so two replicas never both
 * believe they own one registration. Renewal is get-then-expire (not atomic): the worst case is one
 * extra TTL on a lease that has just expired, which never lets a second owner in early.
 */
public final class RedisMcpOwnershipRegistry implements McpOwnershipRegistry {

    private static final Logger log = LoggerFactory.getLogger(RedisMcpOwnershipRegistry.class);
    static final String OWNER_PREFIX = "mcp:owner:";
    static final String REPLICAS_KEY = "mcp:replicas";

    private final StringRedisTemplate redis;
    private final String replicaId;
    private final Duration ttl;
    private final AtomicBoolean warned = new AtomicBoolean();

    /** Leases this replica believes it holds: redis key -> (tenant, id, claimedAt). */
    private final Map<String, Held> held = new ConcurrentHashMap<>();

    private record Held(String tenant, UUID registrationId, Instant since) {}

    public RedisMcpOwnershipRegistry(StringRedisTemplate redis, String replicaId, Duration ttl) {
        this.redis = redis;
        this.replicaId = replicaId;
        this.ttl = ttl;
    }

    static String key(String tenant, UUID registrationId) {
        return OWNER_PREFIX + tenant + ":" + registrationId;
    }

    @Override
    public String replicaId() {
        return replicaId;
    }

    @Override
    public boolean active() {
        return true;
    }

    @Override
    public boolean claim(String tenant, UUID registrationId) {
        String key = key(tenant, registrationId);
        return failOpen(
                () -> {
                    for (int attempt = 0; attempt < 2; attempt++) {
                        if (Boolean.TRUE.equals(
                                redis.opsForValue().setIfAbsent(key, replicaId, ttl))) {
                            held.put(key, new Held(tenant, registrationId, Instant.now()));
                            return true;
                        }
                        String current = redis.opsForValue().get(key);
                        if (replicaId.equals(current)) {
                            redis.expire(key, ttl);
                            held.putIfAbsent(key, new Held(tenant, registrationId, Instant.now()));
                            return true;
                        }
                        if (current != null) {
                            return false;
                        }
                        // expired between SET NX and GET: one more try
                    }
                    return false;
                },
                true);
    }

    @Override
    public Optional<String> ownerOf(String tenant, UUID registrationId) {
        return failOpen(
                () -> Optional.ofNullable(redis.opsForValue().get(key(tenant, registrationId))),
                Optional.empty());
    }

    @Override
    public boolean storeReachable() {
        try {
            redis.hasKey(REPLICAS_KEY);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public void release(String tenant, UUID registrationId) {
        String key = key(tenant, registrationId);
        held.remove(key);
        failOpen(
                () -> {
                    if (replicaId.equals(redis.opsForValue().get(key))) {
                        redis.delete(key);
                    }
                    return null;
                },
                null);
    }

    @Override
    public void renewOwned(BiPredicate<String, UUID> stillNeeded, Duration grace) {
        Instant graceCutoff = Instant.now().minus(grace);
        for (Map.Entry<String, Held> e : List.copyOf(held.entrySet())) {
            Held h = e.getValue();
            if (stillNeeded.test(h.tenant(), h.registrationId())
                    || h.since().isAfter(graceCutoff)) {
                failOpen(
                        () -> {
                            String current = redis.opsForValue().get(e.getKey());
                            if (replicaId.equals(current)) {
                                redis.expire(e.getKey(), ttl);
                            } else {
                                held.remove(e.getKey()); // lost the lease (expired and taken over)
                            }
                            return null;
                        },
                        null);
            } else {
                release(h.tenant(), h.registrationId());
            }
        }
    }

    @Override
    public void registerReplica() {
        failOpen(
                () -> {
                    long now = System.currentTimeMillis();
                    redis.opsForZSet().add(REPLICAS_KEY, replicaId, now);
                    redis.opsForZSet().removeRangeByScore(REPLICAS_KEY, 0, now - ttl.toMillis());
                    return null;
                },
                null);
    }

    @Override
    public List<String> liveReplicas() {
        return failOpen(
                () -> {
                    Set<String> ids =
                            redis.opsForZSet()
                                    .rangeByScore(
                                            REPLICAS_KEY,
                                            System.currentTimeMillis() - ttl.toMillis(),
                                            Double.POSITIVE_INFINITY);
                    return ids == null ? List.<String>of() : new ArrayList<>(ids);
                },
                List.<String>of());
    }

    private <T> T failOpen(Supplier<T> op, T fallback) {
        try {
            return op.get();
        } catch (RuntimeException e) {
            if (warned.compareAndSet(false, true)) {
                log.warn(
                        "MCP ownership registry: Redis unavailable, failing open to local in-memory behaviour "
                                + "(logged once): {}",
                        e.toString());
            }
            return fallback;
        }
    }
}
