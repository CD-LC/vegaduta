package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.tools.search.ToolActivationRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@code app.tools.search.activation-store}: which store the wired registry ends up on for each
 * mode / template combination.
 */
class ToolSearchActivationWiringTest {

    private static final Duration TTL = Duration.ofHours(1);

    @SuppressWarnings("unchecked")
    private static ObjectProvider<StringRedisTemplate> provider(StringRedisTemplate t) {
        ObjectProvider<StringRedisTemplate> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(t);
        return p;
    }

    private static ToolActivationRegistry build(String mode, ObjectProvider<StringRedisTemplate> p) {
        return ToolResolverConfiguration.activationRegistry(mode, TTL, p);
    }

    @Test
    void memoryAndUnknownModesNeverResolveRedis() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ObjectProvider<StringRedisTemplate> p = provider(redis);
        for (String mode : new String[] {"memory", "bogus", "", null}) {
            ToolActivationRegistry registry = build(mode, p);
            registry.remember("k", List.of("a"), 5);
            assertEquals(List.of("a"), registry.recall("k", 5));
        }
        verifyNoInteractions(redis);
        verifyNoInteractions(p);
    }

    @Test
    void autoWithoutATemplateFallsBackToMemory() {
        ToolActivationRegistry registry = build("auto", provider(null));
        registry.remember("k", List.of("a"), 5);
        assertEquals(List.of("a"), registry.recall("k", 5));
    }

    @Test
    void redisWithoutATemplateDegradesToMemory() {
        ToolActivationRegistry registry = build("redis", provider(null));
        registry.remember("k", List.of("a"), 5);
        assertEquals(List.of("a"), registry.recall("k", 5));
    }

    @Test
    @SuppressWarnings("unchecked")
    void autoAndRedisModesUseTheTemplate() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ListOperations<String, String> ops = mock(ListOperations.class);
        when(redis.opsForList()).thenReturn(ops);
        ObjectProvider<StringRedisTemplate> p = provider(redis);

        for (String mode : new String[] {"auto", "redis"}) {
            ToolActivationRegistry registry = build(mode, p);
            registry.remember("k", List.of("a"), 5);
        }
        verify(redis, org.mockito.Mockito.times(6)).opsForList();
        verify(ops, org.mockito.Mockito.times(2)).rightPush("toolsearch:activations:k", "a");
    }
}
