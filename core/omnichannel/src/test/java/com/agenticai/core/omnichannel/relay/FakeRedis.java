package com.agenticai.core.omnichannel.relay;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * An in-memory stand-in for the handful of {@link StringRedisTemplate} calls {@link
 * EdgeRelayService} makes, built on Mockito answers the same manual way every other suite here
 * mocks Redis. Blocking pops return at once (empty when nothing is queued); the multi-key BLPOP
 * behind {@code execute(RedisCallback)} pops from whichever of {@link #queueKeys} has an entry.
 */
final class FakeRedis {

    final Map<String, String> values = new HashMap<>();
    final Map<String, Map<String, String>> hashes = new HashMap<>();
    final Map<String, Deque<String>> lists = new HashMap<>();
    final List<String> queueKeys = new ArrayList<>();
    final StringRedisTemplate template = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    FakeRedis() {
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);
        ListOperations<String, String> listOps = mock(ListOperations.class);
        when(template.opsForValue()).thenReturn(valueOps);
        when(template.opsForHash()).thenReturn(hashOps);
        when(template.opsForList()).thenReturn(listOps);

        org.mockito.Mockito.doAnswer(inv -> { values.put(inv.getArgument(0), inv.getArgument(1)); return null; })
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));
        when(valueOps.get(anyString())).thenAnswer(inv -> values.get((String) inv.getArgument(0)));

        org.mockito.Mockito.doAnswer(inv -> {
            hash(inv.getArgument(0)).put(String.valueOf((Object) inv.getArgument(1)), String.valueOf((Object) inv.getArgument(2)));
            return null;
        }).when(hashOps).put(anyString(), any(), any());
        when(hashOps.hasKey(anyString(), any())).thenAnswer(inv -> hash(inv.getArgument(0)).containsKey(String.valueOf((Object) inv.getArgument(1))));
        when(hashOps.entries(anyString())).thenAnswer(inv -> new LinkedHashMap<Object, Object>(hash(inv.getArgument(0))));
        when(hashOps.get(anyString(), any())).thenAnswer(inv -> hash(inv.getArgument(0)).get(String.valueOf((Object) inv.getArgument(1))));
        when(hashOps.delete(anyString(), any())).thenAnswer(inv -> {
            Map<String, String> h = hash(inv.getArgument(0));
            long removed = 0;
            for (int i = 1; i < inv.getArguments().length; i++) {
                Object arg = inv.getArguments()[i];
                if (arg instanceof Object[] many) {
                    for (Object o : many) { if (h.remove(String.valueOf(o)) != null) removed++; }
                } else if (h.remove(String.valueOf(arg)) != null) {
                    removed++;
                }
            }
            return removed;
        });
        when(hashOps.increment(anyString(), any(), anyLong())).thenAnswer(inv -> {
            Map<String, String> h = hash(inv.getArgument(0));
            String k = String.valueOf((Object) inv.getArgument(1));
            long next = Long.parseLong(h.getOrDefault(k, "0")) + (Long) inv.getArgument(2);
            h.put(k, Long.toString(next));
            return next;
        });

        when(listOps.size(anyString())).thenAnswer(inv -> (long) list(inv.getArgument(0)).size());
        when(listOps.rightPush(anyString(), anyString())).thenAnswer(inv -> { list(inv.getArgument(0)).addLast(inv.getArgument(1)); return (long) list(inv.getArgument(0)).size(); });
        when(listOps.leftPop(anyString(), any(Duration.class))).thenAnswer(inv -> list(inv.getArgument(0)).pollFirst());

        when(template.hasKey(anyString())).thenAnswer(inv -> values.containsKey((String) inv.getArgument(0)));
        when(template.delete(anyString())).thenAnswer(inv -> { values.remove((String) inv.getArgument(0)); lists.remove((String) inv.getArgument(0)); return true; });
        when(template.delete(any(Collection.class))).thenAnswer(inv -> {
            long n = 0;
            for (Object k : (Collection<?>) inv.getArgument(0)) { if (values.remove(String.valueOf(k)) != null) n++; lists.remove(String.valueOf(k)); }
            return n;
        });
        when(template.expire(anyString(), any(Duration.class))).thenReturn(true);
        when(template.execute(any(RedisCallback.class))).thenAnswer(inv -> {
            for (String key : queueKeys) {
                String popped = list(key).pollFirst();
                if (popped != null) {
                    return List.of(key.getBytes(), popped.getBytes());
                }
            }
            return null;
        });
    }

    void expireAlive(String key) {
        values.remove(key);
    }

    private Map<String, String> hash(String key) {
        return hashes.computeIfAbsent(key, k -> new LinkedHashMap<>());
    }

    private Deque<String> list(String key) {
        return lists.computeIfAbsent(key, k -> new ArrayDeque<>());
    }
}
