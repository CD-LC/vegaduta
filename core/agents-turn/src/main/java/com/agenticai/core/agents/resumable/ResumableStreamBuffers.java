package com.agenticai.core.agents.resumable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Picks the resumable-stream buffer for {@code app.chat.resumable-streams.store}: in-memory, or
 * in-memory mirrored to Redis when the store is {@code redis} and a template is available.
 */
final class ResumableStreamBuffers {

    private static final Logger log = LoggerFactory.getLogger(ResumableChatStreamService.class);

    private ResumableStreamBuffers() {}

    static ResumableStreamBuffer build(
            String store,
            int maxEvents,
            long ttlSeconds,
            long detachSeconds,
            long maxBytesPerTurn,
            long maxTotalBytes,
            ObjectProvider<StringRedisTemplate> redisProvider) {
        InMemoryResumableStreamBuffer memory =
                new InMemoryResumableStreamBuffer(
                        maxEvents,
                        ttlSeconds,
                        detachSeconds,
                        System::currentTimeMillis,
                        maxBytesPerTurn,
                        maxTotalBytes);
        if (!"redis".equalsIgnoreCase(store)) {
            return memory;
        }
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            log.warn(
                    "app.chat.resumable-streams.store=redis but no Redis template is available - using the in-memory buffer (single replica only).");
            return memory;
        }
        return new MirroredResumableStreamBuffer(
                memory,
                new RedisResumableStreamBuffer(redis, maxEvents, ttlSeconds, detachSeconds));
    }
}
