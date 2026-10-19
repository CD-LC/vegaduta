package com.agenticai.core.tenancy;

import com.agenticai.core.security.edition.ConditionalOnBillingEdition;
import com.agenticai.core.billing.email.BillingEmailService;
import com.agenticai.core.security.TenantPlan;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Emails the admin of a tenant whose complimentary plan (signup bonus, promo, trial) is about to lapse, so the
 * customer hears about it BEFORE the plan drops to FREE and has a clear nudge to subscribe. Two reminders per grant:
 * stage 1 once the end is within {@value #FIRST_REMINDER_DAYS} days, stage 2 once it is within
 * {@value #FINAL_REMINDER_DAYS} days. (The "your plan has ended" mail is sent by {@link CompGrantExpiryReverter}
 * at the moment it reverts.)
 *
 * <p><b>Exactly once, restart-safe.</b> Each reminder is recorded in {@code comp_plan_reminder_stage} (V472) only
 * after the mail was actually handed to the mail server, and the query excludes tenants already at that stage - so a
 * missed tick, a restart or two replicas never double-send, and an unsent reminder (no mail server, no admin yet) is
 * retried on the next tick. A fresh grant resets the stage ({@link Tenant#grantComplimentaryPlan}).
 *
 * <p><b>Payers are not nagged.</b> A tenant whose plan before the grant was a paid one returns to that paid plan, not
 * to FREE, so a "subscribe" mail would be wrong; its stage is advanced silently so it stops matching.
 *
 * <p>Ticks hourly under {@link PgAdvisoryJobLock} (one replica per tick), like {@link CompGrantExpiryReverter}. No
 * transaction is held across the SMTP sends: each tenant is saved on its own after its mail.
 */
@Component
@ConditionalOnBillingEdition
@Profile("security")
public class CompGrantReminderJob {

    private static final Logger log = LoggerFactory.getLogger(CompGrantReminderJob.class);

    static final int FIRST_REMINDER_DAYS = 14;
    static final int FINAL_REMINDER_DAYS = 3;
    private static final long TICK_MS = 3_600_000;
    private static final String JOB_LOCK_NAME = "comp-grant-reminders";
    private static final DateTimeFormatter EXPIRY_DATE_FORMAT =
        DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    private final TenantRepository tenantRepository;
    private final BillingEmailService billingEmailService;
    private final PgAdvisoryJobLock jobLock;

    public CompGrantReminderJob(
        TenantRepository tenantRepository, BillingEmailService billingEmailService, PgAdvisoryJobLock jobLock
    ) {
        this.tenantRepository = tenantRepository;
        this.billingEmailService = billingEmailService;
        this.jobLock = jobLock;
    }

    // Initial delay so a fresh boot (migrations, warm-up) is not also sending mail in its first seconds.
    @Scheduled(fixedRate = TICK_MS, initialDelay = 300_000)
    public void sendReminders() {
        jobLock.tryRunExclusive(JOB_LOCK_NAME, () -> runOnce(Instant.now()));
    }

    /** One pass at a fixed instant - package-private so tests drive it without a scheduler or a clock. */
    void runOnce(Instant now) {
        // Final reminder first: a tenant already inside 3 days is moved to stage 2 and so never also gets the
        // 14-day mail for the same grant.
        remind(now, now.plus(Duration.ofDays(FINAL_REMINDER_DAYS)), 2);
        remind(now, now.plus(Duration.ofDays(FIRST_REMINDER_DAYS)), 1);
    }

    private void remind(Instant now, Instant until, int stage) {
        List<Tenant> due = tenantRepository.findCompGrantsDueForReminder(now, until, stage);
        for (Tenant tenant : due) {
            try {
                if (!fallsBackToFree(tenant)) {
                    tenant.markCompReminderSent(stage);
                    tenantRepository.save(tenant);
                    continue;
                }
                Instant expires = tenant.getCompPlanExpiresAt();
                int daysLeft = (int) Math.max(1, Math.ceil(Duration.between(now, expires).toMinutes() / (24.0 * 60.0)));
                boolean sent = billingEmailService.sendCompGrantReminderEmail(
                    tenant.getId(), tenant.getName(), EXPIRY_DATE_FORMAT.format(expires), daysLeft);
                if (sent) {
                    tenant.markCompReminderSent(stage);
                    tenantRepository.save(tenant);
                }
            } catch (RuntimeException e) {
                // One tenant's failure must not stop the rest of the batch; it is retried next tick.
                log.warn("Comp-grant reminder (stage {}) failed for tenant {}: {}", stage, tenant.getId(), e.getMessage());
            }
        }
    }

    /** True when the lapse lands the tenant on FREE (the case worth a "subscribe" nudge). */
    private static boolean fallsBackToFree(Tenant tenant) {
        TenantPlan before = tenant.getPlanBeforeCompGrant();
        return before == null || before == TenantPlan.FREE;
    }
}
