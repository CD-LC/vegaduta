package com.agenticai.core.tenancy;

import com.agenticai.core.security.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Autonomy/Guardrails Strategy Stage C - the utility every tenant-scoped {@code @Scheduled}
 * background job (no HTTP request, no JWT) MUST use instead of querying its repository directly.
 * {@link TenantSchemaResolver#currentSchemaName()} defaults to {@value
 * TenantSchemaResolver#DEFAULT_SCHEMA} whenever no {@link TenantContext} is set - true for every
 * {@code @Scheduled} method by construction - so a job that queries a tenant-scoped JPA repository
 * without first setting a {@link TenantContext} per tenant will silently only ever see the (empty,
 * for any real tenant) {@code public} schema, appearing to run successfully while doing nothing for
 * every actual tenant. This is the same class of bug found and fixed twice already in this
 * project's history (the knowledge-base cross-tenant leak, the admin-users cross-tenant leak) -
 * this class exists to make it structurally hard to reintroduce a third time.
 *
 * <p>{@code AbandonedSignupCleanupJob} does not need this: {@link Tenant} itself lives in the
 * global {@code control_plane} schema, not a per-tenant one. Any job touching a genuinely
 * tenant-scoped entity (ApprovalRequest, Agent, ChannelSession, etc.) does.
 */
@Component
public class TenantSchemaIterator {

    private static final Logger log = LoggerFactory.getLogger(TenantSchemaIterator.class);
    private static final String SYSTEM_ACTOR = "system:scheduled-job";

    private final TenantRepository tenantRepository;

    private final boolean idleSkipEnabled;
    private final int idleSkipMaxTicks;
    private final long idleSkipMaxMillis;
    private final LongSupplier clockMillis;

    /** Optional: absent in unit tests and shapes without actuator. */
    private volatile MeterRegistry meterRegistry;

    /** jobName -> schema -> idle memo. Bounded: pruned to the live tenant set on every pass. */
    private final Map<String, Map<String, IdleState>> idleMemo = new ConcurrentHashMap<>();

    /**
     * Per-(job, tenant) memo. The advisory job lock serialises ticks across replicas, not threads
     * within one replica, so fields are volatile; a lost update from a concurrent tick only skews
     * an advisory skip count.
     */
    private static final class IdleState {
        volatile int emptyStreak;
        volatile int skipsRemaining;
        volatile long lastVisitMillis;
    }

    /** Idle skipping disabled - identical to the pre-back-off behaviour. */
    public TenantSchemaIterator(TenantRepository tenantRepository) {
        this(tenantRepository, false, 0, 0, System::currentTimeMillis);
    }

    @Autowired
    public TenantSchemaIterator(
            TenantRepository tenantRepository,
            @Value("${app.tenant.idle-skip.enabled:false}") boolean idleSkipEnabled,
            @Value("${app.tenant.idle-skip.max-skipped-ticks:3}") int idleSkipMaxTicks,
            @Value("${app.tenant.idle-skip.max-skip-duration:60s}")
                    java.time.Duration idleSkipMaxDuration) {
        this(
                tenantRepository,
                idleSkipEnabled,
                idleSkipMaxTicks,
                idleSkipMaxDuration.toMillis(),
                System::currentTimeMillis);
    }

    TenantSchemaIterator(
            TenantRepository tenantRepository,
            boolean idleSkipEnabled,
            int idleSkipMaxTicks,
            long idleSkipMaxMillis,
            LongSupplier clockMillis) {
        this.tenantRepository = tenantRepository;
        this.idleSkipEnabled = idleSkipEnabled && idleSkipMaxTicks > 0 && idleSkipMaxMillis > 0;
        this.idleSkipMaxTicks = idleSkipMaxTicks;
        this.idleSkipMaxMillis = idleSkipMaxMillis;
        this.clockMillis = clockMillis;
    }

    @Autowired(required = false)
    void setMeterRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Idle-aware variant of {@link #forEachActiveTenantSchema} for hot, short-interval pollers.
     * {@code hadWork} runs like the plain action but returns whether the tenant had any work. A
     * tenant that reported none is skipped for a bounded, exponentially growing number of following
     * ticks (1, 2, 4 ... capped at {@code max-skipped-ticks}, and never for longer than {@code
     * max-skip-duration} since its last visit), so fresh work waits at most that cap. Any work
     * found, or an exception, resets the tenant to every-tick. With the feature off (default) this
     * is exactly {@link #forEachActiveTenantSchema}. State is per-replica and advisory: losing it
     * only means a tenant is visited, never missed.
     */
    public void forEachActiveTenantSchemaSkippingIdle(String jobName, Predicate<String> hadWork) {
        List<Tenant> tenants = tenantRepository.findByStatus(TenantStatus.ACTIVE);
        if (!idleSkipEnabled) {
            forEach(tenants, hadWork::test);
            record(jobName, tenants.size(), 0);
            return;
        }
        int skipped = 0;
        Map<String, IdleState> memo =
                idleMemo.computeIfAbsent(jobName, k -> new ConcurrentHashMap<>());
        long now = clockMillis.getAsLong();
        Set<String> live = new HashSet<>();
        for (Tenant tenant : tenants) {
            String schema = tenant.getSchemaName();
            live.add(schema);
            IdleState st = memo.computeIfAbsent(schema, k -> new IdleState());
            if (st.skipsRemaining > 0 && now - st.lastVisitMillis < idleSkipMaxMillis) {
                st.skipsRemaining--;
                skipped++;
                continue;
            }
            st.skipsRemaining = 0;
            st.lastVisitMillis = now;
            boolean work = true; // an exception counts as "work": retry next tick
            try {
                TenantContext.set(TenantContext.of(schema, SYSTEM_ACTOR, Set.of()));
                work = hadWork.test(schema);
            } catch (RuntimeException e) {
                log.error("Scheduled job failed for tenant schema '{}'", schema, e);
            } finally {
                TenantContext.clear();
            }
            if (work) {
                st.emptyStreak = 0;
            } else {
                st.emptyStreak = Math.min(st.emptyStreak + 1, 30);
                st.skipsRemaining =
                        (int) Math.min((long) idleSkipMaxTicks, 1L << (st.emptyStreak - 1));
            }
        }
        memo.keySet().retainAll(live);
        record(jobName, tenants.size() - skipped, skipped);
    }

    /**
     * Visit/skip counters per sweep, so an operator can read the real saving (and confirm the
     * back-off is not starving anyone) from {@code /actuator/prometheus} instead of guessing. One
     * increment per pass, not per tenant.
     */
    private void record(String jobName, int visited, int skipped) {
        MeterRegistry registry = meterRegistry;
        if (registry == null) {
            return;
        }
        registry.counter("vegaduta.tenant.sweep.tenants", "job", jobName, "outcome", "visited")
                .increment(visited);
        registry.counter("vegaduta.tenant.sweep.tenants", "job", jobName, "outcome", "skipped")
                .increment(skipped);
    }

    /**
     * Runs {@code action} once per active tenant, with {@link TenantContext} set to that tenant's
     * schema for the duration of the call, so any JPA repository call inside {@code action} is
     * correctly routed by Hibernate's {@link TenantSchemaResolver}.
     *
     * <p>One tenant's action throwing does not stop the others from running - the exception is
     * logged and swallowed here so a single bad tenant (or one bad row) can never block the rest of
     * a scheduled batch. {@link TenantContext#clear()} always runs in a {@code finally}, so a
     * thread-pool-reused thread never leaks one tenant's context into the next iteration or into
     * some unrelated later task.
     */
    public void forEachActiveTenantSchema(Consumer<String> action) {
        forEach(tenantRepository.findByStatus(TenantStatus.ACTIVE), action);
    }

    /**
     * {@link #forEachActiveTenantSchema} minus anonymous {@code /try} sandbox tenants ({@code
     * is_demo}), in {@code created_at} order - for jobs whose output only ever feeds platform-admin
     * reporting that already excludes sandboxes (e.g. the LLM usage rollup behind the spend
     * reports). Jobs that must keep a live sandbox working keep using the method above.
     */
    public void forEachActiveNonDemoTenantSchema(Consumer<String> action) {
        forEach(tenantRepository.findActiveExcludingDemo(), action);
    }

    private void forEach(List<Tenant> tenants, Consumer<String> action) {
        for (Tenant tenant : tenants) {
            String schema = tenant.getSchemaName();
            try {
                TenantContext.set(TenantContext.of(schema, SYSTEM_ACTOR, Set.of()));
                action.accept(schema);
            } catch (RuntimeException e) {
                log.error("Scheduled job failed for tenant schema '{}'", schema, e);
            } finally {
                TenantContext.clear();
            }
        }
    }
}
