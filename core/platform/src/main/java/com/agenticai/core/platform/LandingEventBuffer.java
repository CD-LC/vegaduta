package com.agenticai.core.platform;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.agenticai.core.tenancy.AllowUnlockedSchedule;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Opt-in write-behind buffer for {@link LandingEventController}'s beacon
 * ({@code app.landing-events.batch-enabled=true}; absent/false means the
 * controller keeps its original synchronous Redis path and this bean is not
 * even created).
 *
 * <p><b>Premise correction:</b> the landing beacon never wrote database rows -
 * it {@code INCR}s Redis counters (see the controller javadoc for why). So
 * there is no JDBC insert, no schema and no tenant to resolve: the flush
 * thread needs no {@code TenantContext}, and nothing here touches PgBouncer.
 * "Batch write" therefore means: accept the event in memory, then flush
 * aggregated {@code INCRBY} deltas from a scheduled tick instead of two
 * Redis round trips (IP lid + counter) on the request thread of every
 * anonymous visitor.
 *
 * <p><b>Lid semantics preserved:</b> the per-IP hourly lid (300) is applied at
 * flush time. Events are grouped per (ip, UTC hour) in arrival order; the
 * group's size is added to the lid counter with one {@code INCRBY}, and only
 * as many of that group's events as still fit under the lid are counted. So
 * the same events are dropped as in synchronous mode (the latest of an IP's
 * over-limit burst), just decided in aggregate. The event's UTC day/hour are
 * captured at accept time, so a flush after midnight still credits the day
 * the visitor acted on.
 *
 * <p><b>Bounded memory / overflow:</b> the queue holds at most
 * {@code app.landing-events.buffer-capacity} entries. When full, the OLDEST
 * entry is dropped (fresh traffic is a better sample of "now" and the request
 * never blocks) and {@code landing.events.dropped} is incremented. The counter
 * carries no tags (no per-event/per-IP cardinality).
 *
 * <p><b>Redis down:</b> a failed flush puts the unprocessed entries back at
 * the front of the queue (still capped; overflow counts as dropped) and the
 * next tick retries. Redis absent entirely (no {@link StringRedisTemplate}
 * bean) discards the batch, matching the synchronous path's fail-open
 * behaviour. If a failure lands mid-batch, the already-applied groups are not
 * re-applied but the failing group may be partially applied and retried, so a
 * counter can over-count by at most one group - acceptable for a vanity
 * funnel metric.
 *
 * <p><b>Loss-on-crash tradeoff (deliberate):</b> events sit in memory for up
 * to one flush interval. A JVM crash/kill (SIGKILL, OOM) loses whatever was
 * buffered - at most {@code buffer-capacity} events, typically a second's
 * worth. A graceful shutdown flushes via {@link #shutdownFlush()}. Per-replica
 * buffers are independent, which is safe because the Redis counters are
 * additive. See docs/plans/CORE-LANDING-EVENT-BATCHING.md.
 */
@Component
@ConditionalOnProperty(name = "app.landing-events.batch-enabled", havingValue = "true")
public class LandingEventBuffer {

    private static final Logger log = LoggerFactory.getLogger(LandingEventBuffer.class);

    static final String COUNTER_KEY_PREFIX = "agentic:landing:";
    static final String RATE_KEY_PREFIX = "agentic:landing-rate:";
    static final Duration COUNTER_TTL = Duration.ofDays(90);
    static final Duration RATE_WINDOW = Duration.ofHours(1);
    static final int MAX_EVENTS_PER_IP_PER_HOUR = 300;

    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter HOUR_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH");
    /** Safety valve so a shutdown flush against a dead Redis cannot spin. */
    private static final int MAX_SHUTDOWN_ROUNDS = 100;

    /** One accepted beacon; day/hour fixed at accept time (UTC). */
    record Entry(String event, String ip, String day, String hour) {}

    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final int capacity;
    private final int maxBatchSize;
    private final Counter dropped;

    private final Deque<Entry> queue = new ArrayDeque<>();
    private final Object queueLock = new Object();
    /**
     * Serializes flushes. A {@link java.util.concurrent.locks.ReentrantLock}, not {@code synchronized}: the
     * critical section does Redis I/O, and {@code synchronized} around blocking I/O pins a virtual thread to
     * its carrier on JDK 21 (JAVA-024). {@code queueLock} guards pure in-memory work and stays a monitor.
     */
    private final java.util.concurrent.locks.ReentrantLock flushLock = new java.util.concurrent.locks.ReentrantLock();

    public LandingEventBuffer(
        ObjectProvider<StringRedisTemplate> redisTemplateProvider,
        MeterRegistry meterRegistry,
        @Value("${app.landing-events.buffer-capacity:10000}") int capacity,
        @Value("${app.landing-events.max-batch-size:500}") int maxBatchSize
    ) {
        this.redisTemplateProvider = redisTemplateProvider;
        this.capacity = Math.max(1, capacity);
        this.maxBatchSize = Math.max(1, maxBatchSize);
        this.dropped = Counter.builder("landing.events.dropped")
            .description("Landing beacon events dropped from the in-memory buffer (overflow or flush overflow)")
            .register(meterRegistry);
    }

    /** Accepts an already-vocabulary-checked event. Never blocks, never throws. */
    public void offer(String event, String clientIp) {
        Entry entry = new Entry(
            event,
            clientIp,
            LocalDate.now(ZoneOffset.UTC).format(DAY_FORMAT),
            LocalDateTime.now(ZoneOffset.UTC).format(HOUR_FORMAT)
        );
        synchronized (queueLock) {
            if (queue.size() >= capacity) {
                queue.pollFirst(); // drop-oldest
                dropped.increment();
            }
            queue.addLast(entry);
        }
    }

    int size() {
        synchronized (queueLock) {
            return queue.size();
        }
    }

    @AllowUnlockedSchedule(reason = "Flushes THIS JVM's own in-memory event queue to Redis; every replica must drain its own buffer.")
    @Scheduled(fixedDelayString = "${app.landing-events.flush-interval-ms:1000}")
    public void scheduledFlush() {
        try {
            flushOnce();
        } catch (RuntimeException e) {
            log.warn("landing-event flush tick failed: {}", e.getMessage());
        }
    }

    /** Graceful-stop drain: keep flushing until empty, Redis fails, or the round cap. */
    @PreDestroy
    public void shutdownFlush() {
        try {
            for (int i = 0; i < MAX_SHUTDOWN_ROUNDS && size() > 0; i++) {
                if (!flushOnce()) {
                    break;
                }
            }
        } catch (RuntimeException e) {
            log.warn("landing-event shutdown flush failed, {} events lost: {}", size(), e.getMessage());
        }
    }

    /**
     * Flushes one batch of at most {@code maxBatchSize} entries.
     *
     * @return false if Redis rejected the batch (entries were re-queued), true otherwise
     */
    boolean flushOnce() {
        flushLock.lock();
        try {
            List<Entry> batch = drain();
            if (batch.isEmpty()) {
                return true;
            }
            StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
            if (redis == null) {
                return true; // fail open like the synchronous path: measurement, not a feature
            }

            Map<String, List<Entry>> groups = new LinkedHashMap<>();
            for (Entry e : batch) {
                groups.computeIfAbsent(e.ip() + "|" + e.hour(), k -> new ArrayList<>()).add(e);
            }
            List<List<Entry>> pending = new ArrayList<>(groups.values());
            int done = 0;
            try {
                for (; done < pending.size(); done++) {
                    applyGroup(redis, pending.get(done));
                }
                return true;
            } catch (RuntimeException e) {
                log.debug("landing-event flush deferred (Redis error): {}", e.getMessage());
                List<Entry> unprocessed = new ArrayList<>();
                for (int i = done; i < pending.size(); i++) {
                    unprocessed.addAll(pending.get(i));
                }
                requeue(unprocessed);
                return false;
            }
        } finally {
            flushLock.unlock();
        }
    }

    private void applyGroup(StringRedisTemplate redis, List<Entry> group) {
        Entry first = group.get(0);
        long n = group.size();
        String rateKey = RATE_KEY_PREFIX + first.ip() + ":" + first.hour();
        Long total = redis.opsForValue().increment(rateKey, n);
        long allowed = n;
        if (total != null) {
            if (total == n) {
                redis.expire(rateKey, RATE_WINDOW);
            }
            allowed = Math.max(0L, Math.min(n, MAX_EVENTS_PER_IP_PER_HOUR - (total - n)));
        }
        if (allowed == 0L) {
            return; // over the hourly lid - drop silently, as the synchronous path does
        }
        Map<String, Long> deltas = new LinkedHashMap<>();
        for (int i = 0; i < allowed; i++) {
            Entry e = group.get(i);
            deltas.merge(COUNTER_KEY_PREFIX + e.event() + ":" + e.day(), 1L, Long::sum);
        }
        for (Map.Entry<String, Long> d : deltas.entrySet()) {
            Long count = redis.opsForValue().increment(d.getKey(), d.getValue());
            if (count != null && count.longValue() == d.getValue().longValue()) {
                redis.expire(d.getKey(), COUNTER_TTL);
            }
        }
    }

    private List<Entry> drain() {
        synchronized (queueLock) {
            int n = Math.min(maxBatchSize, queue.size());
            List<Entry> batch = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                batch.add(queue.pollFirst());
            }
            return batch;
        }
    }

    /** Puts entries back at the front in original order; whatever no longer fits is dropped (oldest first). */
    private void requeue(List<Entry> entries) {
        synchronized (queueLock) {
            for (int i = entries.size() - 1; i >= 0; i--) {
                if (queue.size() >= capacity) {
                    // no room: the remaining (older) entries are the ones dropped
                    dropped.increment(i + 1);
                    return;
                }
                queue.addFirst(entries.get(i));
            }
        }
    }
}
