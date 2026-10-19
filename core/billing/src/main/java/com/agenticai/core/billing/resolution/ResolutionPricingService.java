package com.agenticai.core.billing.resolution;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Reads the platform-wide resolution-pricing knobs out of {@code
 * control_plane.platform_setting} (seeded by controlplane/V281) via raw
 * JDBC. JdbcTemplate rather than {@code PlatformSettingsService},
 * deliberately: that service lives in {@code core/tenancy}, which already
 * depends on THIS module - importing it would close a dependency cycle.
 * {@code TenantBillingProfileRepository} reads {@code control_plane.tenant}
 * the same way for the same reason.
 *
 * <p>Same caching shape as {@code FreeModeService}: a small time-boxed
 * cache ({@code app.resolution-pricing.cache-ms}, default 30s) because
 * {@link #snapshot()} sits on the sweep's per-event path. Missing or
 * unparsable rows fall back to the documented defaults - a corrupt setting
 * must never hard-fail metering, identical posture to
 * {@code PlatformSettingsService}'s own javadoc.
 */
@Service
public class ResolutionPricingService {

    private static final Logger log = LoggerFactory.getLogger(ResolutionPricingService.class);

    public static final String KEY_UNIT_PRICE_MINOR = "platform.resolution.unit-price-minor";
    public static final String KEY_CURRENCY = "platform.resolution.currency";
    public static final String KEY_BILL_IDLE_CLOSE = "platform.resolution.bill-idle-close";
    public static final String KEY_IDLE_CLOSE_HOURS = "platform.resolution.idle-close-hours";

    /** Seeded defaults (V281): meter-only, USD, idle-close billable, 72h window. */
    public static final long DEFAULT_UNIT_PRICE_MINOR = 0L;
    public static final String DEFAULT_CURRENCY = "USD";
    public static final boolean DEFAULT_BILL_IDLE_CLOSE = true;
    public static final long DEFAULT_IDLE_CLOSE_HOURS = 72L;

    /** Point-in-time price/config read, snapshotted onto each recorded event. */
    public record PricingSnapshot(long unitPriceMinor, String currency, boolean billIdleClose, long idleCloseHours) {}

    private final JdbcTemplate jdbcTemplate;
    private final long cacheMs;
    private final AtomicReference<Cached> cache = new AtomicReference<>();

    // Two constructors -> Spring needs an explicit pick; without this the context fails with
    // "No default constructor found" (prod boot failure 2026-09-23 on image a461e5db).
    @Autowired
    public ResolutionPricingService(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, 30_000L);
    }

    /** Test seam: explicit cache window. */
    ResolutionPricingService(JdbcTemplate jdbcTemplate, long cacheMs) {
        this.jdbcTemplate = jdbcTemplate;
        this.cacheMs = cacheMs;
    }

    public PricingSnapshot snapshot() {
        Cached cached = cache.get();
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.readAtMs < cacheMs) {
            return cached.snapshot;
        }
        PricingSnapshot snapshot = new PricingSnapshot(
            getLong(KEY_UNIT_PRICE_MINOR, DEFAULT_UNIT_PRICE_MINOR),
            getString(KEY_CURRENCY, DEFAULT_CURRENCY),
            getBoolean(KEY_BILL_IDLE_CLOSE, DEFAULT_BILL_IDLE_CLOSE),
            getLong(KEY_IDLE_CLOSE_HOURS, DEFAULT_IDLE_CLOSE_HOURS));
        cache.set(new Cached(snapshot, now));
        return snapshot;
    }

    private String readSetting(String key) {
        List<String> rows = jdbcTemplate.query(
            "SELECT value FROM control_plane.platform_setting WHERE key = ?",
            (rs, rowNum) -> rs.getString(1), key);
        return rows.stream().findFirst().orElse(null);
    }

    private String getString(String key, String defaultIfMissing) {
        String value = readSetting(key);
        return value == null ? defaultIfMissing : value;
    }

    private long getLong(String key, long defaultIfMissing) {
        String value = readSetting(key);
        if (value == null) {
            return defaultIfMissing;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            log.warn("Platform setting '{}' has a non-numeric value '{}' - using default {}", key, value, defaultIfMissing);
            return defaultIfMissing;
        }
    }

    private boolean getBoolean(String key, boolean defaultIfMissing) {
        String value = readSetting(key);
        return value == null ? defaultIfMissing : Boolean.parseBoolean(value.trim());
    }

    private record Cached(PricingSnapshot snapshot, long readAtMs) {}
}
