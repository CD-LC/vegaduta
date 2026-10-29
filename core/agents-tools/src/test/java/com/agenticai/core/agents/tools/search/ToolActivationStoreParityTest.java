package com.agenticai.core.agents.tools.search;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The memory and Redis {@link ToolActivationStore} implementations must answer identically for
 * the same operation sequence - the store is swappable without a behaviour change.
 */
class ToolActivationStoreParityTest {

    private static final Duration TTL = Duration.ofHours(2);

    private final ToolActivationStore memory =
            new MemoryToolActivationStore(50, Clock.systemUTC());
    private final ToolActivationStore redis =
            new RedisToolActivationStore(
                    RedisToolActivationStoreTest.fakeRedis(new HashMap<>()));

    private void both(String key, List<String> names, int maxNames) {
        memory.remember(key, names, maxNames, TTL);
        redis.remember(key, names, maxNames, TTL);
    }

    private void same(String key, int limit) {
        assertEquals(
                memory.recall(key, limit, TTL),
                redis.recall(key, limit, TTL),
                "stores disagree on recall(" + key + ", " + limit + ")");
    }

    @Test
    void recallOrderDedupAndTrimMatch() {
        both("k1", List.of("a", "b", "c"), 5);
        same("k1", 10);
        same("k1", 2);

        both("k1", List.of("b", "d"), 5); // dedup + move-to-end
        same("k1", 10);

        both("k1", List.of("e", "f", "g"), 3); // trim to newest 3
        same("k1", 10);
    }

    @Test
    void sessionsAndLimitsMatch() {
        both("k1", List.of("a"), 5);
        both("k2", List.of("x", "y"), 1);

        same("k1", 10);
        same("k2", 10);
        same("missing", 10);
        same("k1", 0);
        same(null, 10);
        assertEquals(memory.sessionCount(), redis.sessionCount());
    }
}
