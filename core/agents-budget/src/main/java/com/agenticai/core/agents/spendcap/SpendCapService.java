package com.agenticai.core.agents.spendcap;

import com.agenticai.core.chat.HostedSpendListener;
import com.agenticai.core.chat.LlmUsageRollupService;
import com.agenticai.core.chat.SpendReport;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Per-tenant monthly HOSTED-POOL spend cap (V477, calendar month UTC). BYOK is
 * never counted (only {@link ProviderName#PLATFORM_HOSTED} costs reach
 * {@link #onHostedSpend}) and never blocked (only the hosted-pool gate in
 * {@code FreeTierChatClientService} calls {@link #assertWithinCap}).
 *
 * <p><b>Hot path.</b> {@link #assertWithinCap} costs one in-memory lookup for a
 * tenant without a cap (the default - cap config is cached {@link #CONFIG_TTL}).
 * With a cap it adds one Redis GET of the month-to-date counter
 * {@code spendcap:mtd:<tenantId>:<yyyy-MM>}.
 *
 * <p><b>Counter.</b> {@link #onHostedSpend} adds each recorded hosted cost with
 * INCRBYFLOAT - but only when the key already exists (Lua), so a missing key is
 * never re-created holding just one call. A missing key (new month, Redis
 * restart, cap just set) is seeded from the database: the daily rollup for the
 * month's finished days plus a live aggregation of today, PLATFORM_HOSTED only
 * (the same source as the platform spend reports). Known approximation: a
 * finished day the nightly rollup has not processed yet is missing from a
 * seed; a call recorded between a seed's read and its SETNX is not counted.
 *
 * <p><b>Redis down.</b> Falls back to the database computation, cached
 * {@link #DB_FALLBACK_TTL} per tenant, with a WARN at most once a minute. If
 * that fails too, the call is ALLOWED (fail open) with an ERROR log - an
 * infrastructure fault must not stop every capped tenant's chat. A tenant with
 * no cap never touches Redis or the database here at all.
 *
 * <p><b>Alerts.</b> Crossing 80% and 100% fires {@link SpendCapAlertSink}s once
 * per tenant, month and stage. The once-ness is a conditional UPDATE on
 * {@code spend_cap_alert_stage/month}, so it holds across replicas; the sinks
 * run on a small background thread that drops work rather than block a call.
 */
@Service
public class SpendCapService implements HostedSpendListener {

    private static final Logger log = LoggerFactory.getLogger(SpendCapService.class);

    static final String KEY_PREFIX = "spendcap:mtd:";
    static final Duration KEY_TTL = Duration.ofDays(40);
    static final Duration CONFIG_TTL = Duration.ofSeconds(30);
    static final Duration DB_FALLBACK_TTL = Duration.ofSeconds(60);
    static final BigDecimal MAX_CAP_USD = new BigDecimal("1000000");
    static final BigDecimal EIGHTY_PERCENT = new BigDecimal("0.80");
    private static final String HOSTED = ProviderName.PLATFORM_HOSTED.name();
    private static final String SYSTEM_ACTOR = "system:spend-cap";
    private static final long WARN_INTERVAL_MS = 60_000L;

    static final String SQL_BY_SCHEMA = "SELECT id, schema_name, name, spend_cap_usd, spend_cap_alert_stage, "
        + "spend_cap_alert_month FROM control_plane.tenant WHERE schema_name = ?";
    static final String SQL_BY_ID = "SELECT id, schema_name, name, spend_cap_usd, spend_cap_alert_stage, "
        + "spend_cap_alert_month FROM control_plane.tenant WHERE id = ?";
    static final String SQL_ALL_CAPPED = "SELECT id, schema_name, name, spend_cap_usd, spend_cap_alert_stage, "
        + "spend_cap_alert_month FROM control_plane.tenant WHERE spend_cap_usd IS NOT NULL";
    static final String SQL_SET_CAP = "UPDATE control_plane.tenant SET spend_cap_usd = ?, spend_cap_alert_stage = 0, "
        + "spend_cap_alert_month = NULL WHERE id = ?";
    static final String SQL_CLAIM_STAGE = "UPDATE control_plane.tenant SET spend_cap_alert_stage = ?, "
        + "spend_cap_alert_month = ? WHERE id = ? AND spend_cap_usd IS NOT NULL "
        + "AND (spend_cap_alert_month IS DISTINCT FROM ? OR spend_cap_alert_stage < ?)";

    /** INCRBYFLOAT only when the key exists; nil otherwise (the next read seeds it from the database). */
    static final DefaultRedisScript<String> INCR_IF_EXISTS = new DefaultRedisScript<>(
        "if redis.call('EXISTS', KEYS[1]) == 1 then return redis.call('INCRBYFLOAT', KEYS[1], ARGV[1]) end "
            + "return false", String.class);

    /** A tenant's stored cap configuration. {@code capUsd} null = no cap. */
    public record TenantCap(UUID tenantId, String schema, String name, BigDecimal capUsd, int alertStage,
                            LocalDate alertMonth) {
    }

    /** The {@code GET .../spend-cap} body. */
    public record CapStatus(UUID tenantId, BigDecimal capUsd, BigDecimal monthToDateUsd, BigDecimal percentUsed,
                            int alertStage, boolean blocked, LocalDate monthStart) {
    }

    /** Roster columns; {@code monthToDateUsd} null when it could not be determined. */
    public record RosterCap(BigDecimal capUsd, BigDecimal monthToDateUsd, Boolean blocked) {
    }

    private record Cached<T>(T value, Instant loadedAt) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectProvider<StringRedisTemplate> redis;
    private final LlmUsageRollupService rollupService;
    private final ObjectProvider<SpendCapAlertSink> alertSinks;
    private final Clock clock;
    private final Executor alertExecutor;

    private final Map<String, Cached<Optional<TenantCap>>> configBySchema = new ConcurrentHashMap<>();
    private final Map<String, Cached<BigDecimal>> dbFallback = new ConcurrentHashMap<>();
    private final AtomicLong lastRedisWarnMs = new AtomicLong();

    @Autowired
    public SpendCapService(JdbcTemplate jdbc, ObjectProvider<StringRedisTemplate> redis,
                           LlmUsageRollupService rollupService, ObjectProvider<SpendCapAlertSink> alertSinks) {
        this(jdbc, redis, rollupService, alertSinks, Clock.systemUTC(), boundedAlertExecutor());
    }

    SpendCapService(JdbcTemplate jdbc, ObjectProvider<StringRedisTemplate> redis, LlmUsageRollupService rollupService,
                    ObjectProvider<SpendCapAlertSink> alertSinks, Clock clock, Executor alertExecutor) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.rollupService = rollupService;
        this.alertSinks = alertSinks;
        this.clock = clock;
        this.alertExecutor = alertExecutor;
    }

    // ------------------------------------------------------------------ enforcement

    /**
     * Pre-call gate for a hosted-pool LLM call.
     *
     * @throws SpendCapReachedException when a cap is set and month-to-date hosted spend is at or above it
     */
    public void assertWithinCap(String tenantSchema) {
        if (tenantSchema == null || tenantSchema.isBlank()) {
            return;
        }
        TenantCap cap = configForSchema(tenantSchema).orElse(null);
        if (cap == null || cap.capUsd() == null) {
            return;
        }
        BigDecimal mtd = monthToDate(cap);
        if (mtd == null) {
            log.error("Spend cap for tenant {} could not be evaluated (Redis and database both failed) - "
                + "ALLOWING this hosted call (fail open)", cap.tenantId());
            return;
        }
        if (mtd.compareTo(cap.capUsd()) >= 0) {
            evaluateAlertsAsync(cap, mtd);
            throw new SpendCapReachedException(cap.capUsd(), scale(mtd), resetsAt());
        }
    }

    /** Non-throwing variant for optional hosted helpers (e.g. RAG query rewrite) that should just skip. */
    public boolean isBlocked(String tenantSchema) {
        try {
            assertWithinCap(tenantSchema);
            return false;
        } catch (SpendCapReachedException e) {
            return true;
        }
    }

    /** Counter feed from {@code LlmCallLogService} for every recorded PLATFORM_HOSTED cost. */
    @Override
    public void onHostedSpend(String tenantSchema, BigDecimal costUsd) {
        if (costUsd == null || costUsd.signum() <= 0) {
            return;
        }
        TenantCap cap = configForSchema(tenantSchema).orElse(null);
        if (cap == null || cap.capUsd() == null) {
            return; // nothing to enforce; a cap set later seeds from the database
        }
        String key = key(cap.tenantId(), currentMonth());
        StringRedisTemplate r = redis.getIfAvailable();
        BigDecimal after = null;
        if (r != null) {
            try {
                String value = r.execute(INCR_IF_EXISTS, List.of(key), costUsd.toPlainString());
                after = value == null ? null : new BigDecimal(value);
            } catch (RuntimeException e) {
                warnRedis("increment", e);
            }
        }
        if (after == null) {
            // Key absent or Redis down: the fallback figure is stale by at most this call.
            dbFallback.remove(fallbackKey(cap.tenantId()));
            return;
        }
        evaluateAlertsAsync(cap, after);
    }

    // ------------------------------------------------------------------ reads / admin

    /** Current state for the platform and tenant endpoints. Fresh config read (no cache). */
    public Optional<CapStatus> status(UUID tenantId) {
        Optional<TenantCap> cap = loadById(tenantId);
        return cap.map(c -> statusOf(c, monthToDate(c)));
    }

    /** Tenant-facing variant keyed by the caller's schema. */
    public Optional<CapStatus> statusForSchema(String tenantSchema) {
        return loadBySchema(tenantSchema).map(c -> statusOf(c, monthToDate(c)));
    }

    /**
     * Sets (or clears, with null) the cap. Validates {@code 0 < cap <= 1,000,000}.
     * Resets the alert stage, then re-evaluates against the new cap - so lowering
     * a cap below current spend alerts (once) straight away.
     *
     * @throws IllegalArgumentException for an out-of-range cap
     */
    public Optional<CapStatus> updateCap(UUID tenantId, BigDecimal capUsd) {
        BigDecimal normalized = validateCap(capUsd);
        Optional<TenantCap> before = loadById(tenantId);
        if (before.isEmpty()) {
            return Optional.empty();
        }
        jdbc.update(SQL_SET_CAP, normalized, tenantId);
        configBySchema.remove(before.get().schema());
        dbFallback.remove(fallbackKey(tenantId));
        TenantCap after = loadById(tenantId).orElseThrow();
        BigDecimal mtd = monthToDate(after);
        if (after.capUsd() != null && mtd != null) {
            int claimed = claimStage(after, mtd);
            if (claimed > 0) {
                after = new TenantCap(after.tenantId(), after.schema(), after.name(), after.capUsd(), claimed,
                    monthStart());
            }
        }
        return Optional.of(statusOf(after, mtd));
    }

    /** Roster columns for every capped tenant (others are absent = no cap). */
    public Map<UUID, RosterCap> rosterCaps() {
        Map<UUID, RosterCap> out = new HashMap<>();
        for (TenantCap cap : jdbc.query(SQL_ALL_CAPPED, (rs, n) -> map(rs))) {
            BigDecimal mtd = monthToDate(cap);
            out.put(cap.tenantId(), new RosterCap(cap.capUsd(), mtd == null ? null : scale(mtd),
                mtd == null ? null : mtd.compareTo(cap.capUsd()) >= 0));
        }
        return out;
    }

    static BigDecimal validateCap(BigDecimal capUsd) {
        if (capUsd == null) {
            return null;
        }
        if (capUsd.signum() <= 0 || capUsd.compareTo(MAX_CAP_USD) > 0) {
            throw new IllegalArgumentException("capUsd must be greater than 0 and at most 1,000,000 (or null to remove the cap).");
        }
        return capUsd.setScale(2, RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------------ month-to-date

    BigDecimal monthToDate(TenantCap cap) {
        YearMonth month = currentMonth();
        StringRedisTemplate r = redis.getIfAvailable();
        if (r != null) {
            try {
                String key = key(cap.tenantId(), month);
                String value = r.opsForValue().get(key);
                if (value != null) {
                    return new BigDecimal(value);
                }
                BigDecimal seeded = computeFromDb(cap.schema());
                if (seeded == null) {
                    return null;
                }
                Boolean set = r.opsForValue().setIfAbsent(key, seeded.toPlainString(), KEY_TTL);
                if (Boolean.FALSE.equals(set)) {
                    String raced = r.opsForValue().get(key);
                    if (raced != null) {
                        return new BigDecimal(raced);
                    }
                }
                return seeded;
            } catch (RuntimeException e) {
                warnRedis("read", e);
            }
        }
        String fk = fallbackKey(cap.tenantId());
        Cached<BigDecimal> cached = dbFallback.get(fk);
        if (cached != null && fresh(cached, DB_FALLBACK_TTL)) {
            return cached.value();
        }
        BigDecimal computed = computeFromDb(cap.schema());
        if (computed != null) {
            dbFallback.put(fk, new Cached<>(computed, clock.instant()));
        }
        return computed;
    }

    /** Hosted-pool cost from the 1st of this month (UTC) through now; null when the query fails. */
    BigDecimal computeFromDb(String schema) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        try {
            List<SpendReport.ProviderUsage> rows = asTenant(schema,
                () -> rollupService.byProvider(today.withDayOfMonth(1), today));
            BigDecimal total = BigDecimal.ZERO;
            for (SpendReport.ProviderUsage p : rows) {
                if (HOSTED.equals(p.providerName()) && p.metrics() != null && p.metrics().estimatedCostUsd() != null) {
                    total = total.add(p.metrics().estimatedCostUsd());
                }
            }
            return total;
        } catch (RuntimeException e) {
            log.error("Month-to-date hosted spend query failed for tenant schema '{}'", schema, e);
            return null;
        }
    }

    // ------------------------------------------------------------------ alerts

    private void evaluateAlertsAsync(TenantCap cap, BigDecimal mtd) {
        int stage = stageFor(cap.capUsd(), mtd);
        if (stage == 0 || stage <= effectiveStage(cap)) {
            return;
        }
        try {
            alertExecutor.execute(() -> {
                try {
                    claimStage(cap, mtd);
                } catch (RuntimeException e) {
                    log.warn("Spend-cap alert evaluation failed for tenant {}", cap.tenantId(), e);
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("Spend-cap alert queue full - tenant {} alert deferred to the next call", cap.tenantId());
        }
    }

    /**
     * Atomically claims the alert stage {@code mtd} has reached and, when this
     * caller won the claim, notifies every sink. Returns the claimed stage, 0
     * when nothing new was claimed.
     */
    int claimStage(TenantCap cap, BigDecimal mtd) {
        int stage = stageFor(cap.capUsd(), mtd);
        if (stage == 0) {
            return 0;
        }
        LocalDate month = monthStart();
        int updated = jdbc.update(SQL_CLAIM_STAGE, stage, Date.valueOf(month), cap.tenantId(), Date.valueOf(month), stage);
        configBySchema.remove(cap.schema());
        if (updated == 0) {
            return 0;
        }
        log.info("Tenant {} crossed {}% of its monthly hosted spend cap (${} of ${})", cap.tenantId(), stage,
            scale(mtd), cap.capUsd());
        SpendCapAlertSink.Alert alert = new SpendCapAlertSink.Alert(cap.tenantId(), cap.schema(), cap.name(), stage,
            cap.capUsd(), scale(mtd), month);
        alertSinks.orderedStream().forEach(sink -> {
            try {
                sink.onThresholdCrossed(alert);
            } catch (RuntimeException e) {
                log.warn("Spend-cap alert sink {} failed for tenant {}", sink.getClass().getSimpleName(),
                    cap.tenantId(), e);
            }
        });
        return stage;
    }

    static int stageFor(BigDecimal capUsd, BigDecimal mtd) {
        if (capUsd == null || mtd == null || capUsd.signum() <= 0) {
            return 0;
        }
        if (mtd.compareTo(capUsd) >= 0) {
            return 100;
        }
        return mtd.compareTo(capUsd.multiply(EIGHTY_PERCENT)) >= 0 ? 80 : 0;
    }

    private int effectiveStage(TenantCap cap) {
        return monthStart().equals(cap.alertMonth()) ? cap.alertStage() : 0;
    }

    // ------------------------------------------------------------------ helpers

    private CapStatus statusOf(TenantCap cap, BigDecimal mtd) {
        BigDecimal shown = mtd == null ? null : scale(mtd);
        BigDecimal percent = cap.capUsd() == null || mtd == null ? null
            : mtd.multiply(BigDecimal.valueOf(100)).divide(cap.capUsd(), 1, RoundingMode.HALF_UP);
        boolean blocked = cap.capUsd() != null && mtd != null && mtd.compareTo(cap.capUsd()) >= 0;
        return new CapStatus(cap.tenantId(), cap.capUsd(), shown, percent, effectiveStage(cap), blocked, monthStart());
    }

    Optional<TenantCap> configForSchema(String schema) {
        Cached<Optional<TenantCap>> cached = configBySchema.get(schema);
        if (cached != null && fresh(cached, CONFIG_TTL)) {
            return cached.value();
        }
        Optional<TenantCap> loaded;
        try {
            loaded = loadBySchema(schema);
        } catch (RuntimeException e) {
            log.error("Spend-cap config lookup failed for tenant schema '{}' - treating as uncapped (fail open)", schema, e);
            return Optional.empty();
        }
        if (configBySchema.size() > 50_000) {
            configBySchema.clear();
        }
        configBySchema.put(schema, new Cached<>(loaded, clock.instant()));
        return loaded;
    }

    private Optional<TenantCap> loadBySchema(String schema) {
        return jdbc.query(SQL_BY_SCHEMA, (rs, n) -> map(rs), schema).stream().findFirst();
    }

    private Optional<TenantCap> loadById(UUID tenantId) {
        return jdbc.query(SQL_BY_ID, (rs, n) -> map(rs), tenantId).stream().findFirst();
    }

    private static TenantCap map(java.sql.ResultSet rs) throws java.sql.SQLException {
        Date month = rs.getDate("spend_cap_alert_month");
        return new TenantCap(rs.getObject("id", UUID.class), rs.getString("schema_name"), rs.getString("name"),
            rs.getBigDecimal("spend_cap_usd"), rs.getInt("spend_cap_alert_stage"),
            month == null ? null : month.toLocalDate());
    }

    private boolean fresh(Cached<?> cached, Duration ttl) {
        return cached.loadedAt().plus(ttl).isAfter(clock.instant());
    }

    private YearMonth currentMonth() {
        return YearMonth.now(clock.withZone(ZoneOffset.UTC));
    }

    LocalDate monthStart() {
        return currentMonth().atDay(1);
    }

    Instant resetsAt() {
        return currentMonth().plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    static String key(UUID tenantId, YearMonth month) {
        return KEY_PREFIX + tenantId + ":" + month;
    }

    private String fallbackKey(UUID tenantId) {
        return tenantId + ":" + currentMonth();
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    private void warnRedis(String op, RuntimeException e) {
        long now = clock.millis();
        long last = lastRedisWarnMs.get();
        if (now - last >= WARN_INTERVAL_MS && lastRedisWarnMs.compareAndSet(last, now)) {
            log.warn("Spend-cap Redis {} failed ({}) - using the database figure (cached {}s)", op,
                e.getClass().getSimpleName(), DB_FALLBACK_TTL.toSeconds());
        }
    }

    private static <T> T asTenant(String schema, Supplier<T> work) {
        Optional<TenantContext> previous = TenantContext.current();
        try {
            TenantContext.set(TenantContext.of(schema, SYSTEM_ACTOR, Set.of()));
            return work.get();
        } finally {
            if (previous.isPresent()) {
                TenantContext.set(previous.get());
            } else {
                TenantContext.clear();
            }
        }
    }

    private static Executor boundedAlertExecutor() {
        return TenantAwareExecutors.newThreadPool(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(200),
            TenantAwareExecutors.threadFactory("spend-cap-alerts", true),
            new ThreadPoolExecutor.AbortPolicy());
    }

    /** Test seam: visible snapshot of the config cache size. */
    int cachedConfigCount() {
        return new ArrayList<>(configBySchema.keySet()).size();
    }
}
