package com.agenticai.core.agents.tools.e2b;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Meters {@code run_code} calls that run on the PLATFORM-managed E2B key (see {@code
 * E2bAccessResolver}): a per-tenant daily run counter with plan-tiered caps, plus the wall-clock
 * ceiling every platform-paid run is forced to. A tenant on its own E2B key is never counted here.
 *
 * <p>Plan read: {@link TenantLimits#storedPlan} on purpose (platform spend must not open to
 * everyone just because free mode answers UNLIMITED). FREE gets {@code free-daily-runs}; PREMIUM
 * and UNLIMITED both get {@code paid-daily-runs} - there is deliberately no uncapped tier, because
 * a complimentary signup grant is stored as UNLIMITED. An unknown plan or a failed lookup is
 * treated as FREE; no tenant context at all is refused.
 *
 * <p>Two further bounds on platform spend: {@code global-daily-runs} is a platform-wide ceiling
 * across ALL tenants per UTC day (so cost does not scale with sign-ups), and the shared /try demo
 * tenant ({@link TenantLimits#isSharedDemoTenant}) is refused unless {@code allow-demo-tenant} is
 * set - anonymous visitors would otherwise share one tenant's allowance on the platform key.
 *
 * <p>In-memory by default, same single-replica posture as {@link SandboxFileRegistry}: a restart
 * resets the day's counters (errs towards the user, bounded by the cap again), and a second core
 * replica would let a tenant use the cap once per instance. The day is the UTC calendar day.
 *
 * <p>{@code app.sandbox.platform-e2b.quota-store=redis} moves the counters to Redis ({@link
 * SandboxQuotaRedisCounters}) so the caps hold across replicas; any Redis failure falls back to the
 * per-JVM counters below, i.e. to the default behaviour.
 */
@Component
public class PlatformSandboxRunQuota {

    private static final Logger log = LoggerFactory.getLogger(PlatformSandboxRunQuota.class);

    static final String NO_WORKSPACE_MESSAGE =
            "Code execution is not available here: no workspace could be identified for this run.";
    static final String DEMO_TENANT_MESSAGE =
            "Code execution is not available in the shared demo workspace. Sign in to your own workspace to use it.";
    static final String GLOBAL_LIMIT_MESSAGE =
            "The shared sandbox has reached its capacity for today. Connect your own E2B key on the "
                    + "Providers page to keep going, or try again tomorrow.";
    static final int DEFAULT_GLOBAL_DAILY_RUNS = 2000;

    /**
     * Upper bound on the forced wall-clock, so a config typo cannot buy long platform-paid runs.
     */
    static final int MAX_TIMEOUT_CEILING_SECONDS = 120;

    private final ObjectProvider<TenantLimits> tenantLimits;
    private final int freeDailyRuns;
    private final int paidDailyRuns;
    private final int maxTimeoutSeconds;
    private final int globalDailyRuns;
    private final boolean allowDemoTenant;
    private final Supplier<LocalDate> today;

    /**
     * {@code memory} (default) or {@code redis}; field-injected so the test-built constructors keep
     * the default.
     */
    @Value("${app.sandbox.platform-e2b.quota-store:memory}")
    private String quotaStore = "memory";

    private volatile ObjectProvider<StringRedisTemplate> redisProvider;
    private volatile SandboxQuotaRedisCounters sharedCounters;

    private LocalDate day;
    private int globalRunsToday;
    private final Map<String, Integer> runsToday = new HashMap<>();

    @Autowired
    public PlatformSandboxRunQuota(
            ObjectProvider<TenantLimits> tenantLimits,
            @Value("${app.sandbox.platform-e2b.free-daily-runs:20}") int freeDailyRuns,
            @Value("${app.sandbox.platform-e2b.paid-daily-runs:200}") int paidDailyRuns,
            @Value("${app.sandbox.platform-e2b.max-timeout-seconds:30}") int maxTimeoutSeconds,
            @Value("${app.sandbox.platform-e2b.global-daily-runs:2000}") int globalDailyRuns,
            @Value("${app.sandbox.platform-e2b.allow-demo-tenant:false}") boolean allowDemoTenant) {
        this(
                tenantLimits,
                freeDailyRuns,
                paidDailyRuns,
                maxTimeoutSeconds,
                globalDailyRuns,
                allowDemoTenant,
                () -> LocalDate.now(ZoneOffset.UTC));
    }

    PlatformSandboxRunQuota(
            ObjectProvider<TenantLimits> tenantLimits,
            int freeDailyRuns,
            int paidDailyRuns,
            int maxTimeoutSeconds,
            Supplier<LocalDate> today) {
        this(
                tenantLimits,
                freeDailyRuns,
                paidDailyRuns,
                maxTimeoutSeconds,
                DEFAULT_GLOBAL_DAILY_RUNS,
                false,
                today);
    }

    PlatformSandboxRunQuota(
            ObjectProvider<TenantLimits> tenantLimits,
            int freeDailyRuns,
            int paidDailyRuns,
            int maxTimeoutSeconds,
            int globalDailyRuns,
            boolean allowDemoTenant,
            Supplier<LocalDate> today) {
        this.tenantLimits = tenantLimits;
        this.globalDailyRuns = Math.max(0, globalDailyRuns);
        this.allowDemoTenant = allowDemoTenant;
        this.freeDailyRuns = Math.max(0, freeDailyRuns);
        this.paidDailyRuns = Math.max(0, paidDailyRuns);
        this.maxTimeoutSeconds =
                Math.min(MAX_TIMEOUT_CEILING_SECONDS, Math.max(1, maxTimeoutSeconds));
        this.today = today;
    }

    /** Setter-injected and optional so the many test-built constructors stay unchanged. */
    @Autowired(required = false)
    void setRedisProvider(ObjectProvider<StringRedisTemplate> provider) {
        this.redisProvider = provider;
    }

    /** Test seam: use these shared counters regardless of the quota-store property. */
    void useSharedCounters(SandboxQuotaRedisCounters counters) {
        this.sharedCounters = counters;
    }

    /**
     * The Redis counters when quota-store=redis and a template exists, else null (per-JVM counters
     * apply).
     */
    private SandboxQuotaRedisCounters shared() {
        SandboxQuotaRedisCounters ready = sharedCounters;
        if (ready != null) {
            return ready;
        }
        if (!"redis".equalsIgnoreCase(quotaStore == null ? "" : quotaStore.strip())) {
            return null;
        }
        ObjectProvider<StringRedisTemplate> provider = redisProvider;
        StringRedisTemplate template = provider == null ? null : provider.getIfAvailable();
        if (template == null) {
            return null;
        }
        sharedCounters = new SandboxQuotaRedisCounters(template);
        return sharedCounters;
    }

    /** Wall-clock budget (seconds) forced on every run that uses the platform key. */
    public int maxTimeoutSeconds() {
        return maxTimeoutSeconds;
    }

    /**
     * Counts one platform-paid run for the current tenant. Returns null when the run may proceed,
     * otherwise a short user-facing reason (the run must NOT be started).
     */
    public String tryConsume() {
        String tenantId = currentTenant();
        if (tenantId == null) {
            return NO_WORKSPACE_MESSAGE;
        }
        if (!allowDemoTenant && isSharedDemoTenant(tenantId)) {
            return DEMO_TENANT_MESSAGE;
        }
        int cap = dailyCapFor(tenantId);
        SandboxQuotaRedisCounters redis = shared();
        if (redis != null) {
            switch (redis.consume(today.get(), tenantId, cap, globalDailyRuns)) {
                case ALLOWED -> {
                    return null;
                }
                case TENANT_LIMIT -> {
                    return limitMessage(cap);
                }
                case GLOBAL_LIMIT -> {
                    log.warn(
                            "Platform sandbox global daily ceiling reached ({} runs)",
                            globalDailyRuns);
                    return GLOBAL_LIMIT_MESSAGE;
                }
                case UNAVAILABLE -> {
                    // fall through to the per-JVM counters: today's behaviour
                }
            }
        }
        synchronized (this) {
            rollDay();
            int used = runsToday.getOrDefault(tenantId, 0);
            if (used >= cap) {
                return limitMessage(cap);
            }
            if (globalRunsToday >= globalDailyRuns) {
                log.warn(
                        "Platform sandbox global daily ceiling reached ({} runs)", globalDailyRuns);
                return GLOBAL_LIMIT_MESSAGE;
            }
            runsToday.put(tenantId, used + 1);
            globalRunsToday++;
            return null;
        }
    }

    /**
     * Gives back a run counted by {@link #tryConsume()}. Only for a run whose sandbox was never
     * CREATED; a failure after creation has cost platform time and must stay counted.
     */
    public void refund() {
        String tenantId = currentTenant();
        if (tenantId == null) {
            return;
        }
        SandboxQuotaRedisCounters redis = shared();
        if (redis != null && redis.refund(today.get(), tenantId)) {
            return;
        }
        synchronized (this) {
            rollDay();
            Integer used = runsToday.get(tenantId);
            if (used != null && used > 0) {
                runsToday.put(tenantId, used - 1);
                if (globalRunsToday > 0) {
                    globalRunsToday--;
                }
            }
        }
    }

    /** Runs counted today for the current tenant (0 without a tenant). */
    public int usedToday() {
        String tenantId = currentTenant();
        if (tenantId == null) {
            return 0;
        }
        SandboxQuotaRedisCounters redis = shared();
        Integer sharedUsed = redis == null ? null : redis.used(today.get(), tenantId);
        if (sharedUsed != null) {
            return sharedUsed;
        }
        synchronized (this) {
            rollDay();
            return runsToday.getOrDefault(tenantId, 0);
        }
    }

    /** The cap that applies to the given plan (null/unknown = FREE). */
    int dailyCapFor(TenantPlan plan) {
        return plan == TenantPlan.PREMIUM || plan == TenantPlan.UNLIMITED
                ? paidDailyRuns
                : freeDailyRuns;
    }

    static String limitMessage(int cap) {
        return "Daily analysis limit reached ("
                + cap
                + " code runs per day on the shared sandbox). "
                + "Connect your own E2B key on the Providers page to keep going, or try again tomorrow.";
    }

    private int dailyCapFor(String tenantId) {
        TenantPlan plan = null;
        TenantLimits limits = tenantLimits == null ? null : tenantLimits.getIfAvailable();
        if (limits != null) {
            try {
                plan = limits.storedPlan(tenantId);
            } catch (RuntimeException e) {
                log.warn(
                        "Plan lookup failed for the platform sandbox quota - applying the free cap: {}",
                        e.getMessage());
            }
        }
        return dailyCapFor(plan);
    }

    /**
     * Failure-safe towards NOT spending: a failed demo-tenant lookup refuses the platform-paid run.
     */
    private boolean isSharedDemoTenant(String tenantId) {
        TenantLimits limits = tenantLimits == null ? null : tenantLimits.getIfAvailable();
        if (limits == null) {
            return false;
        }
        try {
            return limits.isSharedDemoTenant(tenantId);
        } catch (RuntimeException e) {
            log.warn(
                    "Demo-tenant lookup failed for the platform sandbox quota - refusing the run: {}",
                    e.getMessage());
            return true;
        }
    }

    private void rollDay() {
        LocalDate now = today.get();
        if (!now.equals(day)) {
            day = now;
            globalRunsToday = 0;
            runsToday.clear();
        }
    }

    private static String currentTenant() {
        return TenantContext.current()
                .map(TenantContext::getTenantId)
                .filter(t -> t != null && !t.isBlank())
                .orElse(null);
    }
}
