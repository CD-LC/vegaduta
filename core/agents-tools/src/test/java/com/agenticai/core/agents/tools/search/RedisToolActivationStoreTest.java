package com.agenticai.core.agents.tools.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Two stores share ONE fake Redis (a map with list semantics and explicit expiry) and stand in
 * for two replicas.
 */
class RedisToolActivationStoreTest {

    private static final Duration TTL = Duration.ofMinutes(30);

    private final Map<String, List<String>> data = new HashMap<>();
    private StringRedisTemplate redis;
    private RedisToolActivationStore a;
    private RedisToolActivationStore b;

    @BeforeEach
    void setUp() {
        redis = fakeRedis(data);
        a = new RedisToolActivationStore(redis);
        b = new RedisToolActivationStore(redis);
    }

    /** A {@link StringRedisTemplate} mock whose list ops read/write {@code data}. */
    @SuppressWarnings("unchecked")
    static StringRedisTemplate fakeRedis(Map<String, List<String>> data) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ListOperations<String, String> ops = mock(ListOperations.class);
        when(redis.opsForList()).thenReturn(ops);
        when(ops.rightPush(anyString(), anyString()))
                .thenAnswer(
                        inv -> {
                            List<String> l =
                                    data.computeIfAbsent(
                                            inv.getArgument(0), k -> new ArrayList<>());
                            l.add(inv.getArgument(1));
                            return (long) l.size();
                        });
        when(ops.remove(anyString(), anyLong(), any()))
                .thenAnswer(
                        inv -> {
                            List<String> l = data.get(inv.getArgument(0));
                            if (l == null) {
                                return 0L;
                            }
                            int before = l.size();
                            l.removeIf(((String) inv.getArgument(2))::equals);
                            return (long) (before - l.size());
                        });
        when(ops.range(anyString(), anyLong(), anyLong()))
                .thenAnswer(
                        inv ->
                                range(
                                        data.getOrDefault(inv.getArgument(0), List.of()),
                                        inv.getArgument(1),
                                        inv.getArgument(2)));
        doAnswer(
                        inv -> {
                            String k = inv.getArgument(0);
                            List<String> kept =
                                    range(
                                            data.getOrDefault(k, List.of()),
                                            inv.getArgument(1),
                                            inv.getArgument(2));
                            if (kept.isEmpty()) {
                                data.remove(k);
                            } else {
                                data.put(k, kept);
                            }
                            return null;
                        })
                .when(ops)
                .trim(anyString(), anyLong(), anyLong());
        doAnswer(
                        inv -> {
                            String pattern = inv.getArgument(0);
                            int star = pattern == null ? -1 : pattern.indexOf('*');
                            String prefix = star < 0 ? String.valueOf(pattern) : pattern.substring(0, star);
                            return data.keySet().stream()
                                    .filter(k -> k.startsWith(prefix))
                                    .collect(java.util.stream.Collectors.toSet());
                        })
                .when(redis)
                .keys(anyString());
        return redis;
    }

    /** Redis list range semantics: negative indices count from the tail. */
    private static List<String> range(List<String> l, long from, long to) {
        int start = (int) (from < 0 ? l.size() + from : from);
        int end = (int) (to < 0 ? l.size() + to : to);
        start = Math.max(start, 0);
        end = Math.min(end, l.size() - 1);
        if (start > end) {
            return new ArrayList<>();
        }
        return new ArrayList<>(l.subList(start, end + 1));
    }

    private void expire(String key) {
        data.remove(RedisToolActivationStore.redisKey(key)); // simulates TTL lapse
    }

    @Test
    void activationsAreVisibleAcrossReplicas() {
        a.remember("t|a|s1", List.of("x", "y"), 10, TTL);

        assertEquals(List.of("x", "y"), b.recall("t|a|s1", 10, TTL));
    }

    @Test
    void rememberingAgainMovesANameToTheNewestEnd() {
        a.remember("k", List.of("a", "b", "c"), 10, TTL);
        b.remember("k", List.of("a"), 10, TTL);

        assertEquals(List.of("b", "c", "a"), b.recall("k", 10, TTL));
    }

    @Test
    void keepsOnlyTheNewestMaxNames() {
        a.remember("k", List.of("a", "b", "c", "d"), 2, TTL);

        assertEquals(List.of("c", "d"), b.recall("k", 10, TTL));
    }

    @Test
    void rememberSetsTheTtlAndRecallRenewsIt() {
        a.remember("k", List.of("x"), 10, TTL);
        verify(redis).expire(RedisToolActivationStore.redisKey("k"), TTL);

        b.recall("k", 10, TTL);
        verify(redis, org.mockito.Mockito.times(2))
                .expire(RedisToolActivationStore.redisKey("k"), TTL);
    }

    @Test
    void anExpiredSessionRecallsNothing() {
        a.remember("k", List.of("x"), 10, TTL);
        expire("k");

        assertTrue(b.recall("k", 10, TTL).isEmpty());
    }

    @Test
    void keysAreTenantScoped() {
        String tenantA = ToolActivationRegistry.key("tenant-a", "agent", "s1");
        String tenantB = ToolActivationRegistry.key("tenant-b", "agent", "s1");
        a.remember(tenantA, List.of("secret_tool"), 10, TTL);

        assertTrue(b.recall(tenantB, 10, TTL).isEmpty());
        assertTrue(
                data.containsKey("toolsearch:activations:tenant-a|agent|s1"),
                "the Redis key keeps the tenant|agent|session structure");
    }

    @Test
    void blankNamesAreSkipped() {
        a.remember("k", java.util.Arrays.asList("", null, "ok"), 10, TTL);

        assertEquals(List.of("ok"), b.recall("k", 10, TTL));
    }

    @Test
    void nonPositiveInputsNeverTouchRedis() {
        a.remember("k", List.of("x"), 0, TTL);
        a.remember(null, List.of("x"), 5, TTL);
        a.remember("k", List.of(), 5, TTL);
        a.recall(null, 5, TTL);
        a.recall("k", 0, TTL);

        verifyNoInteractions(redis);
    }

    @Test
    void theRegistryStillCapsAtThirtyTwoNames() {
        ToolActivationRegistry registry =
                new ToolActivationRegistry(new RedisToolActivationStore(redis), TTL);
        List<String> forty = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            forty.add("tool_" + i);
        }
        registry.remember("k", forty, 40);

        assertEquals(32, registry.recall("k", 40).size());
        assertTrue(registry.recall("k", 40).contains("tool_39"));
        assertTrue(!registry.recall("k", 40).contains("tool_7"));
    }

    @Test
    void sessionCountCountsLiveKeys() {
        a.remember("k1", List.of("x"), 10, TTL);
        a.remember("k2", List.of("y"), 10, TTL);

        assertEquals(2, b.sessionCount());
        expire("k1");
        assertEquals(1, b.sessionCount());
    }

    @Test
    void redisFailureFailsOpenToSearchingAgain() {
        ListOperations<String, String> ops = redis.opsForList();
        when(ops.rightPush(anyString(), anyString()))
                .thenThrow(new RedisConnectionFailureException("down"));
        when(ops.range(anyString(), anyLong(), anyLong()))
                .thenThrow(new RedisConnectionFailureException("down"));
        when(redis.keys(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        a.remember("k", List.of("x"), 10, TTL); // must not throw
        assertTrue(b.recall("k", 10, TTL).isEmpty());
        assertEquals(0, b.sessionCount());
    }
}
