package com.agenticai.core.quality.calibration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@link RedisJudgeCalibrationStore} against a map-backed fake of the Redis hash commands it uses: key layout,
 * JSON round trip, tenant partitioning, cascade delete, result trimming, and the no-Redis in-memory fallback.
 */
class RedisJudgeCalibrationStoreTest {

    private static final UUID AGENT = UUID.randomUUID();

    private final Map<String, Map<String, String>> redisData = new HashMap<>();
    private RedisJudgeCalibrationStore store;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        TenantContext.set(TenantContext.of("tenant-1", "tester", Set.of()));
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HashOperations<String, String, String> hash = mock(HashOperations.class);
        when(redis.<String, String>opsForHash()).thenReturn(hash);
        org.mockito.Mockito.doAnswer(inv -> {
            redisData.computeIfAbsent(inv.<String>getArgument(0), k -> new LinkedHashMap<>())
                .put(inv.<String>getArgument(1), inv.<String>getArgument(2));
            return null;
        }).when(hash).put(anyString(), anyString(), anyString());
        when(hash.get(anyString(), any())).thenAnswer(inv ->
            redisData.getOrDefault(inv.<String>getArgument(0), Map.of()).get(String.valueOf(inv.<Object>getArgument(1))));
        when(hash.entries(anyString())).thenAnswer(inv ->
            new LinkedHashMap<>(redisData.getOrDefault(inv.<String>getArgument(0), Map.of())));
        when(hash.size(anyString())).thenAnswer(inv ->
            (long) redisData.getOrDefault(inv.<String>getArgument(0), Map.of()).size());
        when(hash.delete(anyString(), any(Object[].class))).thenAnswer(inv -> {
            Map<String, String> m = redisData.get(inv.<String>getArgument(0));
            long removed = 0;
            Object[] fields = inv.getRawArguments().length > 1 && inv.getRawArguments()[1] instanceof Object[] arr
                ? arr : new Object[0];
            for (Object f : fields) {
                if (m != null && m.remove(String.valueOf(f)) != null) {
                    removed++;
                }
            }
            return removed;
        });
        when(redis.delete(anyString())).thenAnswer(inv -> redisData.remove(inv.<String>getArgument(0)) != null);
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redis);
        store = new RedisJudgeCalibrationStore(provider);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static JudgeCalibrationResult result(UUID setId, String judge, Double kappa) {
        return new JudgeCalibrationResult(setId, AGENT, judge, "OPENAI/agent", 20, 19, 17, kappa, 0.89, 0.9,
            "substantial agreement", null, "tester");
    }

    @Test
    void setsRoundTripThroughJsonAndAreKeyedByTenant() {
        JudgeCalibrationSet set = store.saveSet(new JudgeCalibrationSet(AGENT, "tone v1", "tester"));

        JudgeCalibrationSet loaded = store.findSet(set.getId()).orElseThrow();
        assertEquals("tone v1", loaded.getName());
        assertEquals(AGENT, loaded.getAgentId());
        assertEquals(set.getCreatedAt(), loaded.getCreatedAt());
        assertEquals(1, store.listSets(AGENT).size());
        assertTrue(store.listSets(UUID.randomUUID()).isEmpty());
        assertTrue(redisData.containsKey("quality:judgecal:tenant-1:sets"));

        TenantContext.set(TenantContext.of("tenant-2", "other", Set.of()));
        assertTrue(store.findSet(set.getId()).isEmpty(), "another tenant computes a different key");
        assertTrue(store.listSets(AGENT).isEmpty());
    }

    @Test
    void itemsRoundTripAndKeepPositionOrder() {
        JudgeCalibrationSet set = store.saveSet(new JudgeCalibrationSet(AGENT, "s", "t"));
        store.saveItems(List.of(
            new JudgeCalibrationItem(set.getId(), 1, "q2", "a", "b", "B", "why"),
            new JudgeCalibrationItem(set.getId(), 0, "q1", "a", "b", "A", null)));

        List<JudgeCalibrationItem> items = store.listItems(set.getId());
        assertEquals(List.of("q1", "q2"), items.stream().map(JudgeCalibrationItem::getInput).toList());
        assertEquals("why", items.get(1).getRationale());
        assertEquals(2, store.countItems(set.getId()));
        assertEquals("B", store.findItem(set.getId(), items.get(1).getId()).orElseThrow().getHumanLabel());

        store.deleteItem(set.getId(), items.get(0).getId());
        assertEquals(1, store.countItems(set.getId()));
        assertTrue(store.findItem(set.getId(), items.get(0).getId()).isEmpty());
    }

    @Test
    void deletingASetRemovesItsItemsAndResultsButNotOthers() {
        JudgeCalibrationSet a = store.saveSet(new JudgeCalibrationSet(AGENT, "a", "t"));
        JudgeCalibrationSet b = store.saveSet(new JudgeCalibrationSet(AGENT, "b", "t"));
        store.saveItems(List.of(new JudgeCalibrationItem(a.getId(), 0, "q", "x", "y", "A", null)));
        store.saveItems(List.of(new JudgeCalibrationItem(b.getId(), 0, "q", "x", "y", "A", null)));
        store.saveResult(result(a.getId(), "J", 0.5));
        store.saveResult(result(b.getId(), "J", 0.6));

        store.deleteSet(a.getId());

        assertTrue(store.findSet(a.getId()).isEmpty());
        assertEquals(0, store.countItems(a.getId()));
        assertTrue(store.resultsForSet(a.getId(), 10).isEmpty());
        assertTrue(store.findSet(b.getId()).isPresent());
        assertEquals(1, store.countItems(b.getId()));
        assertEquals(1, store.resultsForSet(b.getId(), 10).size());
    }

    @Test
    void resultsKeepNullKappaNullAndAnswerNewestFirstAndLatestPerJudge() throws Exception {
        UUID setId = UUID.randomUUID();
        store.saveResult(result(setId, "OPENAI/judge", 0.3));
        Thread.sleep(5);
        JudgeCalibrationResult newest = store.saveResult(result(setId, "OPENAI/judge", null));
        store.saveResult(result(setId, "OPENAI/other", 0.9));

        List<JudgeCalibrationResult> forSet = store.resultsForSet(setId, 10);
        assertEquals(3, forSet.size());
        assertEquals(1, store.resultsForSet(setId, 1).size());
        JudgeCalibrationResult latest = store.latestForJudgeModel("OPENAI/judge").orElseThrow();
        assertEquals(newest.getId(), latest.getId());
        assertEquals(null, latest.getKappa(), "an undefined kappa survives the JSON round trip as null");
        assertEquals(0.89, latest.getOrderSwapConsistency());
        assertTrue(store.latestForJudgeModel("OPENAI/none").isEmpty());
        assertTrue(store.latestForJudgeModel(null).isEmpty());
        assertEquals(3, store.resultsForAgent(AGENT, 10).size());
        assertTrue(store.resultsForAgent(UUID.randomUUID(), 10).isEmpty());
    }

    @Test
    void resultsAreTrimmedToTheNewestMaximum() {
        UUID setId = UUID.randomUUID();
        List<JudgeCalibrationResult> all = new ArrayList<>();
        for (int i = 0; i < InMemoryJudgeCalibrationStore.MAX_RESULTS + 5; i++) {
            all.add(store.saveResult(result(setId, "J", 0.5)));
        }
        assertEquals(InMemoryJudgeCalibrationStore.MAX_RESULTS,
            redisData.get("quality:judgecal:tenant-1:results").size());
    }

    @Test
    void unreadableRecordsAreSkippedNotFatal() {
        JudgeCalibrationSet set = store.saveSet(new JudgeCalibrationSet(AGENT, "ok", "t"));
        redisData.get("quality:judgecal:tenant-1:sets").put(UUID.randomUUID().toString(), "{not json");

        assertEquals(1, store.listSets(AGENT).size());
        assertEquals(set.getId(), store.listSets(AGENT).get(0).getId());
    }

    @SuppressWarnings("unchecked")
    @Test
    void withoutRedisTheStoreFallsBackToMemoryPerTenant() {
        ObjectProvider<StringRedisTemplate> none = mock(ObjectProvider.class);
        when(none.getIfAvailable()).thenReturn(null);
        RedisJudgeCalibrationStore memory = new RedisJudgeCalibrationStore(none);

        JudgeCalibrationSet set = memory.saveSet(new JudgeCalibrationSet(AGENT, "m", "t"));
        memory.saveResult(result(set.getId(), "J", 0.7));
        assertTrue(memory.findSet(set.getId()).isPresent());
        assertEquals(1, memory.resultsForSet(set.getId(), 5).size());
        assertEquals(0.7, memory.latestForJudgeModel("J").orElseThrow().getKappa());

        TenantContext.set(TenantContext.of("tenant-2", "other", Set.of()));
        assertFalse(memory.findSet(set.getId()).isPresent());
    }
}
