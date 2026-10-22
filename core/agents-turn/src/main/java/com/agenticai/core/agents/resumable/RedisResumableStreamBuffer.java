package com.agenticai.core.agents.resumable;

import com.agenticai.core.agents.resumable.ResumableStreamBuffer.Entry;
import com.agenticai.core.agents.resumable.ResumableStreamBuffer.ReadResult;
import com.agenticai.core.agents.resumable.ResumableStreamBuffer.TurnMeta;
import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis half of the resumable-stream buffer ({@code app.chat.resumable-streams.store=redis}): lets
 * a replica that did NOT generate a turn serve a resume for it. Not a {@link ResumableStreamBuffer}
 * itself - the node generating a turn keeps the authoritative in-memory copy and mirrors every
 * entry here with the id it already assigned ({@link #push}), so the two never disagree about
 * numbering. {@link MirroredResumableStreamBuffer} owns the fail-open policy (a Redis error is
 * logged, never thrown into the live stream).
 *
 * <p>Layout, tenant-first like every other Redis key here: one LIST per turn ({@code
 * agentic:chat-resume:{tenant}:{turn}:ev}) whose elements are {@code id|D|payload} (or {@code
 * id|T|status} for the terminal entry), plus one meta JSON value ({@code ...:meta}). The tenant is
 * part of the key, so a lookup with another tenant's id computes a different key and cannot see the
 * turn.
 *
 * <p>The list is trimmed from the left to {@code maxEvents} (with some slack so trimming is not a
 * Redis call per token). Reads locate the cursor from the id of the oldest retained element and
 * then VERIFY the ids they got back are contiguous from {@code after + 1}; a concurrent trim, a
 * mirror write that failed, or a malformed element all surface as {@link ReadResult#gapped()}
 * instead of a silently incomplete replay.
 */
final class RedisResumableStreamBuffer {

    static final String KEY_PREFIX = "agentic:chat-resume:";
    private static final ObjectMapper JSON = JsonMappers.plain();

    /** Refresh the running-turn TTL every this many entries instead of on each one. */
    private static final int TTL_REFRESH_EVERY = 64;

    private final StringRedisTemplate redis;
    private final int maxEvents;
    private final int trimAt;
    private final Duration ttl;
    private final Duration runningTtl;

    RedisResumableStreamBuffer(
            StringRedisTemplate redis, int maxEvents, long ttlSeconds, long detachTimeoutSeconds) {
        this.redis = redis;
        this.maxEvents = Math.max(1, maxEvents);
        this.trimAt = this.maxEvents + Math.max(16, this.maxEvents / 10);
        this.ttl = Duration.ofSeconds(Math.max(1L, ttlSeconds));
        this.runningTtl =
                Duration.ofSeconds(Math.max(1L, detachTimeoutSeconds) + Math.max(1L, ttlSeconds));
    }

    static String listKey(String tenantId, String turnId) {
        return KEY_PREFIX + tenantId + ":" + turnId + ":ev";
    }

    static String metaKey(String tenantId, String turnId) {
        return KEY_PREFIX + tenantId + ":" + turnId + ":meta";
    }

    static String encode(Entry entry) {
        return entry.id() + "|" + (entry.terminal() ? "T" : "D") + "|" + entry.payload();
    }

    /** Null when the element is not in this class's own format. */
    static Entry decode(String raw) {
        if (raw == null) {
            return null;
        }
        int first = raw.indexOf('|');
        if (first <= 0) {
            return null;
        }
        int second = raw.indexOf('|', first + 1);
        if (second != first + 2) {
            return null;
        }
        long id;
        try {
            id = Long.parseLong(raw.substring(0, first));
        } catch (NumberFormatException e) {
            return null;
        }
        char kind = raw.charAt(first + 1);
        if (kind != 'D' && kind != 'T') {
            return null;
        }
        return new Entry(id, raw.substring(second + 1), kind == 'T');
    }

    void open(TurnMeta meta) {
        redis.opsForValue()
                .set(metaKey(meta.tenantId(), meta.turnId()), metaJson(meta), runningTtl);
    }

    /** Mirrors one entry (id already assigned by the in-memory buffer). */
    void push(TurnMeta meta, Entry entry) {
        String list = listKey(meta.tenantId(), meta.turnId());
        Long size = redis.opsForList().rightPush(list, encode(entry));
        if (size != null && size > trimAt) {
            redis.opsForList().trim(list, -maxEvents, -1);
        }
        if (entry.terminal()) {
            redis.expire(list, ttl);
            redis.expire(metaKey(meta.tenantId(), meta.turnId()), ttl);
        } else if (entry.id() % TTL_REFRESH_EVERY == 1) {
            redis.expire(list, runningTtl);
            redis.expire(metaKey(meta.tenantId(), meta.turnId()), runningTtl);
        }
    }

    Optional<TurnMeta> meta(String tenantId, String turnId) {
        String raw = redis.opsForValue().get(metaKey(tenantId, turnId));
        if (raw == null) {
            return Optional.empty();
        }
        try {
            JsonNode n = JSON.readTree(raw);
            return Optional.of(
                    new TurnMeta(
                            n.path("turnId").asText(),
                            n.path("tenantId").asText(),
                            n.hasNonNull("ownerSubject") ? n.get("ownerSubject").asText() : null,
                            n.path("agentId").asText(),
                            n.path("sessionId").asText(),
                            n.path("startedAtMs").asLong()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    ReadResult read(String tenantId, String turnId, long afterId) {
        String list = listKey(tenantId, turnId);
        long after = Math.max(0L, afterId);
        List<String> head = redis.opsForList().range(list, 0, 0);
        if (head == null || head.isEmpty()) {
            return Boolean.TRUE.equals(redis.hasKey(metaKey(tenantId, turnId)))
                    ? ReadResult.of(List.of())
                    : ReadResult.notFound();
        }
        Entry oldest = decode(head.get(0));
        if (oldest == null) {
            return ReadResult.gapped();
        }
        if (after + 1L < oldest.id()) {
            return ReadResult.gapped();
        }
        List<String> raw = redis.opsForList().range(list, after + 1L - oldest.id(), -1);
        if (raw == null || raw.isEmpty()) {
            return ReadResult.of(List.of());
        }
        List<Entry> out = new ArrayList<>(raw.size());
        long expected = after + 1L;
        for (String element : raw) {
            Entry entry = decode(element);
            if (entry == null || entry.id() != expected) {
                // A trim moved the list under us, a mirror write was lost, or the element is
                // malformed.
                return ReadResult.gapped();
            }
            out.add(entry);
            expected++;
        }
        return ReadResult.of(out);
    }

    private static String metaJson(TurnMeta meta) {
        ObjectNode n = JSON.createObjectNode();
        n.put("turnId", meta.turnId());
        n.put("tenantId", meta.tenantId());
        if (meta.ownerSubject() != null) {
            n.put("ownerSubject", meta.ownerSubject());
        }
        n.put("agentId", meta.agentId());
        n.put("sessionId", meta.sessionId());
        n.put("startedAtMs", meta.startedAtMs());
        return n.toString();
    }
}
