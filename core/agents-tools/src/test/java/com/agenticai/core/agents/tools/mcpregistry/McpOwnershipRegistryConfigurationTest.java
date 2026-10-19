package com.agenticai.core.agents.tools.mcpregistry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

class McpOwnershipRegistryConfigurationTest {

    private final McpOwnershipRegistryConfiguration config =
            new McpOwnershipRegistryConfiguration();

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
            McpOwnershipRegistry registry =
                    config.mcpOwnershipRegistry(store, Duration.ofSeconds(60), p);
            McpOwnershipGate gate =
                    config.mcpOwnershipGate(registry, false, Duration.ofSeconds(5), p);
            assertFalse(registry.active());
            assertSame(McpOwnershipGate.NOOP, gate);
            UUID id = UUID.randomUUID();
            // exercise every gate/registry method the manager can reach
            registry.claim("t", id);
            registry.ownerOf("t", id);
            registry.release("t", id);
            registry.renewOwned((t, i) -> true, Duration.ZERO);
            registry.registerReplica();
            registry.liveReplicas();
            gate.routeConnect("t", id);
            gate.broadcastDisconnect("t", id);
            gate.routeTest("t", id);
            gate.mayRetry("t", id);
            gate.mayPersistHealth("t", id);
        }
        verifyNoInteractions(redis);
        org.mockito.Mockito.verifyNoInteractions(p);
    }

    @Test
    void memoryDefaultsAreTodaysBehaviour() {
        McpOwnershipGate g = McpOwnershipGate.NOOP;
        UUID id = UUID.randomUUID();
        assertFalse(g.routeConnect("t", id));
        assertTrue(g.routeTest("t", id).isEmpty());
        assertTrue(g.mayRetry("t", id));
        assertTrue(g.mayPersistHealth("t", id));
    }

    @Test
    void redisModeWithoutATemplateDegradesToMemory() {
        McpOwnershipRegistry registry =
                config.mcpOwnershipRegistry("redis", Duration.ofSeconds(60), provider(null));
        assertFalse(registry.active());
    }

    @Test
    void redisModeWithATemplateBuildsTheRedisRegistryAndGate() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ObjectProvider<StringRedisTemplate> p = provider(redis);
        McpOwnershipRegistry registry =
                config.mcpOwnershipRegistry("redis", Duration.ofSeconds(60), p);
        assertTrue(registry.active());
        assertTrue(
                config.mcpOwnershipGate(registry, false, Duration.ofSeconds(5), p)
                        instanceof RedisMcpOwnershipGate);
    }
}
