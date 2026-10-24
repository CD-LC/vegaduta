package com.agenticai.core.agents.e2e;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * The slice of Redis {@code SessionMemoryService} uses on its multi-call append path (RPUSH,
 * LRANGE, LTRIM, LLEN, GET/SET/SETNX, DEL, EXPIRE), kept in two maps, so the REAL session memory
 * service runs with its REAL key scoping in a unit test. Expiry is not modelled (a test is far
 * shorter than the session TTL). Any operation outside that slice returns Mockito's default,
 * exactly as the other mock-Redis fixtures in this repo do.
 */
public final class InMemoryRedis {

    private final Map<String, List<String>> lists = new LinkedHashMap<>();
    private final Map<String, String> values = new LinkedHashMap<>();
    private final StringRedisTemplate template = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    public InMemoryRedis() {
        ListOperations<String, String> listOps = mock(ListOperations.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(template.opsForList()).thenReturn(listOps);
        when(template.opsForValue()).thenReturn(valueOps);

        when(listOps.rightPush(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> push(inv.getArgument(0), List.of(inv.<String>getArgument(1))));
        when(listOps.rightPushAll(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.<Collection<String>>any()))
                .thenAnswer(
                        inv -> push(inv.getArgument(0), inv.<Collection<String>>getArgument(1)));
        when(listOps.range(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(
                        inv -> range(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)));
        when(listOps.size(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> (long) listOf(inv.getArgument(0)).size());
        org.mockito.Mockito.doAnswer(
                        inv -> {
                            trim(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2));
                            return null;
                        })
                .when(listOps)
                .trim(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong());

        when(valueOps.get(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> values.get(inv.<String>getArgument(0)));
        when(valueOps.getAndExpire(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(Duration.class)))
                .thenAnswer(inv -> values.get(inv.<String>getArgument(0)));
        org.mockito.Mockito.doAnswer(
                        inv -> {
                            values.put(inv.getArgument(0), inv.getArgument(1));
                            return null;
                        })
                .when(valueOps)
                .set(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(Duration.class));
        when(valueOps.setIfAbsent(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(Duration.class)))
                .thenAnswer(
                        inv -> values.putIfAbsent(inv.getArgument(0), inv.getArgument(1)) == null);

        when(template.delete(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(
                        inv -> {
                            String key = inv.getArgument(0);
                            boolean existed =
                                    lists.remove(key) != null | values.remove(key) != null;
                            return existed;
                        });
        when(template.expire(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(Duration.class)))
                .thenReturn(true);
    }

    public StringRedisTemplate template() {
        return template;
    }

    /**
     * Every list key currently holding history, so a test can see WHICH key a turn was written to.
     */
    public synchronized List<String> listKeys() {
        return new ArrayList<>(lists.keySet());
    }

    /** The raw JSON entries stored under {@code key} (empty when the key does not exist). */
    public synchronized List<String> entries(String key) {
        return new ArrayList<>(listOf(key));
    }

    private synchronized List<String> listOf(String key) {
        return lists.computeIfAbsent(key, k -> new ArrayList<>());
    }

    private synchronized Long push(String key, Collection<String> items) {
        List<String> list = listOf(key);
        list.addAll(items);
        return (long) list.size();
    }

    private synchronized List<String> range(String key, long start, long end) {
        List<String> list = lists.get(key);
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        int size = list.size();
        int from = (int) (start < 0 ? Math.max(0, size + start) : Math.min(start, size));
        int to = (int) (end < 0 ? size + end : Math.min(end, size - 1));
        if (from > to) {
            return List.of();
        }
        return new ArrayList<>(list.subList(from, to + 1));
    }

    private synchronized void trim(String key, long start, long end) {
        List<String> kept = range(key, start, end);
        List<String> list = listOf(key);
        list.clear();
        list.addAll(kept);
    }
}
