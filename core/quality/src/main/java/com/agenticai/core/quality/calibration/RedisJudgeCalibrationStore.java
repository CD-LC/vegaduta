package com.agenticai.core.quality.calibration;

import com.agenticai.core.json.JsonMappers;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

/**
 * The production {@link JudgeCalibrationStore}: JSON documents in Redis hashes, no SQL table (this
 * repo ships no Flyway migration for the feature). Layout, all keys embedding the tenant so one
 * tenant can never read another's:
 *
 * <ul>
 *   <li>{@code quality:judgecal:<tenant>:sets} - hash, field = set id, value = set JSON
 *   <li>{@code quality:judgecal:<tenant>:items:<setId>} - hash, field = item id, value = item JSON
 *   <li>{@code quality:judgecal:<tenant>:results} - hash, field = result id, value = result JSON,
 *       trimmed to the newest {@value InMemoryJudgeCalibrationStore#MAX_RESULTS}
 * </ul>
 *
 * No TTL is set, so durability is whatever the Redis deployment's persistence (AOF/RDB) gives; a
 * Redis flush loses sets and history. With no {@link StringRedisTemplate} bean the store falls back
 * to {@link InMemoryJudgeCalibrationStore} (per-JVM, lost on restart). A Redis error on a call
 * propagates (it is not silently answered from memory); the judge-trust lookup that stamps eval
 * runs already swallows it.
 */
@Component
public class RedisJudgeCalibrationStore implements JudgeCalibrationStore {

    private static final Logger log = LoggerFactory.getLogger(RedisJudgeCalibrationStore.class);

    private static final ObjectMapper MAPPER =
            JsonMappers.plain()
                    .findAndRegisterModules()
                    .disable(tools.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final StringRedisTemplate redis;
    private final InMemoryJudgeCalibrationStore fallback = new InMemoryJudgeCalibrationStore();

    public RedisJudgeCalibrationStore(ObjectProvider<StringRedisTemplate> redisProvider) {
        this.redis = redisProvider.getIfAvailable();
        if (this.redis == null) {
            log.warn(
                    "No Redis available: judge calibration sets and history are held in memory only "
                            + "(lost on restart, not shared between replicas).");
        }
    }

    // ------------------------------------------------------------------ keys / json

    private static String base() {
        return "quality:judgecal:" + InMemoryJudgeCalibrationStore.tenantKey();
    }

    private static String setsKey() {
        return base() + ":sets";
    }

    private static String itemsKey(UUID setId) {
        return base() + ":items:" + setId;
    }

    private static String resultsKey() {
        return base() + ":results";
    }

    private static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException("Could not serialise calibration record", e);
        }
    }

    private static <T> Optional<T> read(String json, Class<T> type) {
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(MAPPER.readValue(json, type));
        } catch (JacksonException e) {
            log.warn(
                    "Skipping unreadable calibration record ({}): {}",
                    type.getSimpleName(),
                    e.getOriginalMessage());
            return Optional.empty();
        }
    }

    private <T> List<T> readAll(String key, Class<T> type) {
        Map<String, String> entries = redis.<String, String>opsForHash().entries(key);
        List<T> out = new ArrayList<>(entries.size());
        for (String json : entries.values()) {
            read(json, type).ifPresent(out::add);
        }
        return out;
    }

    // ------------------------------------------------------------------ sets

    @Override
    public JudgeCalibrationSet saveSet(JudgeCalibrationSet set) {
        if (redis == null) {
            return fallback.saveSet(set);
        }
        redis.<String, String>opsForHash().put(setsKey(), set.getId().toString(), write(set));
        return set;
    }

    @Override
    public Optional<JudgeCalibrationSet> findSet(UUID setId) {
        if (redis == null) {
            return fallback.findSet(setId);
        }
        return read(
                redis.<String, String>opsForHash().get(setsKey(), setId.toString()),
                JudgeCalibrationSet.class);
    }

    @Override
    public List<JudgeCalibrationSet> listSets(UUID agentId) {
        if (redis == null) {
            return fallback.listSets(agentId);
        }
        return readAll(setsKey(), JudgeCalibrationSet.class).stream()
                .filter(s -> agentId.equals(s.getAgentId()))
                .sorted(Comparator.comparing(JudgeCalibrationSet::getCreatedAt).reversed())
                .toList();
    }

    @Override
    public void deleteSet(UUID setId) {
        if (redis == null) {
            fallback.deleteSet(setId);
            return;
        }
        redis.<String, String>opsForHash().delete(setsKey(), setId.toString());
        redis.delete(itemsKey(setId));
        Object[] resultIds =
                readAll(resultsKey(), JudgeCalibrationResult.class).stream()
                        .filter(r -> setId.equals(r.getSetId()))
                        .map(r -> r.getId().toString())
                        .toArray();
        if (resultIds.length > 0) {
            redis.<String, String>opsForHash().delete(resultsKey(), resultIds);
        }
    }

    // ------------------------------------------------------------------ items

    @Override
    public List<JudgeCalibrationItem> saveItems(List<JudgeCalibrationItem> items) {
        if (redis == null) {
            return fallback.saveItems(items);
        }
        for (JudgeCalibrationItem item : items) {
            redis.<String, String>opsForHash()
                    .put(itemsKey(item.getSetId()), item.getId().toString(), write(item));
        }
        return items;
    }

    @Override
    public List<JudgeCalibrationItem> listItems(UUID setId) {
        if (redis == null) {
            return fallback.listItems(setId);
        }
        return readAll(itemsKey(setId), JudgeCalibrationItem.class).stream()
                .sorted(
                        Comparator.comparingInt(JudgeCalibrationItem::getPosition)
                                .thenComparing(JudgeCalibrationItem::getCreatedAt))
                .toList();
    }

    @Override
    public long countItems(UUID setId) {
        if (redis == null) {
            return fallback.countItems(setId);
        }
        Long size = redis.<String, String>opsForHash().size(itemsKey(setId));
        return size == null ? 0 : size;
    }

    @Override
    public Optional<JudgeCalibrationItem> findItem(UUID setId, UUID itemId) {
        if (redis == null) {
            return fallback.findItem(setId, itemId);
        }
        return read(
                redis.<String, String>opsForHash().get(itemsKey(setId), itemId.toString()),
                JudgeCalibrationItem.class);
    }

    @Override
    public void deleteItem(UUID setId, UUID itemId) {
        if (redis == null) {
            fallback.deleteItem(setId, itemId);
            return;
        }
        redis.<String, String>opsForHash().delete(itemsKey(setId), itemId.toString());
    }

    // ------------------------------------------------------------------ results

    @Override
    public JudgeCalibrationResult saveResult(JudgeCalibrationResult result) {
        if (redis == null) {
            return fallback.saveResult(result);
        }
        var hash = redis.<String, String>opsForHash();
        hash.put(resultsKey(), result.getId().toString(), write(result));
        Long size = hash.size(resultsKey());
        if (size != null && size > InMemoryJudgeCalibrationStore.MAX_RESULTS) {
            Object[] oldest =
                    readAll(resultsKey(), JudgeCalibrationResult.class).stream()
                            .sorted(Comparator.comparing(JudgeCalibrationResult::getCreatedAt))
                            .limit(size - InMemoryJudgeCalibrationStore.MAX_RESULTS)
                            .map(r -> r.getId().toString())
                            .toArray();
            if (oldest.length > 0) {
                hash.delete(resultsKey(), oldest);
            }
        }
        return result;
    }

    private List<JudgeCalibrationResult> newestFirst(
            Predicate<JudgeCalibrationResult> filter, int limit) {
        return readAll(resultsKey(), JudgeCalibrationResult.class).stream()
                .filter(filter)
                .sorted(Comparator.comparing(JudgeCalibrationResult::getCreatedAt).reversed())
                .limit(Math.max(0, limit))
                .toList();
    }

    @Override
    public List<JudgeCalibrationResult> resultsForAgent(UUID agentId, int limit) {
        if (redis == null) {
            return fallback.resultsForAgent(agentId, limit);
        }
        return newestFirst(r -> agentId.equals(r.getAgentId()), limit);
    }

    @Override
    public List<JudgeCalibrationResult> resultsForSet(UUID setId, int limit) {
        if (redis == null) {
            return fallback.resultsForSet(setId, limit);
        }
        return newestFirst(r -> setId.equals(r.getSetId()), limit);
    }

    @Override
    public Optional<JudgeCalibrationResult> latestForJudgeModel(String judgeModel) {
        if (redis == null) {
            return fallback.latestForJudgeModel(judgeModel);
        }
        return newestFirst(r -> judgeModel != null && judgeModel.equals(r.getJudgeModel()), 1)
                .stream()
                .findFirst();
    }
}
