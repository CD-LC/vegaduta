package com.agenticai.core.chat;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The Redis half of the request inspector's store ({@code app.llm.request-snapshot-store.mode=redis}): one hash
 * per (tenant, session), field = snapshot id, value = the snapshot as JSON. The text in it is already
 * redacted and size-capped by {@link LlmRequestSnapshotter}, exactly as in memory. The hash expires {@code ttl}
 * after the last write, and a copy older than {@code ttl} is ignored on read, so each copy lives for the same
 * time it does in memory. At most {@code maxPerSession} copies stay per hash (the oldest go first).
 *
 * <p>The tenant is part of the key, so a session id from another tenant reads an empty hash.
 */
final class RedisLlmRequestSnapshotBackend implements LlmRequestSnapshotBackend {

    private static final Logger log = LoggerFactory.getLogger(RedisLlmRequestSnapshotBackend.class);

    /** Lenient on read: a replica on a newer version may have added fields while this one is still running. */
    private static final ObjectMapper MAPPER = JsonMappers.withJavaTime()
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final StringRedisTemplate redis;
    private final String keyPrefix;
    private final Duration ttl;
    private final int maxPerSession;

    RedisLlmRequestSnapshotBackend(StringRedisTemplate redis, String keyPrefix, Duration ttl, int maxPerSession) {
        this.redis = redis;
        this.keyPrefix = keyPrefix;
        this.ttl = ttl;
        this.maxPerSession = maxPerSession;
    }

    String key(String tenantId, String sessionId) {
        return keyPrefix + (tenantId == null ? "-" : tenantId) + "|" + sessionId;
    }

    @Override
    public void save(String tenantId, LlmRequestSnapshot snapshot) {
        String key = key(tenantId, snapshot.sessionId());
        HashOperations<String, String, String> hash = redis.opsForHash();
        hash.put(key, snapshot.id(), write(snapshot));
        redis.expire(key, ttl);
        Long size = hash.size(key);
        if (size != null && size > maxPerSession) {
            trim(key, hash);
        }
    }

    private void trim(String key, HashOperations<String, String, String> hash) {
        List<Held> held = new ArrayList<>();
        for (Map.Entry<String, String> e : hash.entries(key).entrySet()) {
            LlmRequestSnapshot s = read(e.getValue());
            // An unreadable value would never be shown; it is the first to go.
            held.add(new Held(e.getKey(), s == null ? Instant.EPOCH : s.at()));
        }
        held.sort(Comparator.comparing(Held::at));
        int excess = held.size() - maxPerSession;
        if (excess > 0) {
            Object[] drop = held.subList(0, excess).stream().map(Held::id).toArray();
            hash.delete(key, drop);
        }
    }

    private record Held(String id, Instant at) {}

    @Override
    public Optional<LlmRequestSnapshot> find(String tenantId, String sessionId, String snapshotId) {
        HashOperations<String, String, String> hash = redis.opsForHash();
        return Optional.ofNullable(read(hash.get(key(tenantId, sessionId), snapshotId)));
    }

    @Override
    public List<LlmRequestSnapshot> load(String tenantId, String sessionId) {
        HashOperations<String, String, String> hash = redis.opsForHash();
        Map<String, String> entries = hash.entries(key(tenantId, sessionId));
        List<LlmRequestSnapshot> out = new ArrayList<>(entries.size());
        for (String json : entries.values()) {
            LlmRequestSnapshot s = read(json);
            if (s != null) {
                out.add(s);
            }
        }
        return out;
    }

    private static String write(LlmRequestSnapshot snapshot) {
        try {
            return MAPPER.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("LLM request snapshot is not serializable", e);
        }
    }

    private static LlmRequestSnapshot read(String json) {
        if (json == null) {
            return null;
        }
        try {
            LlmRequestSnapshot s = MAPPER.readValue(json, LlmRequestSnapshot.class);
            return s == null || s.id() == null || s.at() == null ? null : s;
        } catch (JsonProcessingException e) {
            log.debug("Unreadable LLM request snapshot ignored: {}", e.getOriginalMessage());
            return null;
        }
    }
}
