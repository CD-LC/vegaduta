package com.agenticai.core.agents.turn.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * Both response caches key on the conversation before the message as well as on the message: "yes" after one
 * question is a different entry from "yes" after another, and a first message keys exactly as it always did.
 */
class CacheConversationKeyTest {

    private static final String TENANT = "tenant_a";
    private static final UUID AGENT = UUID.randomUUID();

    private final Map<String, String> store = new HashMap<>();
    private LlmResponseCacheService cache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenAnswer(inv -> store.get(inv.<String>getArgument(0)));
        doAnswer(inv -> store.put(inv.getArgument(0), inv.getArgument(1)))
                .when(ops).set(anyString(), anyString(), any(java.time.Duration.class));
        when(redis.delete(anyString())).thenAnswer(inv -> store.remove(inv.<String>getArgument(0)) != null);
        cache = new LlmResponseCacheService(redis, 6);
    }

    @Test
    void sameMessageAfterADifferentConversationIsADifferentEntry() {
        cache.put(TENANT, AGENT, "u", "yes", "ctx-orders", "Order cancelled.");

        assertThat(cache.get(TENANT, AGENT, "u", "yes", "ctx-orders")).contains("Order cancelled.");
        assertThat(cache.get(TENANT, AGENT, "u", "yes", "ctx-invoices")).isEmpty();
        assertThat(cache.get(TENANT, AGENT, "u", "yes")).as("and not the message-only entry either").isEmpty();
    }

    @Test
    void aFirstMessageKeysExactlyAsTheMessageOnlyCallersAlwaysDid() {
        cache.put(TENANT, AGENT, "u", "When do you open?", "At nine.");

        assertThat(cache.get(TENANT, AGENT, "u", "When do you open?", "")).contains("At nine.");
        assertThat(cache.get(TENANT, AGENT, "u", "When do you open?", null)).contains("At nine.");
        // llmcache:<tenant>:<agent>:u<principal>:<message> - five segments; a conversation adds a sixth. Counting
        // segments (not searching for ":c") keeps this independent of the random agent UUID and the hashes.
        assertThat(store.keySet()).allMatch(k -> k.split(":").length == 5, "no conversation segment on the legacy key: " + store.keySet());
    }

    @Test
    void evictDropsTheEntryOfThatConversationOnly() {
        cache.put(TENANT, AGENT, "u", "yes", "ctx-a", "A");
        cache.put(TENANT, AGENT, "u", "yes", "ctx-b", "B");

        cache.evict(TENANT, AGENT, "u", "yes", "ctx-a");

        assertThat(cache.get(TENANT, AGENT, "u", "yes", "ctx-a")).isEmpty();
        assertThat(cache.get(TENANT, AGENT, "u", "yes", "ctx-b")).contains("B");
    }

    @Test
    void keysNeverContainTheRawConversation() {
        cache.put(TENANT, AGENT, "u", "yes", "Shall I cancel the order?", "Done");

        assertThat(store.keySet()).noneMatch(k -> k.contains("cancel"));
    }

    @Test
    void theSemanticCacheBindsRowsToThePrincipalAndTheConversationTogether() {
        String first = SemanticResponseCacheService.scopeHash("u", "");
        String inContext = SemanticResponseCacheService.scopeHash("u", "ctx-a");

        assertEquals(SemanticResponseCacheService.subjectHash("u"), first, "rows stored before keep matching a first message");
        assertNotEquals(first, inContext);
        assertNotEquals(inContext, SemanticResponseCacheService.scopeHash("u", "ctx-b"));
        assertNotEquals(inContext, SemanticResponseCacheService.scopeHash("v", "ctx-a"), "and still per principal");
    }
}
