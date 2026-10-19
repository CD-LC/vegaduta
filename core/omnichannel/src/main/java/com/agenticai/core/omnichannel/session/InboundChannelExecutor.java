package com.agenticai.core.omnichannel.session;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Shared, bounded executor for the blocking LLM round trip behind inbound channel webhooks
 * (WhatsApp QR / Meta / Twilio, Telegram). Replaces {@code CompletableFuture.runAsync(...)} with no
 * executor, which ran those blocking {@code agentChatService.chat(...).block()} calls on {@code
 * ForkJoinPool.commonPool()} (about cores-1 threads): ~100 concurrent chats then starved every
 * tenant and anything else using the common pool.
 *
 * <p>Tasks run on virtual threads (the app already runs with {@code
 * spring.threads.virtual.enabled}), so there is no thread-pool queue; the bound is admission
 * control instead.
 *
 * <p><b>Admission is a two-step permit</b> so a webhook can be shed BEFORE it has any side effect
 * (session create, workflow event, dedup-visible work): a handler calls {@link #acquire} first, and
 * only on success does its DB writes and finally {@link Permit#run} (or {@link Permit#release} if
 * the message turned out to need no LLM step). If {@code acquire} returns null nothing was written,
 * so a provider redelivery cannot repeat a side effect.
 *
 * <p><b>Backpressure, not instant shedding.</b> When a cap is hit, {@code acquire} parks the
 * (virtual) request thread for up to {@code queue-wait-ms} in a bounded, <b>FIFO</b> wait queue
 * ({@code queue-capacity} waiters, at most {@code queue-per-tenant} of them per tenant, so one
 * flooding tenant cannot fill it). A newcomer never barges ahead of a parked waiter that could be
 * admitted. A short burst is therefore delayed, not dropped. Only when the wait times out or the
 * queue is full does {@link #acquire} return null.
 *
 * <p><b>Never-drop channels</b> ({@link #acquireNeverDrop}: Twilio, WhatsApp QR). Their providers
 * do not redeliver after a 5xx (Twilio) or at all (the QR bridge), and these messages were
 * processed before this class existed, so they must never be lost by it. After the wait they are
 * admitted as <i>overflow</i> against a hard ceiling {@code overflow-max} (default 64) AND a
 * per-tenant ceiling {@code min(overflow-per-tenant-max, tenant cap)}, so a tenant is bounded at
 * about twice its cap and cannot eat the overflow of others. If even that is exhausted the permit
 * is an <i>inline</i> permit: the task runs on the caller's request thread (the pre-existing
 * behaviour), never a 503. That is a deliberate bounded-but-not-lossy trade: the load is then
 * limited only by the web tier's own thread budget.
 *
 * <p><b>Caps.</b> Global cap {@code max-concurrent}; a tenant may hold at most {@code
 * min(per-tenant-max-concurrent, max(1, global/4))}; and the last {@code global/4} slots are held
 * back for tenants that currently have nothing in flight, so a handful of busy tenants cannot fill
 * the whole replica and starve a quiet one. Caps are per core replica (in-memory, deliberately not
 * shared). Per-tenant bookkeeping is removed at zero, so memory is bounded by tenants with work in
 * flight.
 *
 * <p>{@link TenantContext} (a plain ThreadLocal) is captured when the permit is acquired and
 * re-applied around the task on the worker thread.
 *
 * <p><b>Not re-entrant by design:</b> a task already running here must never call {@link #acquire}
 * / {@link #submit} for its own follow-up step (it holds a slot, so at the cap it would wait on
 * itself and time out). Run the next step inline in the same slot instead.
 *
 * <p><b>Sizing caveat:</b> virtual threads share the JVM's carrier pool with the rest of the
 * virtual-thread request tier. Every task here parks in {@code .block()} on an LLM call, and on
 * Java 21 anything that blocks while pinned (synchronized blocks, JDBC drivers) holds a carrier.
 * Tasks also borrow Hikari connections (max 15, 20s timeout), so a high cap just moves the queue
 * into the connection pool. The default cap is therefore deliberately modest (64 global / 16 per
 * tenant); raise it only after a load test shows interactive HTTP latency and Hikari wait stay
 * healthy.
 *
 * <p><b>Shed response (retry-capable channels).</b> {@code shed-mode} ({@code retry} default |
 * {@code ack}) tells Meta and Telegram handlers what to answer when {@link #acquire} still returns
 * null: {@code retry} = release the dedup claim and answer 503 so the provider redelivers, {@code
 * ack} = release the claim, answer 200 and drop the message. {@code retry} is the safe default.
 * Caveat: redelivery relies on the Redis dedup claims ({@link InboundMessageDedupService}) to skip
 * messages that were already accepted from the same payload; dedup fails OPEN on a Redis error, so
 * during a Redis outage a redelivered Meta batch may double-reply to the accepted messages, and a
 * failed claim release makes the shed message look like a duplicate on redelivery. Both are the
 * lesser evil next to dropping the message, and shedding is rare with the wait queue in front of
 * it.
 *
 * <p><b>Cheap paths use {@link #inline()}.</b> Handlers that can tell, without a side effect, that
 * a message needs no LLM step (unpaired sender notice, workflow-trigger takeover) take an inline
 * permit instead of a slot, so a tenant saturated with LLM chats cannot delay or shed them.
 */
@Component
public class InboundChannelExecutor implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(InboundChannelExecutor.class);
    private static final String NO_TENANT = "__no-tenant__";
    static final long SHUTDOWN_WAIT_SECONDS = 10;
    static final int DEFAULT_QUEUE_CAPACITY = 64;
    static final long DEFAULT_QUEUE_WAIT_MS = 5_000;
    static final int DEFAULT_OVERFLOW_MAX = 64;
    static final int DEFAULT_QUEUE_PER_TENANT = 16;
    static final int DEFAULT_OVERFLOW_PER_TENANT = 16;

    private final Executor delegate;
    private final int maxConcurrent;
    private final int perTenantMaxConcurrent;
    private final int tenantCap;
    private final int headroom;
    private final int queueCapacity;
    private final long queueWaitMs;
    private final int overflowMax;
    private final int queuePerTenant;
    private final int overflowPerTenant;
    private final boolean shedAcks;
    private final InboundExecutorMetrics metrics;
    private final ExecutorService owned;

    /**
     * Shutdown grace; package-private so a test does not have to sit through the production 10s.
     */
    volatile long shutdownWaitMs = SHUTDOWN_WAIT_SECONDS * 1000;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition freed = lock.newCondition();
    // All guarded by lock.
    private final Map<String, Integer> perTenant = new HashMap<>();
    private int inFlight;
    private final Map<String, Integer> overflowPerTenantInFlight = new HashMap<>();
    private final Map<String, Integer> waitingPerTenant = new HashMap<>();
    private final LinkedList<Waiter> waiters = new LinkedList<>();
    private int overflowInFlight;

    @Autowired
    public InboundChannelExecutor(
            @Value("${app.omnichannel.inbound-executor.max-concurrent:64}") int maxConcurrent,
            @Value("${app.omnichannel.inbound-executor.per-tenant-max-concurrent:16}")
                    int perTenantMaxConcurrent,
            @Value("${app.omnichannel.inbound-executor.queue-capacity:64}") int queueCapacity,
            @Value("${app.omnichannel.inbound-executor.queue-wait-ms:5000}") long queueWaitMs,
            @Value("${app.omnichannel.inbound-executor.overflow-max:64}") int overflowMax,
            @Value("${app.omnichannel.inbound-executor.queue-per-tenant:16}") int queuePerTenant,
            @Value("${app.omnichannel.inbound-executor.overflow-per-tenant-max:16}")
                    int overflowPerTenant,
            @Value("${app.omnichannel.inbound-executor.shed-mode:retry}") String shedMode,
            ObjectProvider<MeterRegistry> registry) {
        this(
                TenantAwareExecutors.newVirtualThreadPerTaskExecutor("inbound-channel-executor-"),
                maxConcurrent,
                perTenantMaxConcurrent,
                queueCapacity,
                queueWaitMs,
                overflowMax,
                queuePerTenant,
                overflowPerTenant,
                "ack".equalsIgnoreCase(shedMode == null ? "" : shedMode.trim()),
                registry.getIfAvailable(),
                true);
    }

    /** Test constructor: no waiting (instant shed at a cap), no overflow, retry-style shed. */
    public InboundChannelExecutor(
            Executor delegate,
            int maxConcurrent,
            int perTenantMaxConcurrent,
            MeterRegistry registry) {
        this(
                delegate,
                maxConcurrent,
                perTenantMaxConcurrent,
                DEFAULT_QUEUE_CAPACITY,
                0,
                0,
                DEFAULT_QUEUE_PER_TENANT,
                DEFAULT_OVERFLOW_PER_TENANT,
                false,
                registry,
                false);
    }

    /** Test constructor with explicit queue / overflow / shed settings. */
    public InboundChannelExecutor(
            Executor delegate,
            int maxConcurrent,
            int perTenantMaxConcurrent,
            int queueCapacity,
            long queueWaitMs,
            int overflowMax,
            boolean shedAcks,
            MeterRegistry registry) {
        this(
                delegate,
                maxConcurrent,
                perTenantMaxConcurrent,
                queueCapacity,
                queueWaitMs,
                overflowMax,
                Math.max(1, queueCapacity / 4),
                DEFAULT_OVERFLOW_PER_TENANT,
                shedAcks,
                registry,
                false);
    }

    /** Test constructor with explicit per-tenant queue and overflow bounds as well. */
    public InboundChannelExecutor(
            Executor delegate,
            int maxConcurrent,
            int perTenantMaxConcurrent,
            int queueCapacity,
            long queueWaitMs,
            int overflowMax,
            int queuePerTenant,
            int overflowPerTenant,
            boolean shedAcks,
            MeterRegistry registry) {
        this(
                delegate,
                maxConcurrent,
                perTenantMaxConcurrent,
                queueCapacity,
                queueWaitMs,
                overflowMax,
                queuePerTenant,
                overflowPerTenant,
                shedAcks,
                registry,
                false);
    }

    private InboundChannelExecutor(
            Executor delegate,
            int maxConcurrent,
            int perTenantMaxConcurrent,
            int queueCapacity,
            long queueWaitMs,
            int overflowMax,
            int queuePerTenant,
            int overflowPerTenant,
            boolean shedAcks,
            MeterRegistry registry,
            boolean ownsDelegate) {
        this.delegate = delegate;
        this.maxConcurrent = Math.max(1, maxConcurrent);
        this.perTenantMaxConcurrent = Math.max(1, perTenantMaxConcurrent);
        // Fairness: one tenant never takes more than a quarter of the replica, and the last
        // quarter is reserved for tenants with nothing in flight (see class javadoc).
        this.tenantCap = Math.min(this.perTenantMaxConcurrent, Math.max(1, this.maxConcurrent / 4));
        this.headroom = this.maxConcurrent / 4;
        this.queueCapacity = Math.max(0, queueCapacity);
        this.queueWaitMs = Math.max(0, queueWaitMs);
        this.overflowMax = Math.max(0, overflowMax);
        // One tenant may park at most this many waiters and hold at most this many overflow
        // slots; the overflow bound is further capped at the tenant's own cap, so a tenant tops
        // out near 2x its cap.
        this.queuePerTenant =
                Math.max(1, Math.min(queuePerTenant, Math.max(1, this.queueCapacity)));
        this.overflowPerTenant = Math.max(0, Math.min(overflowPerTenant, this.tenantCap));
        this.shedAcks = shedAcks;
        this.metrics = new InboundExecutorMetrics(registry);
        this.owned = ownsDelegate && delegate instanceof ExecutorService es ? es : null;
    }

    /** Runs tasks inline on the calling thread, still applying the caps; for tests. */
    public static InboundChannelExecutor synchronous() {
        return new InboundChannelExecutor(Runnable::run, 1_000, 1_000, null);
    }

    /**
     * True when Meta / Telegram handlers should answer 200 (drop) rather than 503 (retry) on a
     * shed.
     */
    public boolean shedAcks() {
        return shedAcks;
    }

    private enum Kind {
        SLOT,
        OVERFLOW,
        INLINE
    }

    /** A parked acquire() call; identity-compared. */
    private static final class Waiter {
        final String tenant;

        Waiter(String tenant) {
            this.tenant = tenant;
        }
    }

    /**
     * A claimed admission slot. Use exactly one of {@link #run} or {@link #release}; both are
     * idempotent and the second call is a no-op.
     */
    public final class Permit {
        private final Optional<TenantContext> snapshot;
        private final String tenant;
        private final String channel;
        private final Kind kind;
        private final boolean neverDrop;
        private final AtomicBoolean spent = new AtomicBoolean();

        private Permit(
                Optional<TenantContext> snapshot,
                String tenant,
                String channel,
                Kind kind,
                boolean neverDrop) {
            this.snapshot = snapshot;
            this.tenant = tenant;
            this.channel = channel;
            this.kind = kind;
            this.neverDrop = neverDrop;
        }

        /**
         * Hand the task to the worker (an inline permit runs it right here on the caller's thread).
         *
         * @return true if the task ran or was handed to the worker (it runs exactly once); false if
         *     the delegate rejected it (slot freed). A never-drop permit instead runs the task on
         *     the caller's thread when the delegate rejects it, so it still returns true.
         */
        public boolean run(Runnable task) {
            if (!spent.compareAndSet(false, true)) {
                return false;
            }
            if (kind == Kind.INLINE) {
                work(task);
                return true;
            }
            try {
                delegate.execute(() -> work(task));
                metrics.count("accepted", channel);
                return true;
            } catch (RejectedExecutionException e) {
                if (neverDrop) {
                    metrics.count("inline_fallback", channel);
                    work(task);
                    return true;
                }
                free();
                metrics.reject(channel, "executor", tenant);
                return false;
            }
        }

        /**
         * Run the task on the CALLER's thread while holding this slot (the slot is freed when it
         * ends). For channels whose request thread legitimately waits for the whole turn
         * (Telegram), which keeps per-chat ordering exactly as before the executor existed.
         */
        public void runInline(Runnable task) {
            if (spent.compareAndSet(false, true)) {
                work(task);
            }
        }

        /** Give the slot back without running anything (the message needed no LLM step). */
        public void release() {
            if (spent.compareAndSet(false, true)) {
                free();
            }
        }

        private void work(Runnable task) {
            Optional<TenantContext> previous = TenantContext.current();
            snapshot.ifPresent(TenantContext::set);
            try {
                task.run();
            } catch (RuntimeException e) {
                // Callers handle their own failures; this only keeps a stray one from
                // breaking bookkeeping.
                log.warn("Inbound channel task threw: {}", e.getMessage(), e);
            } finally {
                if (previous.isPresent()) {
                    TenantContext.set(previous.get());
                } else {
                    TenantContext.clear();
                }
                free();
            }
        }

        private void free() {
            if (kind == Kind.INLINE) {
                return;
            }
            lock.lock();
            try {
                if (kind == Kind.OVERFLOW) {
                    overflowInFlight--;
                    overflowPerTenantInFlight.compute(
                            tenant, (k, c) -> (c == null || c <= 1) ? null : c - 1);
                } else {
                    perTenant.compute(tenant, (k, c) -> (c == null || c <= 1) ? null : c - 1);
                    inFlight--;
                }
                freed.signalAll();
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * A permit that holds no slot and runs its task on the caller's thread. For messages that are
     * known (without a side effect) to need no LLM step, so they are never delayed or shed by LLM
     * load.
     */
    public Permit inline() {
        return new Permit(
                TenantContext.current(),
                TenantContext.current().map(TenantContext::getTenantId).orElse(NO_TENANT),
                "inline",
                Kind.INLINE,
                true);
    }

    /**
     * {@link #acquire(String, long, boolean)} with the configured wait and no overflow (Meta,
     * Telegram).
     */
    public Permit acquire(String channel) {
        return acquire(channel, queueWaitMs, false);
    }

    /**
     * For channels whose provider cannot redeliver (Twilio after a 5xx, the QR bridge): never
     * returns null. Waits in the queue, then takes a bounded per-tenant overflow slot, and as a
     * last resort returns an inline permit that runs the task on the caller's thread (the
     * pre-existing never-drop behaviour).
     */
    public Permit acquireNeverDrop(String channel) {
        Permit permit = acquire(channel, queueWaitMs, true, true);
        if (permit != null) {
            return permit;
        }
        metrics.count("inline_fallback", channel);
        log.warn(
                "Inbound {} caps and overflow exhausted - running on the request thread rather than"
                        + " dropping",
                channel);
        Optional<TenantContext> snapshot = TenantContext.current();
        return new Permit(
                snapshot,
                snapshot.map(TenantContext::getTenantId).orElse(NO_TENANT),
                channel,
                Kind.INLINE,
                true);
    }

    /**
     * Claim a slot for the current tenant, waiting up to {@code waitMs} in the bounded FIFO wait
     * queue. Returns null (nothing held) if no slot could be had; the caller must then answer the
     * provider and release its dedup claim.
     *
     * @param allowOverflow after the wait, admit anyway against the overflow ceilings (global and
     *     per-tenant) instead of returning null
     */
    public Permit acquire(String channel, long waitMs, boolean allowOverflow) {
        return acquire(channel, waitMs, allowOverflow, false);
    }

    private Permit acquire(String channel, long waitMs, boolean allowOverflow, boolean neverDrop) {
        Optional<TenantContext> snapshot = TenantContext.current();
        String tenant = snapshot.map(TenantContext::getTenantId).orElse(NO_TENANT);
        long nanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0, waitMs));
        Waiter me = null;
        boolean queueFull = false;
        String reason;
        lock.lock();
        try {
            try {
                while (true) {
                    // FIFO without head-of-line blocking: admitted iff I can run AND no
                    // earlier-parked waiter that can run is ahead of me. A newcomer (me == null)
                    // therefore never barges.
                    Waiter next = firstAdmissible();
                    if (me == null ? (next == null && canAdmit(tenant)) : next == me) {
                        perTenant.merge(tenant, 1, Integer::sum);
                        inFlight++;
                        return new Permit(snapshot, tenant, channel, Kind.SLOT, neverDrop);
                    }
                    if (nanos <= 0) {
                        break;
                    }
                    if (me == null) {
                        if (waiters.size() >= queueCapacity
                                || waitingPerTenant.getOrDefault(tenant, 0) >= queuePerTenant) {
                            queueFull = true;
                            break;
                        }
                        me = new Waiter(tenant);
                        waiters.add(me);
                        waitingPerTenant.merge(tenant, 1, Integer::sum);
                        metrics.count("queued", channel);
                    }
                    try {
                        nanos = freed.awaitNanos(nanos);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } finally {
                if (me != null) {
                    waiters.remove(me);
                    waitingPerTenant.compute(
                            tenant, (k, c) -> (c == null || c <= 1) ? null : c - 1);
                    // Leaving (admitted or not) may make the next waiter the head.
                    freed.signalAll();
                }
            }
            if (allowOverflow
                    && overflowInFlight < overflowMax
                    && overflowPerTenantInFlight.getOrDefault(tenant, 0) < overflowPerTenant) {
                overflowInFlight++;
                overflowPerTenantInFlight.merge(tenant, 1, Integer::sum);
                metrics.count("overflow_admitted", channel);
                log.warn(
                        "Inbound {} admitted past the cap as overflow (tenant={},"
                                + " overflow-in-flight={})",
                        channel,
                        tenant,
                        overflowInFlight);
                return new Permit(snapshot, tenant, channel, Kind.OVERFLOW, neverDrop);
            }
            reason =
                    perTenant.getOrDefault(tenant, 0) >= tenantCap
                            ? "tenant"
                            : (queueFull ? "queue_full" : "global");
        } finally {
            lock.unlock();
        }
        metrics.reject(channel, reason, tenant);
        return null;
    }

    /**
     * Must hold the lock. The first parked waiter (arrival order) whose tenant could be admitted
     * right now, or null.
     */
    private Waiter firstAdmissible() {
        for (Iterator<Waiter> it = waiters.iterator(); it.hasNext(); ) {
            Waiter w = it.next();
            if (canAdmit(w.tenant)) {
                return w;
            }
        }
        return null;
    }

    /** Must hold the lock. */
    private boolean canAdmit(String tenant) {
        int mine = perTenant.getOrDefault(tenant, 0);
        if (mine >= tenantCap) {
            return false;
        }
        // A tenant already running work may not eat the reserved headroom; an idle tenant may.
        return inFlight < (mine == 0 ? maxConcurrent : maxConcurrent - headroom);
    }

    /**
     * Convenience for fire-and-forget callers with no pre-work: acquire without waiting, then run.
     *
     * @return true if the task was accepted (it will run exactly once); false if a cap was hit or
     *     the delegate rejected it, in which case nothing was run and nothing is held.
     */
    public boolean submit(String channel, Runnable task) {
        Permit permit = acquire(channel, 0, false);
        return permit != null && permit.run(task);
    }

    int inFlight() {
        lock.lock();
        try {
            return inFlight + overflowInFlight;
        } finally {
            lock.unlock();
        }
    }

    public int waiting() {
        lock.lock();
        try {
            return waiters.size();
        } finally {
            lock.unlock();
        }
    }

    int trackedTenants() {
        lock.lock();
        try {
            return perTenant.size();
        } finally {
            lock.unlock();
        }
    }

    int maxConcurrent() {
        return maxConcurrent;
    }

    int perTenantMaxConcurrent() {
        return perTenantMaxConcurrent;
    }

    int queueCapacity() {
        return queueCapacity;
    }

    public long queueWaitMs() {
        return queueWaitMs;
    }

    int overflowMax() {
        return overflowMax;
    }

    /**
     * Stop admitting work and give in-flight chats a bounded window to finish (SIGTERM grace
     * periods are short; waiting on a multi-minute LLM chat would get the pod killed with pools
     * half closed), then interrupt whatever is left.
     */
    @Override
    public void destroy() {
        if (owned == null) {
            return;
        }
        owned.shutdown();
        try {
            if (!owned.awaitTermination(shutdownWaitMs, TimeUnit.MILLISECONDS)) {
                log.warn(
                        "Inbound channel executor still has tasks after {}ms - interrupting",
                        shutdownWaitMs);
                owned.shutdownNow();
            }
        } catch (InterruptedException e) {
            owned.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
