package com.agenticai.core.mcp.oauth;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.crypto.EncryptionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

/** The {@code app.mcp.oauth.store} switch, mirroring {@code McpOwnershipRegistryConfigurationTest}. */
class McpOAuthSharedStoreConfigurationTest {

    private final McpOAuthSharedStoreConfiguration config = new McpOAuthSharedStoreConfiguration();
    private final EncryptionService encryption = OAuthProviderTestSupport.encryption((byte) 5);

    @SuppressWarnings("unchecked")
    private static ObjectProvider<StringRedisTemplate> provider(StringRedisTemplate t) {
        ObjectProvider<StringRedisTemplate> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(t);
        when(p.getObject()).thenReturn(t);
        return p;
    }

    @Test
    void memoryModeNeverResolvesOrTouchesRedis() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ObjectProvider<StringRedisTemplate> p = provider(redis);
        for (String store : new String[] {"memory", "", null, "bogus"}) {
            McpOAuthSharedStore s = config.mcpOAuthSharedStore(store, encryption, p);
            assertInstanceOf(InMemoryMcpOAuthSharedStore.class, s);
        }
        verifyNoInteractions(redis);
        verifyNoInteractions(p);
    }

    @Test
    void redisModeWithoutATemplateDegradesToMemory() {
        McpOAuthSharedStore s = config.mcpOAuthSharedStore("redis", encryption, provider(null));
        assertInstanceOf(InMemoryMcpOAuthSharedStore.class, s);
    }

    @Test
    void redisModeWithATemplateBuildsTheRedisStore() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        McpOAuthSharedStore s = config.mcpOAuthSharedStore("redis", encryption, provider(redis));
        assertInstanceOf(RedisMcpOAuthSharedStore.class, s);
    }
}
