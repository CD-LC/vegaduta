package com.agenticai.core.agents.turn.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * SEC-052: the exact-match response cache is keyed per principal, so an answer
 * personalised for user A (from A's memories or A's own OAuth tools) is never served
 * to user B asking the identical question - even on the same tenant and agent.
 */
class LlmResponseCacheServiceTest {

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
        cache = new LlmResponseCacheService(redis, 6);
    }

    @Test
    void userAsCachedAnswerIsNotServedToUserB() {
        cache.put(TENANT, AGENT, "user-a", "What is my account balance?", "Your balance is 1,234");

        assertThat(cache.get(TENANT, AGENT, "user-a", "what is my  account balance?"))
            .contains("Your balance is 1,234");
        assertThat(cache.get(TENANT, AGENT, "user-b", "What is my account balance?")).isEqualTo(Optional.empty());
    }

    @Test
    void shareVisitorsAreIsolatedFromEachOtherToo() {
        cache.put(TENANT, AGENT, "agent-share:s1:visitorA", "who am I?", "You are Alice");
        assertThat(cache.get(TENANT, AGENT, "agent-share:s1:visitorB", "who am I?")).isEmpty();
    }

    @Test
    void keysNeverContainTheRawPrincipal() {
        cache.put(TENANT, AGENT, "alice@example.com", "q", "a");
        assertThat(store.keySet()).singleElement().satisfies(key -> {
            assertThat(key).startsWith("llmcache:" + TENANT + ":" + AGENT + ":u");
            assertThat(key).doesNotContain("alice");
        });
    }

    @Test
    void tenantScopingIsUnchanged() {
        cache.put(TENANT, AGENT, "same-sub", "q", "tenant A answer");
        assertThat(cache.get("tenant_b", AGENT, "same-sub", "q")).isEmpty();
    }
}
