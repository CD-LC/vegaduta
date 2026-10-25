package com.agenticai.core.agents.tools.search;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@code app.tools.search.activation-store=redis}. One Redis list per session key ({@code
 * toolsearch:activations:{tenant|agent|session}}) holds the activated tool names most recent last;
 * the key's expiry is the session TTL, renewed on every touch like the memory store's sliding
 * window. Every read and write goes to Redis - nothing per-replica is the source of truth - so
 * activations survive a restart and follow the session across replicas.
 *
 * <p>Fail-open like {@code RedisMcpOwnershipRegistry}: when Redis is unreachable {@link #remember}
 * drops the write and {@link #recall} answers empty, so the worst case is the model searching once
 * more. Concurrent writes from two replicas can interleave the remove+push pair, which at worst
 * reorders names - acceptable for a best-effort carry-over.
 */
public final class RedisToolActivationStore implements ToolActivationStore {

    private static final Logger log = LoggerFactory.getLogger(RedisToolActivationStore.class);
    static final String KEY_PREFIX = "toolsearch:activations:";

    private final StringRedisTemplate redis;
    private final AtomicBoolean warned = new AtomicBoolean();

    public RedisToolActivationStore(StringRedisTemplate redis) {
        this.redis = Objects.requireNonNull(redis, "redis");
    }

    static String redisKey(String key) {
        return KEY_PREFIX + key;
    }

    @Override
    public void remember(String key, Collection<String> names, int maxNames, Duration ttl) {
        if (key == null || names == null || names.isEmpty() || maxNames <= 0) {
            return;
        }
        try {
            String redisKey = redisKey(key);
            for (String name : names) {
                if (name == null || name.isBlank()) {
                    continue;
                }
                redis.opsForList().remove(redisKey, 0, name);
                redis.opsForList().rightPush(redisKey, name);
            }
            redis.opsForList().trim(redisKey, -maxNames, -1);
            touch(redisKey, ttl);
        } catch (RuntimeException e) {
            warnOnce(e);
        }
    }

    @Override
    public List<String> recall(String key, int limit, Duration ttl) {
        if (key == null || limit <= 0) {
            return List.of();
        }
        try {
            String redisKey = redisKey(key);
            List<String> names = redis.opsForList().range(redisKey, -limit, -1);
            if (names == null || names.isEmpty()) {
                return List.of();
            }
            touch(redisKey, ttl);
            return List.copyOf(names);
        } catch (RuntimeException e) {
            warnOnce(e);
            return List.of();
        }
    }

    @Override
    public int sessionCount() {
        try {
            Set<String> keys = redis.keys(KEY_PREFIX + "*");
            return keys == null ? 0 : keys.size();
        } catch (RuntimeException e) {
            warnOnce(e);
            return 0;
        }
    }

    private void touch(String redisKey, Duration ttl) {
        if (ttl != null && !ttl.isNegative() && !ttl.isZero()) {
            redis.expire(redisKey, ttl);
        }
    }

    private void warnOnce(RuntimeException e) {
        if (warned.compareAndSet(false, true)) {
            log.warn(
                    "Tool activation store: Redis unavailable, failing open to 'search again' "
                            + "(logged once): {}",
                    e.toString());
        }
    }
}
