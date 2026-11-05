package com.agenticai.core.agents.spendcap;

import com.agenticai.core.agents.spendcap.SpendCapService.Cached;
import com.agenticai.core.agents.spendcap.SpendCapService.CapStatus;
import com.agenticai.core.agents.spendcap.SpendCapService.TenantCap;
import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Config lookup, row mapping, status projection and the Redis-warning/executor plumbing of {@link
 * SpendCapService}, extracted so the service stays under the file-size cap. Package-private;
 * callers still go through {@link SpendCapService}.
 */
final class SpendCapSupport {

    private static final Logger log = LoggerFactory.getLogger(SpendCapSupport.class);

    private final SpendCapService owner;

    SpendCapSupport(SpendCapService owner) {
        this.owner = owner;
    }

    CapStatus statusOf(TenantCap cap, BigDecimal mtd) {
        BigDecimal shown = mtd == null ? null : scale(mtd);
        BigDecimal percent =
                cap.capUsd() == null || mtd == null
                        ? null
                        : mtd.multiply(BigDecimal.valueOf(100))
                                .divide(cap.capUsd(), 1, RoundingMode.HALF_UP);
        boolean blocked = cap.capUsd() != null && mtd != null && mtd.compareTo(cap.capUsd()) >= 0;
        return new CapStatus(
                cap.tenantId(),
                cap.capUsd(),
                shown,
                percent,
                owner.effectiveStage(cap),
                blocked,
                owner.monthStart());
    }

    Optional<TenantCap> configForSchema(String schema) {
        return configForSchema(schema, false);
    }

    /**
     * @param gate true on the pre-call gate: fail-mode closed then refuses instead of treating the
     *     tenant as uncapped.
     */
    Optional<TenantCap> configForSchema(String schema, boolean gate) {
        Cached<Optional<TenantCap>> cached = owner.configBySchema.get(schema);
        if (cached != null && owner.fresh(cached, SpendCapService.CONFIG_TTL)) {
            return cached.value();
        }
        Optional<TenantCap> loaded;
        try {
            loaded = loadBySchema(schema);
        } catch (RuntimeException e) {
            if (gate) {
                owner.failMode.onGateFailure("spend-cap-config", e, log);
            }
            log.error(
                    "Spend-cap config lookup failed for tenant schema '{}' - treating as uncapped (fail open)",
                    schema,
                    e);
            return Optional.empty();
        }
        if (owner.configBySchema.size() > 50_000) {
            owner.configBySchema.clear();
        }
        owner.configBySchema.put(schema, new Cached<>(loaded, owner.clock.instant()));
        return loaded;
    }

    Optional<TenantCap> loadBySchema(String schema) {
        return owner.jdbc.query(SpendCapService.SQL_BY_SCHEMA, (rs, n) -> map(rs), schema).stream()
                .findFirst();
    }

    Optional<TenantCap> loadById(UUID tenantId) {
        return owner.jdbc.query(SpendCapService.SQL_BY_ID, (rs, n) -> map(rs), tenantId).stream()
                .findFirst();
    }

    static TenantCap map(java.sql.ResultSet rs) throws java.sql.SQLException {
        Date month = rs.getDate("spend_cap_alert_month");
        return new TenantCap(
                rs.getObject("id", UUID.class),
                rs.getString("schema_name"),
                rs.getString("name"),
                rs.getBigDecimal("spend_cap_usd"),
                rs.getInt("spend_cap_alert_stage"),
                month == null ? null : month.toLocalDate());
    }

    String fallbackKey(UUID tenantId) {
        return tenantId + ":" + owner.currentMonth();
    }

    static BigDecimal scale(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    void warnRedis(String op, RuntimeException e) {
        long now = owner.clock.millis();
        long last = owner.lastRedisWarnMs.get();
        if (now - last >= SpendCapService.WARN_INTERVAL_MS
                && owner.lastRedisWarnMs.compareAndSet(last, now)) {
            log.warn(
                    "Spend-cap Redis {} failed ({}) - using the database figure (cached {}s)",
                    op,
                    e.getClass().getSimpleName(),
                    SpendCapService.DB_FALLBACK_TTL.toSeconds());
        }
    }

    static Executor boundedAlertExecutor() {
        return TenantAwareExecutors.newThreadPool(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(200),
                TenantAwareExecutors.threadFactory("spend-cap-alerts", true),
                new ThreadPoolExecutor.AbortPolicy());
    }

    /** Test seam: visible snapshot of the config cache size. */
    int cachedConfigCount() {
        return new ArrayList<>(owner.configBySchema.keySet()).size();
    }
}
