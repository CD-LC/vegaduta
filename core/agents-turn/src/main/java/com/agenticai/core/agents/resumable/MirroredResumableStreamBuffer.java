package com.agenticai.core.agents.resumable;

import com.agenticai.core.resilience.RateLimitedLog;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code store=redis}: the in-memory buffer stays the source of truth for turns generated on
 * THIS node and every entry is mirrored to Redis so another replica can serve a resume. Same
 * fail-open posture as {@code A2aTaskStore}: a Redis error is logged (once a minute) and the
 * local copy keeps answering - a sick Redis must never take a running turn down. A turn this
 * node does not hold is read from Redis only.
 *
 * <p>Known limit, stated rather than hidden: a mirror write that failed leaves a hole in the
 * Redis copy; a reader on another replica detects it (non-contiguous ids) and is told the
 * replay is gapped instead of being handed an incomplete one.
 */
final class MirroredResumableStreamBuffer implements ResumableStreamBuffer {

    private static final Logger log = LoggerFactory.getLogger(MirroredResumableStreamBuffer.class);
    private static final RateLimitedLog REDIS_DOWN_LOG = new RateLimitedLog(log, Duration.ofMinutes(1));

    private final InMemoryResumableStreamBuffer memory;
    private final RedisResumableStreamBuffer redis;
    /** Turns this node is mirroring, by id: the Redis key needs the tenant, {@link #append} only gets the id. */
    private final Map<String, TurnMeta> mirroring = new ConcurrentHashMap<>();

    MirroredResumableStreamBuffer(InMemoryResumableStreamBuffer memory, RedisResumableStreamBuffer redis) {
        this.memory = memory;
        this.redis = redis;
    }

    @Override
    public void open(TurnMeta meta) {
        memory.open(meta);
        mirroring.put(meta.turnId(), meta);
        try {
            redis.open(meta);
        } catch (RuntimeException e) {
            REDIS_DOWN_LOG.warnf("Resumable chat stream: could not mirror turn {} to Redis - it can only be resumed on this replica.",
                meta.turnId(), e);
        }
    }

    @Override
    public long append(String turnId, String payload) {
        long id = memory.append(turnId, payload);
        TurnMeta meta = mirroring.get(turnId);
        if (id > 0 && meta != null) {
            mirror(meta, new Entry(id, payload == null ? "" : payload, false));
        }
        return id;
    }

    @Override
    public long finish(String turnId, String status) {
        long id = memory.finish(turnId, status);
        TurnMeta meta = mirroring.remove(turnId);
        if (id > 0 && meta != null) {
            mirror(meta, new Entry(id, status == null ? "complete" : status, true));
        }
        return id;
    }

    @Override
    public Optional<TurnMeta> meta(String tenantId, String turnId) {
        Optional<TurnMeta> local = memory.meta(tenantId, turnId);
        if (local.isPresent()) {
            return local;
        }
        try {
            return redis.meta(tenantId, turnId);
        } catch (RuntimeException e) {
            REDIS_DOWN_LOG.warnf("Resumable chat stream: Redis meta read failed for turn {} - treating as unknown.", turnId, e);
            return Optional.empty();
        }
    }

    @Override
    public ReadResult read(String tenantId, String turnId, long afterId) {
        ReadResult local = memory.read(tenantId, turnId, afterId);
        if (local.found()) {
            return local;
        }
        try {
            return redis.read(tenantId, turnId, afterId);
        } catch (RuntimeException e) {
            REDIS_DOWN_LOG.warnf("Resumable chat stream: Redis read failed for turn {} - treating as unknown.", turnId, e);
            return ReadResult.notFound();
        }
    }

    private void mirror(TurnMeta meta, Entry entry) {
        try {
            redis.push(meta, entry);
        } catch (RuntimeException e) {
            REDIS_DOWN_LOG.warnf("Resumable chat stream: could not mirror an entry of turn {} to Redis - another replica cannot fully replay it.",
                meta.turnId(), e);
        }
    }
}
