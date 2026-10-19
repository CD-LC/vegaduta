package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.billing.email.BillingEmailService;
import com.agenticai.core.security.AccountType;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantPlan;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the reminder contract: the right mail at the right distance, exactly once per grant, only recorded when it
 * was really sent, and never a "subscribe" nudge to someone who returns to a paid plan. The repository is a small
 * in-memory fake with the REAL query semantics (window + stage), so "once only" is proven by running the job twice.
 */
class CompGrantReminderJobTest {

    private static final Instant NOW = Instant.parse("2026-12-01T00:00:00Z");

    private final List<Tenant> tenants = new ArrayList<>();
    private TenantRepository tenantRepository;
    private BillingEmailService emailService;
    private CompGrantReminderJob job;

    @BeforeEach
    void setUp() {
        tenantRepository = mock(TenantRepository.class);
        emailService = mock(BillingEmailService.class);
        when(tenantRepository.findCompGrantsDueForReminder(any(Instant.class), any(Instant.class), anyInt()))
            .thenAnswer(inv -> {
                Instant now = inv.getArgument(0);
                Instant until = inv.getArgument(1);
                int stage = inv.getArgument(2);
                return tenants.stream()
                    .filter(t -> t.getCompPlanExpiresAt() != null
                        && t.getCompPlanExpiresAt().isAfter(now)
                        && !t.getCompPlanExpiresAt().isAfter(until)
                        && t.getCompPlanReminderStage() < stage)
                    .toList();
            });
        job = new CompGrantReminderJob(tenantRepository, emailService, mock(PgAdvisoryJobLock.class));
    }

    private Tenant tenantEndingIn(String name, Duration fromNow, TenantPlan planBeforeGrant) {
        Tenant tenant = new Tenant(name, planBeforeGrant, name, "agentic-ai", AccountType.B2C, ExperienceMode.ADVANCED);
        tenant.grantComplimentaryPlan(TenantPlan.UNLIMITED, NOW.minus(Duration.ofDays(60)), NOW.plus(fromNow), "signup-trial");
        tenants.add(tenant);
        return tenant;
    }

    private void emailSucceeds() {
        when(emailService.sendCompGrantReminderEmail(any(), anyString(), anyString(), anyInt())).thenReturn(true);
    }

    @Test
    void sendsTheFirstReminderInsideFourteenDaysAndRecordsIt() {
        emailSucceeds();
        Tenant t = tenantEndingIn("acme", Duration.ofDays(10), TenantPlan.FREE);

        job.runOnce(NOW);

        verify(emailService).sendCompGrantReminderEmail(any(), eq("acme"), eq("11 Dec 2026"), eq(10));
        assertEquals(1, t.getCompPlanReminderStage());
        verify(tenantRepository).save(t);
    }

    @Test
    void sendsTheFinalReminderInsideThreeDaysAndNotAlsoTheFirstOne() {
        emailSucceeds();
        Tenant t = tenantEndingIn("acme", Duration.ofDays(2), TenantPlan.FREE);

        job.runOnce(NOW);

        verify(emailService, times(1)).sendCompGrantReminderEmail(any(), eq("acme"), anyString(), eq(2));
        assertEquals(2, t.getCompPlanReminderStage());
    }

    @Test
    void isOncePerStageEvenWhenTheJobRunsAgain() {
        emailSucceeds();
        tenantEndingIn("acme", Duration.ofDays(10), TenantPlan.FREE);

        job.runOnce(NOW);
        job.runOnce(NOW.plus(Duration.ofHours(1)));

        verify(emailService, times(1)).sendCompGrantReminderEmail(any(), anyString(), anyString(), anyInt());
    }

    @Test
    void movesFromTheFirstToTheFinalReminderAsTheEndApproaches() {
        emailSucceeds();
        Tenant t = tenantEndingIn("acme", Duration.ofDays(10), TenantPlan.FREE);

        job.runOnce(NOW);
        job.runOnce(NOW.plus(Duration.ofDays(8)));

        verify(emailService, times(2)).sendCompGrantReminderEmail(any(), eq("acme"), anyString(), anyInt());
        assertEquals(2, t.getCompPlanReminderStage());
    }

    @Test
    void doesNotRecordAReminderThatWasNotActuallySent() {
        when(emailService.sendCompGrantReminderEmail(any(), anyString(), anyString(), anyInt())).thenReturn(false);
        Tenant t = tenantEndingIn("acme", Duration.ofDays(10), TenantPlan.FREE);

        job.runOnce(NOW);

        assertEquals(0, t.getCompPlanReminderStage(), "no mail server / no admin yet: retry on the next tick");
        verify(tenantRepository, never()).save(any(Tenant.class));
    }

    @Test
    void doesNotNagATenantThatReturnsToAPaidPlanButStopsMatchingIt() {
        Tenant payer = tenantEndingIn("payer", Duration.ofDays(2), TenantPlan.PREMIUM);

        job.runOnce(NOW);

        verify(emailService, never()).sendCompGrantReminderEmail(any(), anyString(), anyString(), anyInt());
        assertEquals(2, payer.getCompPlanReminderStage());
    }

    @Test
    void ignoresGrantsFartherThanFourteenDaysOrAlreadyEnded() {
        emailSucceeds();
        tenantEndingIn("far", Duration.ofDays(40), TenantPlan.FREE);
        tenantEndingIn("ended", Duration.ofHours(-1), TenantPlan.FREE);

        job.runOnce(NOW);

        verify(emailService, never()).sendCompGrantReminderEmail(any(), anyString(), anyString(), anyInt());
    }

    @Test
    void oneTenantsFailureDoesNotStopTheRest() {
        when(emailService.sendCompGrantReminderEmail(any(), eq("boom"), anyString(), anyInt()))
            .thenThrow(new IllegalStateException("smtp exploded"));
        when(emailService.sendCompGrantReminderEmail(any(), eq("fine"), anyString(), anyInt())).thenReturn(true);
        tenantEndingIn("boom", Duration.ofDays(10), TenantPlan.FREE);
        Tenant fine = tenantEndingIn("fine", Duration.ofDays(10), TenantPlan.FREE);

        job.runOnce(NOW);

        assertEquals(1, fine.getCompPlanReminderStage());
    }
}
