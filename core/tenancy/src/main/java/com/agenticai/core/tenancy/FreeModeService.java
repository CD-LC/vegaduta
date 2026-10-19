package com.agenticai.core.tenancy;

import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The platform's "everything is free" switch (2026-09-22, at the owner's
 * request as VegaDuta moves to an open-source posture): when the {@code
 * platform_setting} row {@value #KEY} is {@code "true"}, every tenant
 * resolves as {@link com.agenticai.core.security.TenantPlan#UNLIMITED} for
 * FEATURE gates (plan caps, BYOK, WhatsApp, white-label, edit locks,
 * guardrail tiers) while the stored {@code Tenant.plan} and all billing
 * machinery stay untouched - flipping the row back to {@code "false"}
 * restores normal plan enforcement with no data to repair.
 *
 * <p>Deliberately NOT disabled: {@code FreeTierUsageLimiterService}'s
 * platform-hosted-pool message/token ceilings. Those bound the platform's
 * own provider spend, which is a real cost even when nothing is charged
 * for - under free mode every tenant on the pool simply gets the UNLIMITED
 * multiplier of the FREE base, so the cap stays on.
 *
 * <p>Read through {@link PlatformSettingsService} (admin-tunable, no
 * redeploy) with a small time-boxed cache ({@code app.free-mode.cache-ms},
 * default 10s) because {@link PlanLimitsService#resolvePlan} sits on the
 * per-request path - identical caching shape to {@code
 * FreeTierUsageLimiterService#effectiveQuotaConfig}. Missing or unparsable
 * row = OFF (billing on), the same fail-safe default every
 * PlatformSettingsService caller takes.
 *
 * <p>Billing edition ({@code app.edition.billing-enabled}, default {@code true}):
 * when the operator turns the edition OFF (the personal profile does) there is
 * nothing to sell, so this reports enabled unconditionally and without reading
 * the setting row - every plan, limit and entitlement port then resolves
 * "unlimited" through the very same path free mode already exercises, and no
 * module needs a second implementation to wire. The edition is a property, not
 * an admin toggle: it cannot be flipped back from the UI.
 */
@Component
public class FreeModeService {

    private static final Logger log = LoggerFactory.getLogger(FreeModeService.class);

    /** The {@code control_plane.platform_setting} key (seeded by V237). */
    public static final String KEY = "platform.free-mode.enabled";

    private final PlatformSettingsService settings;
    private final long cacheMs;
    private final boolean billingEdition;
    private final AtomicReference<CachedFlag> cache = new AtomicReference<>();

    /** Billing edition on (today's behaviour). */
    public FreeModeService(PlatformSettingsService settings, long cacheMs) {
        this(settings, cacheMs, true);
    }

    @Autowired
    public FreeModeService(
        PlatformSettingsService settings,
        @Value("${app.free-mode.cache-ms:10000}") long cacheMs,
        @Value("${app.edition.billing-enabled:true}") boolean billingEdition
    ) {
        this.settings = settings;
        this.cacheMs = cacheMs;
        this.billingEdition = billingEdition;
    }

    /** False when {@code app.edition.billing-enabled=false}: no plans, prices or payments exist. */
    public boolean isBillingEdition() {
        return billingEdition;
    }

    /**
     * True while the {@value #KEY} setting reads {@code "true"}, or always when the billing edition is off.
     */
    public boolean isEnabled() {
        if (!billingEdition) {
            return true;
        }
        CachedFlag cached = cache.get();
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.readAtMs < cacheMs) {
            return cached.enabled;
        }
        boolean enabled = settings.getBoolean(KEY, false);
        cache.set(new CachedFlag(enabled, now));
        return enabled;
    }

    private record CachedFlag(boolean enabled, long readAtMs) {}
}
