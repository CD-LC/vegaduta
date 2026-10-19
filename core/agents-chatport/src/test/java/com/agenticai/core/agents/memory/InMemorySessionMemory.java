package com.agenticai.core.agents.memory;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.tenancy.PlatformSettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A real {@link SessionMemoryService} over a map-backed stand-in for the Redis commands it uses on
 * the append, full-read, replace and owner paths. Lets callers of the service test against the real
 * key and owner-record derivation (SEC-004 scoped ids) instead of a mock that accepts whatever id
 * it is handed. No summariser, no TTL expiry.
 */
public final class InMemorySessionMemory {

    private final Map<String, List<String>> lists = new HashMap<>();
    private final Map<String, String> values = new HashMap<>();
    private final SessionMemoryService service;

    @SuppressWarnings("unchecked")
    public InMemorySessionMemory() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ListOperations<String, String> listOps = mock(ListOperations.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForList()).thenReturn(listOps);
        when(redis.opsForValue()).thenReturn(valueOps);

        when(listOps.rightPush(anyString(), anyString()))
                .thenAnswer(
                        inv -> {
                            List<String> list = list(inv.getArgument(0));
                            list.add(inv.getArgument(1));
                            return (long) list.size();
                        });
        when(listOps.rightPushAll(anyString(), anyCollection()))
                .thenAnswer(
                        inv -> {
                            List<String> list = list(inv.getArgument(0));
                            list.addAll((Collection<String>) inv.getArgument(1));
                            return (long) list.size();
                        });
        when(listOps.range(anyString(), anyLong(), anyLong()))
                .thenAnswer(
                        inv -> {
                            List<String> list = lists.get((String) inv.getArgument(0));
                            if (list == null) {
                                return List.of();
                            }
                            int[] r = bounds(list.size(), inv.getArgument(1), inv.getArgument(2));
                            return r[0] > r[1]
                                    ? List.of()
                                    : List.copyOf(list.subList(r[0], r[1] + 1));
                        });
        when(listOps.size(anyString()))
                .thenAnswer(
                        inv -> {
                            List<String> list = lists.get((String) inv.getArgument(0));
                            return list == null ? 0L : (long) list.size();
                        });
        doAnswer(
                        inv -> {
                            List<String> list = lists.get((String) inv.getArgument(0));
                            if (list != null) {
                                int[] r =
                                        bounds(list.size(), inv.getArgument(1), inv.getArgument(2));
                                List<String> kept =
                                        r[0] > r[1]
                                                ? new ArrayList<>()
                                                : new ArrayList<>(list.subList(r[0], r[1] + 1));
                                lists.put(inv.getArgument(0), kept);
                            }
                            return null;
                        })
                .when(listOps)
                .trim(anyString(), anyLong(), anyLong());

        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenAnswer(
                        inv -> values.putIfAbsent(inv.getArgument(0), inv.getArgument(1)) == null);
        when(valueOps.get(anyString())).thenAnswer(inv -> values.get((String) inv.getArgument(0)));
        when(redis.delete(anyString()))
                .thenAnswer(
                        inv ->
                                lists.remove((String) inv.getArgument(0)) != null
                                        | values.remove((String) inv.getArgument(0)) != null);
        when(redis.expire(anyString(), any(Duration.class))).thenReturn(true);

        PlatformSettingsService settings = mock(PlatformSettingsService.class);
        when(settings.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        ObjectProvider<SessionSummaryService> noSummariser = mock(ObjectProvider.class);
        service =
                new SessionMemoryService(
                        redis,
                        new ObjectMapper().findAndRegisterModules(),
                        settings,
                        noSummariser,
                        24,
                        40,
                        32000,
                        8000,
                        0);
    }

    public SessionMemoryService service() {
        return service;
    }

    /** Every history list key currently stored (e.g. to assert nothing landed under a raw id). */
    public Set<String> listKeys() {
        return Set.copyOf(lists.keySet());
    }

    private List<String> list(String key) {
        return lists.computeIfAbsent(key, k -> new ArrayList<>());
    }

    /** Redis LRANGE/LTRIM index semantics: negative counts from the end, clamped to the list. */
    private static int[] bounds(int size, long start, long end) {
        long s = start < 0 ? Math.max(0, size + start) : start;
        long e = end < 0 ? size + end : Math.min(end, size - 1);
        return new int[] {(int) s, (int) e};
    }
}
