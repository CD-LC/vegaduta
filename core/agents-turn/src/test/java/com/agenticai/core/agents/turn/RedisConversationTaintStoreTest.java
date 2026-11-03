package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RedisConversationTaintStoreTest {

    @SuppressWarnings("unchecked")
    @Test
    void writesOneKeyPerTenantAndConversationWithTheSessionTtl() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        RedisConversationTaintStore store = new RedisConversationTaintStore(redis, 24);

        store.markTainted("t1", "c1");

        verify(ops).set("agentic:session-taint:t1:c1", "1", Duration.ofHours(24));
        assertNotEquals(
                RedisConversationTaintStore.key("t1", "c1"),
                RedisConversationTaintStore.key("t2", "c1"));
    }

    @Test
    void readingAnExistingFlagRefreshesItsTtl() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.hasKey("agentic:session-taint:t1:c1")).thenReturn(true);
        RedisConversationTaintStore store = new RedisConversationTaintStore(redis, 24);

        assertTrue(store.isTainted("t1", "c1"));
        verify(redis).expire(eq("agentic:session-taint:t1:c1"), eq(Duration.ofHours(24)));
        assertFalse(store.isTainted("t2", "c1"));
        verify(redis, org.mockito.Mockito.never()).expire(eq("agentic:session-taint:t2:c1"), any(Duration.class));
    }
}
