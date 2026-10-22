package com.agenticai.core.platform.reliability;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.platform.reliability.SloMath.InstanceWindow;
import com.agenticai.core.platform.reliability.SloSample.Hist;
import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import tools.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Turns this replica's in-memory Micrometer counters into a time series the SLO view can window.
 * Micrometer only holds totals since process start, so a "last 24 hours" figure needs two readings
 * 24 hours apart: this class takes a reading of the cumulative counters every {@code
 * sample-interval-seconds} (default 60) into a bounded in-memory ring (default 48 h), and the SLO
 * service differences two of them.
 *
 * <p><b>What is read</b> (everything already exists; nothing new is recorded on any request path):
 * {@code http.server.requests} (count, status, URI template, and duration histogram buckets when
 * the {@code latency-histograms} profile is on), {@code agentic.chat.ttft}, {@code llm.calls} /
 * {@code llm.call.errors} by provider, and {@code agent.job.completed} by outcome.
 *
 * <p><b>Replicas.</b> The ring is per JVM. With {@code
 * app.platform.reliability.slo.aggregate-via-redis=true} each replica also publishes a thinned copy
 * of its samples (every {@code redis-publish-seconds}, default 300) to a Redis sorted set keyed by
 * a random per-boot instance id, and a read sums every instance's (newest - window-start) delta.
 * Because the id is new on every boot, a restart can never look like a counter reset, and a replica
 * that died inside the window still contributes what it served. Default OFF: with it off (or when
 * Redis fails) the answer covers only the replica that served the request, and the response says
 * so. The job and worker deployables run this too, so agent-job counters are captured wherever
 * those jobs run.
 *
 * <p><b>Not</b> a scheduled job in the advisory-lock sense: it is intentionally per replica (each
 * JVM holds its own counters), reads no database and iterates no tenant, so neither {@code
 * PgAdvisoryJobLock} nor {@code TenantSchemaIterator} applies. It runs on its own daemon thread, so
 * it also works in deployables without {@code @EnableScheduling}. Failures are logged once and
 * never propagate.
 */
@ConditionalOnEditionFeature(EditionFeature.OPERATOR_CONSOLE)
@Component
public class SloSampler {

    private static final Logger log = LoggerFactory.getLogger(SloSampler.class);

    static final String SAMPLES_KEY_PREFIX = "slo:samples:";
    static final String INSTANCES_KEY = "slo:instances";
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    /** The readings behind one window answer. */
    record WindowRead(List<InstanceWindow> windows, boolean aggregated, List<String> notes) {}

    private final ObjectProvider<MeterRegistry> registryProvider;
    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final boolean enabled;
    private final long intervalMs;
    private final long retentionMs;
    private final boolean viaRedis;
    private final long redisPublishMs;
    private final String instanceId = UUID.randomUUID().toString();
    private final Deque<SloSample> ring = new ArrayDeque<>();

    /** Package-private and non-final so tests can pin time. */
    Clock clock = Clock.systemUTC();

    private volatile MeterRegistry registry;
    private ScheduledExecutorService executor;
    private long lastPublishMs;
    private boolean warnedSample;
    private volatile boolean warnedRedis;

    public SloSampler(
            ObjectProvider<MeterRegistry> registryProvider,
            ObjectProvider<StringRedisTemplate> redisProvider,
            @Value("${app.platform.reliability.enabled:true}") boolean enabled,
            @Value("${app.platform.reliability.slo.sample-interval-seconds:60}")
                    long intervalSeconds,
            @Value("${app.platform.reliability.slo.retention-hours:48}") long retentionHours,
            @Value("${app.platform.reliability.slo.aggregate-via-redis:false}") boolean viaRedis,
            @Value("${app.platform.reliability.slo.redis-publish-seconds:300}")
                    long redisPublishSeconds) {
        this.registryProvider = registryProvider;
        this.redisProvider = redisProvider;
        this.enabled = enabled;
        this.intervalMs = Math.max(5, intervalSeconds) * 1000L;
        this.retentionMs = Math.max(1, retentionHours) * 3_600_000L;
        this.viaRedis = viaRedis;
        this.redisPublishMs = Math.max(30, redisPublishSeconds) * 1000L;
    }

    // ------------------------------------------------------------------ lifecycle

    @PostConstruct
    void start() {
        if (!enabled) {
            return;
        }
        registry = registryProvider.getIfAvailable();
        if (registry == null) {
            log.info("SLO sampler not started: no MeterRegistry bean");
            return;
        }
        tick(); // the baseline reading: a window that reaches back past this process's start begins
        // here
        executor = TenantAwareExecutors.newSingleThreadScheduledExecutor("slo-sampler", true);
        executor.scheduleWithFixedDelay(this::tick, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ sampling

    /** One sample into the ring (and, when due, to Redis). Never throws. */
    void tick() {
        try {
            long now = clock.millis();
            SloSample sample = collect(registry, now);
            record(sample);
            if (viaRedis && now - lastPublishMs >= redisPublishMs) {
                lastPublishMs = now;
                publish(sample, now);
            }
        } catch (RuntimeException e) {
            if (!warnedSample) {
                warnedSample = true;
                log.warn(
                        "SLO sampler tick failed ({}); it will keep trying every interval",
                        e.getClass().getSimpleName());
            }
        }
    }

    /**
     * Appends to the ring and drops samples older than the retention. Package-private for tests.
     */
    void record(SloSample sample) {
        synchronized (ring) {
            ring.addLast(sample);
            long cutoff = sample.t() - retentionMs;
            while (ring.size() > 1 && ring.peekFirst().t() < cutoff) {
                ring.pollFirst();
            }
        }
    }

    /**
     * Reads the cumulative counters of {@code registry} into a sample. Series that do not exist yet
     * read as absent/zero. Package-private static so it can be tested against a {@code
     * SimpleMeterRegistry}.
     */
    static SloSample collect(MeterRegistry registry, long nowMs) {
        double req = 0;
        double err = 0;
        List<Timer> chat = new ArrayList<>();
        List<Timer> api = new ArrayList<>();
        for (Timer timer : registry.find("http.server.requests").timers()) {
            String uri = timer.getId().getTag("uri");
            if (SloMath.isActuator(uri)) {
                continue;
            }
            double count = timer.count();
            req += count;
            if (SloMath.isServerError(timer.getId().getTag("status"))) {
                err += count;
            }
            if (SloMath.isChatTurn(uri)) {
                chat.add(timer);
            } else if (!SloMath.isLongLived(uri)) {
                // Long-lived streams still count for availability above; their duration is not an
                // API-latency signal.
                api.add(timer);
            }
        }

        Map<String, double[]> llm = new TreeMap<>();
        for (Counter c : registry.find("llm.calls").counters()) {
            String provider = c.getId().getTag("provider");
            if (provider != null) {
                llm.computeIfAbsent(provider, k -> new double[2])[0] += c.count();
            }
        }
        for (Counter c : registry.find("llm.call.errors").counters()) {
            String provider = c.getId().getTag("provider");
            if (provider != null) {
                llm.computeIfAbsent(provider, k -> new double[2])[1] += c.count();
            }
        }

        double jobOk = 0;
        double jobFailed = 0;
        for (Counter c : registry.find("agent.job.completed").counters()) {
            String outcome = c.getId().getTag("outcome");
            if ("SUCCEEDED".equals(outcome)) {
                jobOk += c.count();
            } else if ("FAILED".equals(outcome)) {
                jobFailed += c.count();
            }
        }

        return new SloSample(
                SloSample.CURRENT_VERSION,
                nowMs,
                req,
                err,
                histOf(chat),
                histOf(api),
                histOf(registry.find("agentic.chat.ttft").timers()),
                llm,
                jobOk,
                jobFailed);
    }

    /** Merges timers into one cumulative histogram; null when there are none. */
    static Hist histOf(Collection<Timer> timers) {
        if (timers == null || timers.isEmpty()) {
            return null;
        }
        double count = 0;
        int withBuckets = 0;
        TreeMap<Double, Double> merged = new TreeMap<>();
        for (Timer timer : timers) {
            HistogramSnapshot snapshot = timer.takeSnapshot();
            count += snapshot.count();
            CountAtBucket[] buckets = snapshot.histogramCounts();
            if (buckets != null && buckets.length > 0) {
                withBuckets++;
                for (CountAtBucket b : buckets) {
                    double bound = b.bucket(TimeUnit.SECONDS);
                    if (Double.isFinite(bound)) {
                        merged.merge(bound, b.count(), Double::sum);
                    }
                }
            }
        }
        if (withBuckets == 0 || merged.isEmpty()) {
            return new Hist(count, new double[0], new double[0]);
        }
        double[] bounds = new double[merged.size()];
        double[] cum = new double[merged.size()];
        int i = 0;
        for (Map.Entry<Double, Double> e : merged.entrySet()) {
            bounds[i] = e.getKey();
            cum[i] = e.getValue();
            i++;
        }
        return new Hist(count, bounds, cum);
    }

    // ------------------------------------------------------------------ reading a window

    /** Whether samples are being taken here at all. */
    boolean isSampling() {
        return enabled && registry != null;
    }

    boolean isAggregatingViaRedis() {
        return viaRedis;
    }

    long retentionHours() {
        return retentionMs / 3_600_000L;
    }

    long intervalSeconds() {
        return intervalMs / 1000L;
    }

    /**
     * The per-replica windows covering the last {@code window}. Always includes this replica (from
     * its own ring plus a fresh reading); with Redis aggregation on it also includes every other
     * replica that published samples. Never throws: a Redis failure falls back to this replica
     * alone and says so in the notes.
     */
    WindowRead read(Duration window) {
        List<String> notes = new ArrayList<>();
        long now = clock.millis();
        long start = now - window.toMillis();
        List<InstanceWindow> windows = new ArrayList<>();

        InstanceWindow own = ownWindow(start, now);
        if (own != null) {
            windows.add(own);
        }
        if (!viaRedis) {
            return new WindowRead(windows, false, notes);
        }
        try {
            StringRedisTemplate redis = redisProvider.getIfAvailable();
            if (redis == null) {
                notes.add(
                        "Cross-replica aggregation is enabled but Redis is not available; these figures cover only "
                                + "the replica that answered.");
                return new WindowRead(windows, false, notes);
            }
            int unreadable = 0;
            Set<String> ids =
                    redis.opsForZSet()
                            .rangeByScore(
                                    INSTANCES_KEY, now - retentionMs, Double.POSITIVE_INFINITY);
            if (ids != null) {
                for (String id : ids) {
                    if (instanceId.equals(id)) {
                        continue;
                    }
                    String key = SAMPLES_KEY_PREFIX + id;
                    Set<String> first =
                            redis.opsForZSet()
                                    .rangeByScore(key, start, Double.POSITIVE_INFINITY, 0, 1);
                    Set<String> last = redis.opsForZSet().reverseRange(key, 0, 0);
                    SloSample base = parseFirst(first);
                    SloSample latest = parseFirst(last);
                    if (base == null || latest == null) {
                        // An empty set just means the replica went away before the window began (or
                        // its data
                        // expired); a member that is present but does not parse is the unreadable
                        // case.
                        boolean firstBroken = first != null && !first.isEmpty() && base == null;
                        boolean lastBroken = last != null && !last.isEmpty() && latest == null;
                        if (firstBroken || lastBroken) {
                            unreadable++;
                        }
                        continue;
                    }
                    windows.add(new InstanceWindow(id, base, latest));
                }
            }
            if (unreadable > 0) {
                notes.add(
                        unreadable + " replica sample set(s) could not be read and were left out.");
            }
            warnedRedis = false;
            return new WindowRead(windows, true, notes);
        } catch (RuntimeException e) {
            if (!warnedRedis) {
                warnedRedis = true;
                log.warn(
                        "SLO cross-replica read failed ({}); answering from this replica only",
                        e.getClass().getSimpleName());
            }
            notes.add(
                    "Cross-replica aggregation failed just now; these figures cover only the replica that answered.");
            List<InstanceWindow> onlyOwn = new ArrayList<>();
            if (own != null) {
                onlyOwn.add(own);
            }
            return new WindowRead(onlyOwn, false, notes);
        }
    }

    /** This replica: first ring sample at or after the window start, against a fresh reading. */
    InstanceWindow ownWindow(long startMs, long nowMs) {
        SloSample base = null;
        SloSample lastInRing = null;
        synchronized (ring) {
            for (SloSample s : ring) {
                if (base == null && s.t() >= startMs) {
                    base = s;
                }
                lastInRing = s;
            }
            if (base == null) {
                base = ring.peekFirst();
            }
        }
        if (base == null) {
            return null;
        }
        SloSample latest = lastInRing;
        if (registry != null) {
            try {
                latest = collect(registry, nowMs);
            } catch (RuntimeException e) {
                // keep the newest ring sample
            }
        }
        return new InstanceWindow(instanceId, base, latest);
    }

    private static SloSample parseFirst(Set<String> members) {
        if (members == null || members.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readValue(members.iterator().next(), SloSample.class);
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ publishing

    private void publish(SloSample sample, long now) {
        try {
            StringRedisTemplate redis = redisProvider.getIfAvailable();
            if (redis == null) {
                return;
            }
            String key = SAMPLES_KEY_PREFIX + instanceId;
            long ttlSeconds = retentionMs / 1000L + 3600L;
            redis.opsForZSet().add(key, MAPPER.writeValueAsString(sample), now);
            redis.opsForZSet().removeRangeByScore(key, 0, now - retentionMs);
            redis.expire(key, ttlSeconds, TimeUnit.SECONDS);
            redis.opsForZSet().add(INSTANCES_KEY, instanceId, now);
            redis.opsForZSet().removeRangeByScore(INSTANCES_KEY, 0, now - retentionMs);
            redis.expire(INSTANCES_KEY, ttlSeconds, TimeUnit.SECONDS);
            warnedRedis = false;
        } catch (Exception e) {
            if (!warnedRedis) {
                warnedRedis = true;
                log.warn(
                        "SLO sample publish to Redis failed ({}); keeping the local ring only",
                        e.getClass().getSimpleName());
            }
        }
    }
}
