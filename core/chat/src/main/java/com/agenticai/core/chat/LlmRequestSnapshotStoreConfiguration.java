package com.agenticai.core.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Registers {@link LlmRequestSnapshotStoreProperties} and applies them to the static
 * {@link LlmRequestSnapshotStore} (and the block labels in {@link PromptBlockProvenance}) at startup. With
 * {@code mode=redis} and a Redis connection the store shares its copies through Redis; with {@code mode=redis}
 * and no Redis bean it logs that it is keeping them in this JVM only.
 */
@Configuration
@EnableConfigurationProperties(LlmRequestSnapshotStoreProperties.class)
class LlmRequestSnapshotStoreConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LlmRequestSnapshotStoreConfiguration.class);

    @Autowired
    void applyTo(LlmRequestSnapshotStoreProperties properties, ObjectProvider<StringRedisTemplate> redis) {
        LlmRequestSnapshotBackend backend = null;
        if (properties.isRedis()) {
            StringRedisTemplate template = redis.getIfAvailable();
            if (template == null) {
                log.warn("app.llm.request-snapshot-store.mode=redis but there is no Redis connection; "
                    + "the request inspector keeps its copies in this JVM only");
            } else {
                backend = new RedisLlmRequestSnapshotBackend(
                    template, properties.getKeyPrefix(), properties.getTtl(), properties.getMaxPerSession());
            }
        }
        LlmRequestSnapshotStore.INSTANCE.configure(properties, backend);
        PromptBlockProvenance.configure(properties.getTtl(), properties.getMaxSessions());
    }
}
