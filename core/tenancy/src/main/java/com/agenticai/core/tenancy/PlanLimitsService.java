package com.agenticai.core.tenancy;

import com.agenticai.core.resilience.RateLimitedLog;
import com.agenticai.core.security.TenantPlan;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Two-layer read-through cache in front of {@code control_plane.plan_limits}
 * (migration {@code V101__plan_limits.sql}), backing {@link
 * TenantLimitsServiceImpl}'s A2-PR1 refactor away from hardcoded
 * FREE_MAX_* / PREMIUM_MAX_* constants:
 *
 * <ol>
 *   <li>{@code tenantId -> TenantPlan} - Redis key {@code tenant:plan:{id}},
 *       TTL 5 minutes (per A2-PR1's spec text). No plan-resolution cache
 *       existed anywhere in this codebase before this PR (checked: no
 *       {@code @Cacheable}/{@code RedisTemplate} usage in {@code tenancy}
 *       or {@code security} touches plan resolution - only {@link
 *       SignupRateLimiter} uses {@link StringRedisTemplate} today, for an
 *       unrelated IP-keyed counter) - this is new, not a reuse.</li>
 *   <li>{@code TenantPlan -> plan_limits row} - Redis key {@code
 *       plan-limits:{PLAN}}, no TTL. Only 3 possible values ever exist, so
 *       instead of expiring on a timer this layer is explicitly busted by
 *       {@link #evictPlanLimitsCache(TenantPlan)} whenever a
 *       platform-admin edits a row (the web-admin CRUD editor itself is
 *       A2-PR1 task 4, {@code web-admin}-scoped and out of {@code
 *       core-business}'s scope - not built in this PR; this eviction
 *       method is the seam that editor's PUT handler calls).</li>
 * </ol>
 *
 * <p><b>Redis-down fallback:</b> every read here is wrapped so a Redis
 * outage falls back to a direct {@link PlanLimitsRepository}/{@link
 * TenantRepository} read rather than throwing - per A2-PR1's acceptance
 * criteria, this must never fail-open to unlimited (a Redis blip would
 * silently remove every tenant's cap) and never fail-closed to zero (every
 * tenant would suddenly appear at-cap and be unable to create anything).
 * Reading straight through to Postgres is slower but correct; same
 * "degrade to the safe, correct answer, not a convenient one" posture
 * {@link SignupRateLimiter}'s javadoc documents for its own Redis
 * dependency (that one fails open deliberately, for an unrelated abuse
 * throttle where fail-open is the safe choice - plan limits are the
 * opposite case, which is why this class does NOT copy that pattern).
 *
 * <p>Serialization is a plain pipe-delimited string, not JSON - this
 * module has no existing {@code ObjectMapper}-backed Redis cache to match
 * conventions with (checked: {@link
 * com.agenticai.core.tenancy.FeatureFlagService} does not cache at all),
 * and a fixed 15-field record is simple enough that hand-rolled
 * serialization avoids adding a new Jackson dependency/bean-wiring surface
 * to this module for one cache.
 */
@Component
public class PlanLimitsService {

    private static final Logger log = LoggerFactory.getLogger(PlanLimitsService.class);
    /** One WARN per minute per call site while a dependency is down, instead of one (with a stack trace) per request. */
    private static final RateLimitedLog REDIS_DOWN_LOG = new RateLimitedLog(log, Duration.ofMinutes(1));

    private static final String TENANT_PLAN_KEY_PREFIX = "tenant:plan:";
    private static final Duration TENANT_PLAN_TTL = Duration.ofMinutes(5);
    private static final String PLAN_LIMITS_KEY_PREFIX = "plan-limits:";
    private static final String FIELD_SEPARATOR = "|";

    private final PlanLimitsRepository planLimitsRepository;
    private final TenantRepository tenantRepository;
    private final StringRedisTemplate redisTemplate;
    private final FreeModeService freeModeService;

    public PlanLimitsService(
        PlanLimitsRepository planLimitsRepository,
        TenantRepository tenantRepository,
        StringRedisTemplate redisTemplate,
        FreeModeService freeModeService
    ) {
        this.planLimitsRepository = planLimitsRepository;
        this.tenantRepository = tenantRepository;
        this.redisTemplate = redisTemplate;
        this.freeModeService = freeModeService;
    }

    /** Convenience: resolve a tenant's plan, then its plan_limits row, in one call. */
    public ResolvedPlanLimits getLimits(String tenantId) {
        return getLimitsForPlan(resolvePlan(tenantId));
    }

    /**
     * {@code tenantId} (schema name, same key {@link
     * TenantLimitsServiceImpl#resolve} already looks up by) {@code ->}
     * {@link TenantPlan}, Redis read-through, TTL 5m, DB fallback on a
     * Redis outage or cache miss.
     *
     * <p>Free mode (see {@link FreeModeService}) short-circuits BEFORE the
     * cache read: every tenant resolves {@link TenantPlan#UNLIMITED} without
     * touching Redis or Postgres, so flipping the setting takes effect on the
     * next call and no stale {@code tenant:plan:*} entry can hold a tenant at
     * its old paid-tier gate.
     */
    public TenantPlan resolvePlan(String tenantId) {
        if (freeModeService.isEnabled()) {
            return TenantPlan.UNLIMITED;
        }
        String key = TENANT_PLAN_KEY_PREFIX + tenantId;
        String cached = safeGet(key);
        if (cached != null) {
            try {
                return TenantPlan.valueOf(cached);
            } catch (IllegalArgumentException e) {
                log.warn("Cached plan '{}' for tenant '{}' is not a valid TenantPlan - re-reading from DB", cached, tenantId);
            }
        }
        TenantPlan plan = loadTenant(tenantId).getPlan();
        safeSet(key, plan.name(), TENANT_PLAN_TTL);
        return plan;
    }

    /**
     * {@link TenantPlan} {@code ->} {@link ResolvedPlanLimits}, Redis
     * read-through (no TTL - see class javadoc), DB fallback on a Redis
     * outage or cache miss.
     */
    public ResolvedPlanLimits getLimitsForPlan(TenantPlan plan) {
        String key = PLAN_LIMITS_KEY_PREFIX + plan.name();
        String cached = safeGet(key);
        if (cached != null) {
            ResolvedPlanLimits deserialized = deserialize(cached);
            if (deserialized != null) {
                return deserialized;
            }
            log.warn("Cached plan_limits value for plan {} could not be parsed - re-reading from DB", plan);
        }
        ResolvedPlanLimits limits = loadFromDb(plan);
        safeSet(key, serialize(limits), null);
        return limits;
    }

    /**
     * All 3 plans' current limits, one row each, in {@link TenantPlan}
     * declaration order (FREE, PREMIUM, UNLIMITED) - the web-admin plan_limits
     * editor's (A2-PR1 task 4) single read on page load, so it can show every
     * tier's caps side by side without three separate round trips. Each row
     * goes through the same read-through cache {@link #getLimitsForPlan}
     * already uses, so this is 3 cache reads, not a bulk-DB bypass.
     */
    public List<ResolvedPlanLimits> getAllPlanLimits() {
        return Arrays.stream(TenantPlan.values()).map(this::getLimitsForPlan).toList();
    }

    /**
     * Busts the plan_limits cache layer for one plan - called after an
     * admin edits {@code control_plane.plan_limits} so the next read
     * reflects the change (A2-PR1 acceptance: "web-admin edit takes effect
     * <= 5s"; this makes it effectively immediate, well under that bar).
     * Does NOT touch the tenant-plan cache layer (unaffected by an edit to
     * a plan's limits).
     */
    public void evictPlanLimitsCache(TenantPlan plan) {
        try {
            redisTemplate.delete(PLAN_LIMITS_KEY_PREFIX + plan.name());
        } catch (RuntimeException e) {
            log.warn("Redis unavailable evicting plan_limits cache for plan {} - next read may serve a stale cached value "
                + "until it naturally falls out of the cache", plan, e);
        }
    }

    /**
     * A2-PR1 task 4's write path - the platform-admin CRUD editor's PUT
     * handler (see {@code PlatformController#updatePlanLimits} in {@code
     * core/platform}, the {@code web-admin}-scoped consumer this exists
     * for). {@code plan} is always exactly one of the 3 {@link TenantPlan}
     * values and is never itself changed by an edit - only the limits row
     * FOR that plan is replaced. Builds a fresh {@link PlanLimits} via its
     * package-private all-args constructor (same convention {@link
     * PlanLimits}'s own javadoc documents this constructor exists for) and
     * saves it - {@code plan} is a manually-assigned (non-{@code
     * @GeneratedValue}), non-null {@code @Id}, so {@link
     * PlanLimitsRepository#save} merges/updates the existing row for that
     * plan rather than inserting a duplicate (exactly one row per plan
     * always exists, seeded by {@code V101__plan_limits.sql}). Evicts this
     * plan's cache entry afterward so the very next read - anywhere,
     * including a different replica - reflects the edit, same acceptance
     * bar {@link #evictPlanLimitsCache} above already documents.
     */
    public ResolvedPlanLimits update(
        TenantPlan plan,
        int maxUsers,
        int maxAgents,
        int maxWorkflows,
        int maxKbCollections,
        boolean byokAllowed,
        boolean whatsappAllowed,
        boolean whiteLabel,
        boolean liveVisionAllowed,
        boolean externalChannelsAllowed,
        boolean developerApiAllowed,
        boolean ssoAllowed,
        boolean scimAllowed,
        boolean workflowExportAllowed,
        boolean automationAllowed
    ) {
        PlanLimits saved = planLimitsRepository.save(new PlanLimits(
            plan, maxUsers, maxAgents, maxWorkflows, maxKbCollections, byokAllowed, whatsappAllowed, whiteLabel,
            liveVisionAllowed, externalChannelsAllowed, developerApiAllowed, ssoAllowed, scimAllowed,
            workflowExportAllowed, automationAllowed
        ));
        evictPlanLimitsCache(plan);
        return ResolvedPlanLimits.from(saved);
    }

    private ResolvedPlanLimits loadFromDb(TenantPlan plan) {
        PlanLimits row = planLimitsRepository.findById(plan)
            .orElseThrow(() -> new IllegalStateException(
                "No plan_limits row for plan " + plan + " - V101__plan_limits.sql did not run, or its row was deleted"));
        return ResolvedPlanLimits.from(row);
    }

    private Tenant loadTenant(String tenantId) {
        return tenantRepository.findBySchemaName(tenantId)
            .orElseThrow(() -> new IllegalStateException(
                "No Tenant row for schema '" + tenantId + "' - TenantContext claims a tenant that doesn't exist"));
    }

    private String safeGet(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (RuntimeException e) {
            REDIS_DOWN_LOG.warnf("Redis unavailable reading key '{}' - falling back to DB", key, e);
            return null;
        }
    }

    private void safeSet(String key, String value, Duration ttl) {
        try {
            if (ttl != null) {
                redisTemplate.opsForValue().set(key, value, ttl);
            } else {
                redisTemplate.opsForValue().set(key, value);
            }
        } catch (RuntimeException e) {
            REDIS_DOWN_LOG.warnf("Redis unavailable writing key '{}' - continuing without caching this read", key, e);
        }
    }

    private static String serialize(ResolvedPlanLimits limits) {
        return String.join(
            FIELD_SEPARATOR,
            limits.plan().name(),
            Integer.toString(limits.maxUsers()),
            Integer.toString(limits.maxAgents()),
            Integer.toString(limits.maxWorkflows()),
            Integer.toString(limits.maxKbCollections()),
            Boolean.toString(limits.byokAllowed()),
            Boolean.toString(limits.whatsappAllowed()),
            Boolean.toString(limits.whiteLabel()),
            Boolean.toString(limits.liveVisionAllowed()),
            Boolean.toString(limits.externalChannelsAllowed()),
            Boolean.toString(limits.developerApiAllowed()),
            Boolean.toString(limits.ssoAllowed()),
            Boolean.toString(limits.scimAllowed()),
            Boolean.toString(limits.workflowExportAllowed()),
            Boolean.toString(limits.automationAllowed())
        );
    }

    private static ResolvedPlanLimits deserialize(String raw) {
        String[] parts = raw.split("\\" + FIELD_SEPARATOR, -1);
        // Strict field count on purpose: a value cached by a PRE-V470 build
        // (9 parts, before the six billing-entitlement flags) parses as
        // null here, which the caller treats as a cache miss and re-reads
        // from the DB - so a rolling deploy self-heals without a manual
        // Redis eviction (the plan-limits:{PLAN} keys have no TTL - see the
        // class javadoc). Same convention V180 established for the 8->9
        // widening.
        if (parts.length != 15) {
            return null;
        }
        try {
            return new ResolvedPlanLimits(
                TenantPlan.valueOf(parts[0]),
                Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2]),
                Integer.parseInt(parts[3]),
                Integer.parseInt(parts[4]),
                Boolean.parseBoolean(parts[5]),
                Boolean.parseBoolean(parts[6]),
                Boolean.parseBoolean(parts[7]),
                Boolean.parseBoolean(parts[8]),
                Boolean.parseBoolean(parts[9]),
                Boolean.parseBoolean(parts[10]),
                Boolean.parseBoolean(parts[11]),
                Boolean.parseBoolean(parts[12]),
                Boolean.parseBoolean(parts[13]),
                Boolean.parseBoolean(parts[14])
            );
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * A resolved {@code plan_limits} row, decoupled from the JPA entity so
     * callers (and this class's own Redis cache) don't need a Hibernate
     * session to hold one. {@code -1} in any {@code max*} field means "no
     * cap" - {@link TenantLimitsServiceImpl} is the one place that
     * sentinel gets translated into {@code Integer.MAX_VALUE}/{@code
     * Optional.empty()} for the {@code TenantLimits} interface; every
     * other caller of this record should treat -1 as the literal "no cap"
     * value, not a real count.
     */
    public record ResolvedPlanLimits(
        TenantPlan plan,
        int maxUsers,
        int maxAgents,
        int maxWorkflows,
        int maxKbCollections,
        boolean byokAllowed,
        boolean whatsappAllowed,
        boolean whiteLabel,
        boolean liveVisionAllowed,
        boolean externalChannelsAllowed,
        boolean developerApiAllowed,
        boolean ssoAllowed,
        boolean scimAllowed,
        boolean workflowExportAllowed,
        boolean automationAllowed
    ) {
        static ResolvedPlanLimits from(PlanLimits row) {
            return new ResolvedPlanLimits(
                row.getPlan(),
                row.getMaxUsers(),
                row.getMaxAgents(),
                row.getMaxWorkflows(),
                row.getMaxKbCollections(),
                row.isByokAllowed(),
                row.isWhatsappAllowed(),
                row.isWhiteLabel(),
                row.isLiveVisionAllowed(),
                row.isExternalChannelsAllowed(),
                row.isDeveloperApiAllowed(),
                row.isSsoAllowed(),
                row.isScimAllowed(),
                row.isWorkflowExportAllowed(),
                row.isAutomationAllowed()
            );
        }
    }
}
