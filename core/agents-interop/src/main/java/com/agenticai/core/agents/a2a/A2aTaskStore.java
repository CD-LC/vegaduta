package com.agenticai.core.agents.a2a;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.resilience.RateLimitedLog;
import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import com.agenticai.core.security.logging.LogSafe;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * TTL-bounded registry of A2A tasks: an in-memory map that is ALWAYS the source of truth for tasks
 * created on this JVM, optionally mirrored to Redis ({@code app.a2a.task-store=redis}, default
 * {@code memory}) so a second core replica can answer {@code GetTask}/{@code CancelTask} for them.
 *
 * <p><b>{@code memory} mode (default) - known limitation, documented rather than hidden:</b> a
 * per-JVM {@link ConcurrentHashMap}. A task is retrievable only from the replica that created it;
 * behind a load balancer with more than one core replica and no session affinity a follow-up call
 * may land elsewhere and get {@code -32001 TaskNotFound}. A restart forgets every task (the
 * CONVERSATION is not lost - session memory is Redis-backed by {@code contextId}, only the task
 * record wrapping one turn of it). Nothing is durable past {@link A2aProperties#getTaskTtl()}.
 *
 * <p><b>{@code redis} mode:</b> a live {@link A2aTask} holds a reactor subscription and a cancel
 * sink, which cannot be serialised, so Redis holds a JSON <i>snapshot</i> ({@code
 * A2aTask#snapshot()} + the immutable owner/ message fields) under {@code
 * a2a:task:<tenantId>:<taskId>} with {@link A2aProperties#getTaskTtl()} as its Redis TTL (refreshed
 * on every write and every read). The snapshot is written on creation, on WORKING, and on any
 * terminal transition ({@link #save}); streamed deltas are NOT mirrored, so a remote {@code
 * GetTask} on a still-running task shows its state with an empty reply until it finishes. What
 * crosses replicas:
 *
 * <ul>
 *   <li>{@code GetTask} from a replica that does not own the task returns a DETACHED copy rebuilt
 *       from the snapshot.
 *   <li>{@code CancelTask} from a non-owner flips the snapshot to CANCELED. The owning replica
 *       honours it the next time the task is looked up there ({@link #find}), which cuts the token
 *       stream; until then the upstream turn keeps running, and a terminal state already in Redis
 *       is never overwritten by the owner's later write.
 *   <li>A restart still loses the live turn (its subscription), but a task that reached a terminal
 *       state before the restart stays readable.
 * </ul>
 *
 * Every Redis call fails OPEN to the in-memory behaviour: an error is logged and the local copy
 * answers, exactly as in {@code memory} mode (same posture as {@code ToolCircuitBreakerService}).
 * Keys embed the tenant, so a lookup with another tenant's id computes a different key and can
 * never see the record.
 *
 * <p><b>Durability and orphans (redis mode):</b> every record carries the id of the JVM instance
 * that owns it plus a heartbeat the owner refreshes every {@link
 * A2aProperties#getHeartbeatInterval()}. A non-terminal record owned by ANOTHER instance whose
 * heartbeat is older than {@link A2aProperties#getHeartbeatStaleAfter()} (a previous boot, a
 * crashed replica) is marked FAILED with "interrupted by restart" - by a periodic scan on every
 * replica ({@link #reconcileOrphans}) and lazily on read. After a restart an orphan therefore still
 * reads {@code working} until it is stale (about 60s plus one reconcile interval with defaults),
 * not instantly. If a live owner is silent longer than that (long GC pause, Redis outage) a peer
 * may fail its task; the owner then cancels its local turn on the next heartbeat. The Redis key TTL
 * ({@code app.a2a.task-ttl}) bounds how long any record lives. In {@code memory} mode nothing
 * survives a restart (the task simply is not found), so there are no orphans to reconcile; that
 * mode is single-replica only (a get_task/cancel_task routed to another replica reports "not
 * found").
 *
 * <p><b>Remote cancel (redis mode):</b> a cancel from a non-owner replica flips the snapshot AND
 * publishes the key on the {@value #CANCEL_CHANNEL} pub/sub channel; the owner cuts the running
 * stream immediately on receipt. If pub/sub is unavailable the owner's heartbeat tick still applies
 * it within one heartbeat interval.
 *
 * <p><b>Scoping:</b> {@link #find} requires the caller's tenant AND subject to match the task's
 * recorded owner. A UUID is unguessable, but "unguessable" is not an authorization model, and a
 * task's snapshot contains the agent's full reply - so a leaked id from tenant A must be worthless
 * to a caller in tenant B (or to a different user in tenant A), and must be worthless in a way that
 * does not confirm the task exists: a mismatch is an empty {@link Optional}, indistinguishable from
 * "never existed".
 */
@ConditionalOnEditionFeature(EditionFeature.A2A_DIRECTORY)
@Component
public class A2aTaskStore {

    private static final Logger log = LoggerFactory.getLogger(A2aTaskStore.class);

    /**
     * One WARN per minute per call site while a dependency is down, instead of one (with a stack
     * trace) per request.
     */
    private static final RateLimitedLog REDIS_DOWN_LOG =
            new RateLimitedLog(log, Duration.ofMinutes(1));

    /**
     * Sweeps are O(n) over the map; rate-limit them so a burst of puts doesn't sweep on every one.
     */
    private static final Duration MIN_SWEEP_INTERVAL = Duration.ofSeconds(30);

    private static final String KEY_PREFIX = "a2a:task:";

    /** Pub/sub channel carrying the Redis key of a task a non-owner replica just canceled. */
    static final String CANCEL_CHANNEL = "a2a:task-cancel";

    static final String INTERRUPTED_MESSAGE = "interrupted by restart";

    /** Key segment for a task created with no bound tenant (the "insecure" profile). */
    private static final String NO_TENANT = "_none";

    private static final ObjectMapper JSON = JsonMappers.lenient();

    private final Map<String, A2aTask> tasks = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final int maxTasks;
    private final StringRedisTemplate redis;
    private final AtomicReference<Instant> lastSweep = new AtomicReference<>(Instant.EPOCH);

    /** Identity of this JVM boot; stamped on every record this instance writes. */
    private final String instanceId = UUID.randomUUID().toString();

    private final Duration staleAfter;
    private final Duration heartbeatInterval;
    private ScheduledExecutorService scheduler;
    private RedisMessageListenerContainer cancelListener;

    @Autowired
    public A2aTaskStore(
            A2aProperties properties, ObjectProvider<StringRedisTemplate> redisProvider) {
        this(
                properties.getTaskTtl(),
                properties.getMaxTasks(),
                "redis".equalsIgnoreCase(properties.getTaskStore())
                        ? redisProvider.getIfAvailable()
                        : null,
                properties.getHeartbeatInterval(),
                properties.getHeartbeatStaleAfter());
    }

    /** Test seam - in-memory only. */
    A2aTaskStore(Duration ttl, int maxTasks) {
        this(ttl, maxTasks, null);
    }

    /** Test seam - Redis-mirrored when {@code redis} is non-null. */
    A2aTaskStore(Duration ttl, int maxTasks, StringRedisTemplate redis) {
        this(ttl, maxTasks, redis, Duration.ofSeconds(10), Duration.ofSeconds(60));
    }

    /** Test seam - Redis-mirrored with explicit heartbeat timing. */
    A2aTaskStore(
            Duration ttl,
            int maxTasks,
            StringRedisTemplate redis,
            Duration heartbeatInterval,
            Duration staleAfter) {
        this.ttl = ttl;
        this.maxTasks = maxTasks;
        this.redis = redis;
        this.heartbeatInterval = heartbeatInterval;
        this.staleAfter = staleAfter;
    }

    /**
     * Redis mode only: starts the heartbeat/orphan-reconcile timers and the remote-cancel
     * subscription.
     */
    @PostConstruct
    void start() {
        if (redis == null) {
            return;
        }
        long beatMs = Math.max(1000L, heartbeatInterval.toMillis());
        long reconcileMs = Math.max(15_000L, staleAfter.toMillis() / 4);
        scheduler = TenantAwareExecutors.newSingleThreadScheduledExecutor("a2a-task-store", true);
        scheduler.scheduleWithFixedDelay(this::heartbeat, beatMs, beatMs, TimeUnit.MILLISECONDS);
        // First pass shortly after boot; a record is only failed once its heartbeat is older than
        // staleAfter,
        // so after a restart orphans can still read "working" for up to staleAfter + one reconcile
        // interval
        // (about 60-75s with defaults) - lazy-on-read fails them as soon as they are stale and
        // read.
        scheduler.scheduleWithFixedDelay(
                this::reconcileOrphans, 5_000L, reconcileMs, TimeUnit.MILLISECONDS);
        try {
            RedisConnectionFactory factory = redis.getConnectionFactory();
            if (factory != null) {
                RedisMessageListenerContainer container = new RedisMessageListenerContainer();
                container.setConnectionFactory(factory);
                container.afterPropertiesSet();
                container.addMessageListener(
                        (message, pattern) ->
                                onCancelMessage(
                                        new String(message.getBody(), StandardCharsets.UTF_8)),
                        new ChannelTopic(CANCEL_CHANNEL));
                container.start();
                cancelListener = container;
            }
        } catch (RuntimeException e) {
            log.warn(
                    "A2A remote-cancel subscription could not start - relying on the heartbeat tick",
                    e);
        }
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (cancelListener != null) {
            try {
                cancelListener.destroy();
            } catch (Exception e) {
                log.debug("A2A remote-cancel listener did not stop cleanly", e);
            }
        }
    }

    /**
     * Refreshes the heartbeat of every non-terminal task THIS instance owns (so peers never mistake
     * it for an orphan) and applies any remote cancel found in Redis.
     */
    void heartbeat() {
        if (redis == null) {
            return;
        }
        for (A2aTask task : tasks.values()) {
            if (task.isTerminal()) {
                continue;
            }
            try {
                Stored existing = read(key(task.tenantId(), task.id()));
                if (existing != null && existing.terminal()) {
                    // Someone (a remote cancel, a reconcile) already ended it: honour that, never
                    // resurrect it.
                    // A FAILED record under a live local task means a peer judged us dead (GC
                    // pause, Redis
                    // outage longer than stale-after): the record cannot be rewritten, so stop the
                    // local
                    // turn rather than keep spending LLM budget on a result nobody can read.
                    if (existing.state() == A2aTaskState.FAILED) {
                        log.warn(
                                "A2A task {} was marked {} by a peer while still running here - canceling the local turn",
                                task.id(),
                                existing.failureMessage());
                    }
                    task.cancel();
                    continue;
                }
                save(task);
            } catch (RuntimeException e) {
                log.warn("A2A task {} heartbeat failed", task.id(), e);
            }
        }
    }

    /**
     * A remote replica canceled {@code redisKey}; if this instance runs that task, cut its stream
     * now.
     */
    void onCancelMessage(String redisKey) {
        if (redisKey == null) {
            return;
        }
        A2aTask task = tasks.get(redisKey.substring(redisKey.lastIndexOf(':') + 1));
        if (task != null) {
            honourRemoteCancel(task);
        }
    }

    /** Scans every task record and fails the orphans of dead/previous instances. */
    void reconcileOrphans() {
        if (redis == null) {
            return;
        }
        int failed = 0;
        try (Cursor<String> cursor =
                redis.scan(ScanOptions.scanOptions().match(KEY_PREFIX + "*").count(200).build())) {
            while (cursor.hasNext()) {
                try {
                    if (reconcileKey(cursor.next())) {
                        failed++;
                    }
                } catch (RuntimeException e) {
                    log.debug("A2A orphan reconcile skipped one record", e);
                }
            }
        } catch (RuntimeException e) {
            REDIS_DOWN_LOG.warnf("A2A orphan reconcile could not scan Redis", e);
        }
        if (failed > 0) {
            log.warn(
                    "A2A task store marked {} orphaned task(s) as failed ({})",
                    failed,
                    INTERRUPTED_MESSAGE);
        }
    }

    /**
     * @return true when the record was an orphan and is now FAILED.
     */
    boolean reconcileKey(String key) {
        Stored stored = read(key);
        return stored != null && reconcileStored(key, stored) != stored;
    }

    private Stored reconcileStored(String key, Stored stored) {
        if (stored.terminal() || instanceId.equals(stored.ownerInstance())) {
            return stored;
        }
        // Records written by an older build carry no owner/heartbeat: fall back to their last state
        // change.
        long beat =
                stored.ownerInstance() == null ? stored.stateTimestampMs() : stored.heartbeatMs();
        long now = System.currentTimeMillis();
        if (now - beat <= staleAfter.toMillis()) {
            return stored;
        }
        Stored failed = stored.interrupted(instanceId, now);
        try {
            redis.opsForValue().set(key, JSON.writeValueAsString(failed), ttl);
        } catch (RuntimeException | JsonProcessingException e) {
            REDIS_DOWN_LOG.warnf(
                    "A2A orphaned task {} could not be marked failed in Redis", stored.id(), e);
        }
        return failed;
    }

    /**
     * True in {@code redis} mode; callers use it to keep the blocking {@link #save} off event-loop
     * threads. Also note that in redis mode {@link #find} does one extra Redis GET per poll of a
     * non-terminal owned task (remote-cancel check).
     */
    public boolean isRedisBacked() {
        return redis != null;
    }

    public void put(A2aTask task) {
        tasks.put(task.id(), task);
        save(task);
        sweepIfDue();
    }

    /**
     * Mirrors the task's current snapshot to Redis (no-op in {@code memory} mode). Called on
     * creation, on the WORKING transition and on any terminal one. A terminal record already in
     * Redis is never overwritten - that is what makes a remote cancel stick even if the owning
     * replica's turn finishes a moment later.
     */
    public void save(A2aTask task) {
        if (redis == null) {
            return;
        }
        String key = key(task.tenantId(), task.id());
        try {
            if (task.isTerminal()) {
                Stored existing = read(key);
                if (existing != null
                        && existing.terminal()
                        && existing.state() != task.snapshot().state()) {
                    return;
                }
            }
            redis.opsForValue()
                    .set(
                            key,
                            JSON.writeValueAsString(
                                    Stored.of(task, instanceId, System.currentTimeMillis())),
                            ttl);
            if (task.snapshot().state() == A2aTaskState.CANCELED) {
                redis.convertAndSend(CANCEL_CHANNEL, key);
            }
        } catch (RuntimeException | JsonProcessingException e) {
            REDIS_DOWN_LOG.warnf(
                    "A2A task {} could not be mirrored to Redis - keeping the in-memory copy only",
                    task.id(),
                    e);
        }
    }

    /**
     * @param tenantId the caller's tenant (null when no {@code TenantContext} is bound - the
     *     "insecure" profile); compared with {@link Objects#equals}, so a null only ever matches a
     *     task that was created with a null too.
     * @param subject the caller's subject, same null semantics.
     */
    public Optional<A2aTask> find(String taskId, String tenantId, String subject) {
        if (taskId == null) {
            return Optional.empty();
        }
        A2aTask task = tasks.get(taskId);
        if (task == null) {
            return redis == null ? Optional.empty() : findRemote(taskId, tenantId, subject);
        }
        if (!Objects.equals(task.tenantId(), tenantId)
                || !Objects.equals(task.subject(), subject)) {
            log.warn(
                    "A2A task {} requested by a non-owner (same tenant: {}) - answering not-found",
                    LogSafe.clean(taskId),
                    Objects.equals(task.tenantId(), tenantId));
            return Optional.empty();
        }
        if (redis != null) {
            honourRemoteCancel(task);
        }
        task.touch();
        return Optional.of(task);
    }

    /**
     * A task this JVM does not hold: rebuild a detached copy from the Redis snapshot,
     * owner-checked.
     */
    private Optional<A2aTask> findRemote(String taskId, String tenantId, String subject) {
        String key = key(tenantId, taskId);
        try {
            Stored stored = read(key);
            if (stored == null) {
                return Optional.empty();
            }
            if (!Objects.equals(stored.tenantId(), tenantId)
                    || !Objects.equals(stored.subject(), subject)) {
                log.warn(
                        "A2A task {} requested by a non-owner (same tenant: {}) - answering not-found",
                        LogSafe.clean(taskId),
                        Objects.equals(stored.tenantId(), tenantId));
                return Optional.empty();
            }
            stored = reconcileStored(key, stored);
            redis.expire(key, ttl);
            return Optional.of(stored.toTask());
        } catch (RuntimeException e) {
            REDIS_DOWN_LOG.warnf(
                    "A2A task {} could not be read from Redis - answering not-found", taskId, e);
            return Optional.empty();
        }
    }

    /**
     * Every task record this deployment still holds for {@code tenantId}: this JVM's live map plus,
     * in {@code redis} mode, detached snapshots other replicas mirrored ({@link Stored#toTask()} -
     * state/reply for reading, no live subscription). Local entries win the dedup because their
     * view of an in-flight task is fresher than the last mirrored snapshot.
     *
     * <p><b>Console surface only.</b> Unlike {@link #find} this matches on tenant ALONE, not
     * tenant+subject - the console controller re-gates callers on the manager role, which is what
     * authorizes reading another subject's task text.
     */
    public List<A2aTask> listForTenant(String tenantId) {
        List<A2aTask> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (A2aTask task : tasks.values()) {
            if (Objects.equals(task.tenantId(), tenantId) && seen.add(task.id())) {
                out.add(task);
            }
        }
        if (redis == null) {
            return out;
        }
        try (Cursor<String> cursor =
                redis.scan(
                        ScanOptions.scanOptions().match(key(tenantId, "*")).count(200).build())) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                Stored stored = read(key);
                if (stored == null
                        || !Objects.equals(stored.tenantId(), tenantId)
                        || !seen.add(stored.id())) {
                    continue;
                }
                out.add(reconcileStored(key, stored).toTask());
            }
        } catch (RuntimeException e) {
            REDIS_DOWN_LOG.warnf("A2A console task list could not scan Redis", e);
        }
        return out;
    }

    /**
     * {@link #find} minus the subject check, for the console's get/cancel: the caller's manager
     * role (enforced by the controller) stands in for task ownership. A detached record restored
     * from Redis can still be canceled - {@link A2aTask#cancel()} on it flips the mirrored record,
     * which is exactly the remote-cancel path {@link #honourRemoteCancel} honours on the owning
     * replica.
     */
    public Optional<A2aTask> findForTenant(String taskId, String tenantId) {
        if (taskId == null) {
            return Optional.empty();
        }
        A2aTask task = tasks.get(taskId);
        if (task != null) {
            if (!Objects.equals(task.tenantId(), tenantId)) {
                return Optional.empty();
            }
            if (redis != null) {
                honourRemoteCancel(task);
            }
            task.touch();
            return Optional.of(task);
        }
        if (redis == null) {
            return Optional.empty();
        }
        String key = key(tenantId, taskId);
        try {
            Stored stored = read(key);
            if (stored == null || !Objects.equals(stored.tenantId(), tenantId)) {
                return Optional.empty();
            }
            stored = reconcileStored(key, stored);
            redis.expire(key, ttl);
            return Optional.of(stored.toTask());
        } catch (RuntimeException e) {
            REDIS_DOWN_LOG.warnf(
                    "A2A task {} could not be read from Redis - answering not-found", taskId, e);
            return Optional.empty();
        }
    }

    /**
     * A non-owner replica canceled this task in Redis; apply it here so the running turn's stream
     * is cut.
     */
    private void honourRemoteCancel(A2aTask task) {
        if (task.isTerminal()) {
            return;
        }
        try {
            Stored stored = read(key(task.tenantId(), task.id()));
            if (stored != null && stored.state() == A2aTaskState.CANCELED) {
                task.cancel();
            }
        } catch (RuntimeException e) {
            REDIS_DOWN_LOG.warnf(
                    "A2A task {} cancel state could not be checked in Redis", task.id(), e);
        }
    }

    private Stored read(String key) {
        String json = redis.opsForValue().get(key);
        if (json == null) {
            return null;
        }
        try {
            return JSON.readValue(json, Stored.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable A2A task record at " + key, e);
        }
    }

    static String key(String tenantId, String taskId) {
        return KEY_PREFIX + (tenantId == null ? NO_TENANT : tenantId) + ":" + taskId;
    }

    int size() {
        return tasks.size();
    }

    private void sweepIfDue() {
        Instant now = Instant.now();
        Instant previous = lastSweep.get();
        boolean overCapacity = tasks.size() > maxTasks;
        if (!overCapacity && previous.plus(MIN_SWEEP_INTERVAL).isAfter(now)) {
            return;
        }
        if (!lastSweep.compareAndSet(previous, now)) {
            return; // another thread is sweeping right now
        }
        sweep(now);
    }

    /**
     * Package-private so the unit test can force a sweep without waiting out {@link
     * #MIN_SWEEP_INTERVAL}.
     */
    void sweep(Instant now) {
        Instant cutoff = now.minus(ttl);
        int expired = 0;
        for (Map.Entry<String, A2aTask> entry : tasks.entrySet()) {
            A2aTask task = entry.getValue();
            if (task.lastTouched().isBefore(cutoff)) {
                // A non-terminal task older than the TTL is a turn that never
                // finished (a hung upstream the blocking timeout didn't catch,
                // e.g. on the async path). Cancel it so its subscription is
                // disposed rather than just dropping our handle to it.
                if (!task.isTerminal()) {
                    task.cancel();
                }
                tasks.remove(entry.getKey(), task);
                expired++;
            }
        }
        int overflow = tasks.size() - maxTasks;
        if (overflow > 0) {
            // Oldest TERMINAL tasks first - an in-flight turn is never evicted for
            // capacity, its result would silently vanish mid-poll.
            List<A2aTask> evictable =
                    tasks.values().stream()
                            .filter(A2aTask::isTerminal)
                            .sorted(Comparator.comparing(A2aTask::lastTouched))
                            .limit(overflow)
                            .toList();
            evictable.forEach(task -> tasks.remove(task.id(), task));
            log.warn(
                    "A2A task store over capacity ({} > {}) - evicted {} finished tasks early",
                    tasks.size() + evictable.size(),
                    maxTasks,
                    evictable.size());
        }
        if (expired > 0) {
            log.debug("A2A task store sweep expired {} tasks", expired);
        }
    }
}
