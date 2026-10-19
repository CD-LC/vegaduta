package com.agenticai.core.agents.tools.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * The per-caller daily search allowance, and its enforcement inside the deep_research tool call -
 * the place the platform-paid searches are actually spent, whichever path reached the tool.
 */
class DeepResearchDailyAllowanceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String KEY = DeepResearchDailyAllowance.KEY_PREFIX + "t1:alice";

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private ObjectProvider<StringRedisTemplate> provider;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redis);
    }

    // ----------------------------------------------------------- the counter

    @Test
    void remainingCountsDownPerTenantAndUserAndIsNeverNegative() {
        DeepResearchDailyAllowance allowance = new DeepResearchDailyAllowance(provider, 60);
        assertEquals(60, allowance.maxPerDay());
        when(values.get(KEY)).thenReturn(null);
        assertEquals(60, allowance.remaining("t1", "alice"));
        when(values.get(KEY)).thenReturn("48");
        assertEquals(12, allowance.remaining("t1", "alice"));
        when(values.get(KEY)).thenReturn("75");
        assertEquals(0, allowance.remaining("t1", "alice"));
        // Another user of the same tenant has their own window.
        assertEquals(60, allowance.remaining("t1", "bob"));
        verify(values, never()).increment(anyString(), anyLong());
    }

    @Test
    void recordAddsTheSearchesAndStartsTheWindow() {
        when(values.increment(KEY, 5L)).thenReturn(5L);
        new DeepResearchDailyAllowance(provider, 60).record("t1", "alice", 5);
        verify(values).increment(KEY, 5L);
        verify(redis).expire(KEY, Duration.ofDays(1));
    }

    @Test
    void zeroMeansNoAllowanceAndNoRedisTraffic() {
        DeepResearchDailyAllowance off = new DeepResearchDailyAllowance(provider, 0);
        assertEquals(Integer.MAX_VALUE, off.remaining("t1", "alice"));
        off.record("t1", "alice", 5);
        verify(redis, never()).opsForValue();
    }

    @Test
    void failsOpenWhenRedisIsDown() {
        when(values.get(anyString())).thenThrow(new IllegalStateException("redis down"));
        when(values.increment(anyString(), anyLong()))
                .thenThrow(new IllegalStateException("redis down"));
        DeepResearchDailyAllowance allowance = new DeepResearchDailyAllowance(provider, 60);
        assertEquals(60, allowance.remaining("t1", "alice"));
        allowance.record("t1", "alice", 3);
    }

    @Test
    void aCallWithNoUserIsCountedPerAgentNotInOneSharedBucket() {
        UUID agentId = UUID.randomUUID();
        assertEquals("alice", DeepResearchDailyAllowance.callerKey("alice", agentId));
        assertEquals("agent:" + agentId, DeepResearchDailyAllowance.callerKey(null, agentId));
        assertEquals("agent:" + agentId, DeepResearchDailyAllowance.callerKey("  ", agentId));

        String agentKey = DeepResearchDailyAllowance.KEY_PREFIX + "t1:agent:" + agentId;
        when(values.get(agentKey)).thenReturn("59");
        DeepResearchToolCallback.SearchAllowance bound =
                new DeepResearchDailyAllowance(provider, 60).forCaller("t1", null, agentId);
        assertEquals(1, bound.remaining());
        when(values.increment(agentKey, 1L)).thenReturn(60L);
        bound.record(1);
        verify(values).increment(agentKey, 1L);
    }

    // ------------------------------------------------- enforcement in the tool

    /**
     * An in-memory allowance with the production semantics (check, then charge what was reserved).
     */
    private static final class FakeAllowance implements DeepResearchToolCallback.SearchAllowance {
        private int left;
        private final List<Integer> charged = new ArrayList<>();

        FakeAllowance(int left) {
            this.left = left;
        }

        @Override
        public int remaining() {
            return Math.max(0, left);
        }

        @Override
        public void record(int searches) {
            charged.add(searches);
            left -= searches;
        }
    }

    private static DeepResearchService service(AtomicInteger searches) {
        return new DeepResearchService(
                DeepResearchConfig.defaults(),
                (query, count) -> {
                    searches.incrementAndGet();
                    return List.of(
                            new BraveWebSearchClient.SearchResult(
                                    "A", "https://example.com/a", "d"));
                },
                (url, maxChars) ->
                        "Solid-state batteries reach 400 Wh/kg in lab cells. Another sentence follows here.");
    }

    private static final String COMPARISON_CALL =
            "{\"question\": \"battery progress\", \"effort\": \"comparison\", "
                    + "\"subQuestions\": [\"solid state\", \"lithium\", \"sodium\"]}";

    @Test
    void aSpentAllowanceRefusesTheCallWithoutRunningASingleSearch() throws Exception {
        AtomicInteger searches = new AtomicInteger();
        FakeAllowance allowance = new FakeAllowance(0);
        DeepResearchToolCallback tool =
                new DeepResearchToolCallback(
                        service(searches), new DeepResearchSession(12, 12), allowance);

        JsonNode out = MAPPER.readTree(tool.call(COMPARISON_CALL));
        assertTrue(out.get("error").asText().contains("allowance for this account is used up"));
        assertEquals(0, searches.get());
        assertTrue(allowance.charged.isEmpty());
    }

    @Test
    void theRemainingAllowanceCapsTheWorkersAndExactlyTheSearchesRunAreCharged() throws Exception {
        AtomicInteger searches = new AtomicInteger();
        FakeAllowance allowance = new FakeAllowance(2);
        DeepResearchSession session = new DeepResearchSession(12, 12);
        DeepResearchToolCallback tool =
                new DeepResearchToolCallback(service(searches), session, allowance);

        JsonNode first = MAPPER.readTree(tool.call(COMPARISON_CALL));
        assertEquals(
                2, first.get("workers").asInt(), "three angles wanted, two searches left today");
        assertEquals(2, searches.get());
        assertEquals(List.of(2), allowance.charged);

        // Same turn, allowance now spent: refused even though the turn's own ceiling has room.
        JsonNode second = MAPPER.readTree(tool.call(COMPARISON_CALL));
        assertTrue(second.has("error"));
        assertEquals(2, searches.get());
        assertEquals(List.of(2), allowance.charged);
    }

    @Test
    void withoutAnAllowanceTheToolBehavesAsBefore() throws Exception {
        AtomicInteger searches = new AtomicInteger();
        DeepResearchToolCallback tool =
                new DeepResearchToolCallback(service(searches), new DeepResearchSession(12, 12));
        JsonNode out = MAPPER.readTree(tool.call(COMPARISON_CALL));
        assertEquals(3, out.get("workers").asInt());
        assertFalse(out.has("error"));
        assertEquals(3, searches.get());
    }
}
