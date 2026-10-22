package com.agenticai.core.tenancy;

import com.agenticai.core.billing.email.BillingEmailService;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.security.logging.LogSafe;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One-off platform-admin action - "give every registered tenant UNLIMITED for N days" (2026-08-14
 * site-wide promo). Reuses the exact same comp-grant primitive {@code CompGrantService}/{@code
 * TrialGrantService}/{@code ReferralService} already use ({@link Tenant#grantComplimentaryPlan},
 * expired by {@link CompGrantExpiryReverter} on the usual hourly tick) rather than inventing a
 * parallel mechanism - the only thing genuinely new here is "apply it to every tenant in one call"
 * instead of one tenant at a time.
 *
 * <p>Deliberately a real service call, triggered once by hand via {@code PlatformController}, not a
 * data-mutating Flyway migration - a promo grant isn't schema, and a migration would replay on
 * every environment/replica startup with no clean way to scope it to "tenants active right now."
 *
 * <p>Idempotent-friendly like a single comp grant: skips any tenant already on {@link
 * TenantPlan#UNLIMITED} (nothing to grant), so re-running this after a partial failure or to
 * double-check coverage is safe - it will not stack grants or reset an already-current expiry.
 *
 * <p><b>Grant and email are two separate public methods, not one.</b> {@link
 * #grantUnlimitedToAllActiveTenants} is {@code @Transactional} (DB writes only); {@link
 * #sendPromoEmails} is a second, non-transactional call the caller (see {@code PlatformController})
 * makes afterward. They are NOT combined into a single method that calls one from the other
 * internally - that self-invocation would bypass Spring's transactional proxy entirely (see {@code
 * CompGrantExpiryReverter}'s javadoc for the same warning), and even if it didn't, holding a DB
 * transaction open across possibly-hundreds of synchronous SMTP sends would be its own problem.
 */
@Service
@Profile("security")
public class SiteWidePromoService {

    private static final Logger log = LoggerFactory.getLogger(SiteWidePromoService.class);

    private static final DateTimeFormatter EXPIRY_DATE_FORMAT =
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    private final TenantRepository tenantRepository;
    private final BillingEmailService billingEmailService;

    public SiteWidePromoService(
            TenantRepository tenantRepository, BillingEmailService billingEmailService) {
        this.tenantRepository = tenantRepository;
        this.billingEmailService = billingEmailService;
    }

    @Transactional
    public SiteWidePromoResult grantUnlimitedToAllActiveTenants(
            int durationDays, String reason, String triggeredBy) {
        List<Tenant> activeTenants = tenantRepository.findByStatus(TenantStatus.ACTIVE);

        Instant grantedAt = Instant.now();
        Instant expiresAt = grantedAt.plusSeconds((long) durationDays * 24 * 3600);

        List<GrantedTenant> granted = new ArrayList<>();
        List<UUID> skippedAlreadyUnlimited = new ArrayList<>();
        List<Tenant> toSave = new ArrayList<>();

        for (Tenant tenant : activeTenants) {
            if (tenant.getPlan() == TenantPlan.UNLIMITED) {
                skippedAlreadyUnlimited.add(tenant.getId());
                continue;
            }
            tenant.grantComplimentaryPlan(TenantPlan.UNLIMITED, grantedAt, expiresAt, reason);
            toSave.add(tenant);
            granted.add(new GrantedTenant(tenant.getId(), tenant.getName()));
        }
        tenantRepository.saveAll(toSave);

        log.info(
                "Site-wide UNLIMITED promo issued by {}: granted={} skipped(already UNLIMITED)={} expiresAt={} reason='{}'",
                LogSafe.clean(triggeredBy),
                granted.size(),
                skippedAlreadyUnlimited.size(),
                LogSafe.clean(expiresAt),
                LogSafe.clean(reason));

        return new SiteWidePromoResult(
                granted, skippedAlreadyUnlimited, grantedAt, expiresAt, reason);
    }

    /**
     * Best-effort, one tenant's mail-send failure must not abort the rest of the batch - {@link
     * BillingEmailService} already catches and logs send failures internally rather than throwing,
     * matching every other billing email call site's fire-and-forget posture. Call this AFTER
     * {@link #grantUnlimitedToAllActiveTenants} has returned (its transaction has committed) - see
     * class javadoc for why these stay two separate calls.
     */
    public void sendPromoEmails(List<GrantedTenant> grantedTenants, Instant expiresAt) {
        String expiresOn = EXPIRY_DATE_FORMAT.format(expiresAt);
        for (GrantedTenant tenant : grantedTenants) {
            billingEmailService.sendUnlimitedPromoEmail(tenant.id(), tenant.name(), expiresOn);
        }
    }

    public record GrantedTenant(UUID id, String name) {}
}
