package com.agenticai.core.mcp.oauth;

import com.agenticai.core.crypto.EncryptionService;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@code app.mcp.oauth.store=memory|redis} (default {@code memory}: per-JVM
 * state - today's single-replica behaviour, Redis never resolved). Redis mode
 * without a usable {@link StringRedisTemplate} degrades to memory with a
 * warning - the same posture as {@code McpOwnershipRegistryConfiguration} for
 * {@code app.mcp.registry-store}.
 */
@Configuration
public class McpOAuthSharedStoreConfiguration {

    private static final Logger log = LoggerFactory.getLogger(McpOAuthSharedStoreConfiguration.class);

    @Bean
    public McpOAuthSharedStore mcpOAuthSharedStore(
            @Value("${app.mcp.oauth.store:memory}") String store,
            EncryptionService encryption,
            ObjectProvider<StringRedisTemplate> redisProvider) {
        if (!"redis".equalsIgnoreCase(store == null ? "" : store.trim())) {
            return new InMemoryMcpOAuthSharedStore(); // never resolves the Redis template
        }
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            log.warn(
                "app.mcp.oauth.store=redis but no StringRedisTemplate is available -"
                    + " using in-memory MCP OAuth state");
            return new InMemoryMcpOAuthSharedStore();
        }
        log.info("MCP OAuth shared store=redis");
        return new RedisMcpOAuthSharedStore(redis, encryption, Clock.systemUTC());
    }
}
