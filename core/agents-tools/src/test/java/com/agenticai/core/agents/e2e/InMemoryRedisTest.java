package com.agenticai.core.agents.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The fake Redis behaves like Redis for the operations session memory uses. */
class InMemoryRedisTest {

    @Test
    void listOperationsKeepOrderRangeFromTheTailAndTrimToTheNewest() {
        InMemoryRedis redis = new InMemoryRedis();
        var ops = redis.template().opsForList();

        ops.rightPush("k", "a");
        ops.rightPush("k", "b");
        ops.rightPushAll("k", List.of("c", "d"));

        assertEquals(List.of("a", "b", "c", "d"), ops.range("k", 0, -1));
        assertEquals(List.of("c", "d"), ops.range("k", -2, -1));
        assertEquals(4L, ops.size("k"));

        ops.trim("k", -3, -1);

        assertEquals(List.of("b", "c", "d"), redis.entries("k"));
        assertEquals(List.of("k"), redis.listKeys());
    }

    @Test
    void valueOperationsSetIfAbsentOnlyOnce() {
        InMemoryRedis redis = new InMemoryRedis();
        var ops = redis.template().opsForValue();

        assertTrue(ops.setIfAbsent("owner", "t|s", Duration.ofHours(1)));
        assertEquals(false, ops.setIfAbsent("owner", "other", Duration.ofHours(1)));
        assertEquals("t|s", ops.get("owner"));
    }
}
