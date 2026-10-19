package com.agenticai.core.agents.quota;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.budget.PerUserBudgetService;
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Issue 8 verify-pass fix: this class had zero test coverage despite being
 * concurrency-/multi-instance-safety-critical (idempotency-claim, catch-up,
 * and — after the verify-pass fix — resilience to a repair sweep throwing).
 * Plain Mockito unit tests, no Spring context and no database, per this
 * repo's convention against booting a live Postgres for a core/agents test.
 */
class DailyQuotaResetSchedulerTest {

    private PlatformQuotaResetLogRepository repository;
    private FreeTierUsageLimiterService freeTierUsageLimiterService;
    private PerUserBudgetService perUserBudgetService;
    private PgAdvisoryJobLock jobLock;
    private DailyQuotaResetScheduler scheduler;

    @BeforeEach
    void setUp() {
        repository = mock(PlatformQuotaResetLogRepository.class);
        freeTierUsageLimiterService = mock(FreeTierUsageLimiterService.class);
        perUserBudgetService = mock(PerUserBudgetService.class);
        jobLock = mock(PgAdvisoryJobLock.class);
        scheduler = new DailyQuotaResetScheduler(
            repository, freeTierUsageLimiterService, perUserBudgetService, jobLock);
    }

    @Test
    void runForDay_claimsTheDayAndRunsBothRepairSweeps() {
        LocalDate day = LocalDate.of(2026, 8, 7);
        when(repository.saveAndFlush(any())).thenReturn(new PlatformQuotaResetLog("x", day, Instant.now(), "test"));
        when(freeTierUsageLimiterService.repairAllMissingTtls()).thenReturn(3);
        when(perUserBudgetService.repairAllMissingTtls()).thenReturn(5);

        scheduler.runForDay(day, false);

        verify(repository, times(1)).saveAndFlush(any());
        verify(freeTierUsageLimiterService, times(1)).repairAllMissingTtls();
        verify(perUserBudgetService, times(1)).repairAllMissingTtls();
    }

    @Test
    void runForDay_idempotencySkip_whenClaimAlreadyExists_neverRunsRepairSweeps() {
        LocalDate day = LocalDate.of(2026, 8, 7);
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));

        scheduler.runForDay(day, false);

        verify(freeTierUsageLimiterService, never()).repairAllMissingTtls();
        verify(perUserBudgetService, never()).repairAllMissingTtls();
    }

    @Test
    void runForDay_freeTierRepairThrows_stillRunsPerUserRepairAndDoesNotPropagate() {
        // Verify-pass fix under test: a RuntimeException from one repair
        // sweep must not propagate out of runForDay (which would otherwise
        // leave the day's claim row committed with no way to retry the
        // sweep), and must not prevent the OTHER service's sweep from running.
        LocalDate day = LocalDate.of(2026, 8, 7);
        when(repository.saveAndFlush(any())).thenReturn(new PlatformQuotaResetLog("x", day, Instant.now(), "test"));
        when(freeTierUsageLimiterService.repairAllMissingTtls()).thenThrow(new RuntimeException("redis blip"));
        when(perUserBudgetService.repairAllMissingTtls()).thenReturn(2);

        scheduler.runForDay(day, false);

        verify(perUserBudgetService, times(1)).repairAllMissingTtls();
    }

    @Test
    void runForDay_perUserRepairThrows_doesNotPropagate() {
        LocalDate day = LocalDate.of(2026, 8, 7);
        when(repository.saveAndFlush(any())).thenReturn(new PlatformQuotaResetLog("x", day, Instant.now(), "test"));
        when(freeTierUsageLimiterService.repairAllMissingTtls()).thenReturn(1);
        when(perUserBudgetService.repairAllMissingTtls()).thenThrow(new RuntimeException("config load failed"));

        // Must not throw.
        scheduler.runForDay(day, false);

        verify(freeTierUsageLimiterService, times(1)).repairAllMissingTtls();
    }

    @Test
    void catchUpMissedReset_noPriorRow_claimsTodayAsCatchUp() {
        when(repository.findTopByJobNameOrderByResetDateDesc(DailyQuotaResetScheduler.JOB_NAME))
            .thenReturn(Optional.empty());
        when(repository.saveAndFlush(any()))
            .thenReturn(new PlatformQuotaResetLog("x", LocalDate.now(ZoneOffset.UTC), Instant.now(), "test"));

        scheduler.catchUpMissedReset();

        verify(repository, times(1)).saveAndFlush(any());
    }

    @Test
    void catchUpMissedReset_lastResetBeforeToday_claimsAndRunsExactlyOnce() {
        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        when(repository.findTopByJobNameOrderByResetDateDesc(DailyQuotaResetScheduler.JOB_NAME))
            .thenReturn(Optional.of(new PlatformQuotaResetLog(DailyQuotaResetScheduler.JOB_NAME, yesterday, Instant.now(), "prior")));
        when(repository.saveAndFlush(any()))
            .thenReturn(new PlatformQuotaResetLog("x", LocalDate.now(ZoneOffset.UTC), Instant.now(), "test"));

        scheduler.catchUpMissedReset();

        verify(repository, times(1)).saveAndFlush(any());
    }

    @Test
    void catchUpMissedReset_lastResetIsToday_doesNotClaimAgain() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        when(repository.findTopByJobNameOrderByResetDateDesc(DailyQuotaResetScheduler.JOB_NAME))
            .thenReturn(Optional.of(new PlatformQuotaResetLog(DailyQuotaResetScheduler.JOB_NAME, today, Instant.now(), "already-ran")));

        scheduler.catchUpMissedReset();

        verify(repository, never()).saveAndFlush(any());
        verify(freeTierUsageLimiterService, never()).repairAllMissingTtls();
    }

    // --- Scheduler-lock sweep (Wave 2): advisory-lock gating on the tick ----

    @Test
    void resetDailyQuota_lockNotAcquired_skipsWithoutTouchingRepositoryOrSweeps() {
        // Representative skip-on-lock-lost test for the whole Wave 2 sweep:
        // when another replica holds the advisory lock, the tick must do
        // nothing at all - not even attempt the (job_name, reset_date) claim
        // insert, which is exactly the redundant work the belt-and-braces
        // lock exists to spare.
        when(jobLock.tryRunExclusive(eq("daily-quota-reset"), any(Runnable.class))).thenReturn(false);

        scheduler.resetDailyQuota();

        verify(jobLock, times(1)).tryRunExclusive(eq("daily-quota-reset"), any(Runnable.class));
        verify(repository, never()).saveAndFlush(any());
        verify(freeTierUsageLimiterService, never()).repairAllMissingTtls();
        verify(perUserBudgetService, never()).repairAllMissingTtls();
    }

    @Test
    void resetDailyQuota_lockAcquired_runsTheClaimAndSweepsThroughTheLock() {
        // Pass-through stub: the lock "wins" and runs the supplied job, the
        // same contract PgAdvisoryJobLock documents for the acquired path.
        when(jobLock.tryRunExclusive(eq("daily-quota-reset"), any(Runnable.class))).thenAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return true;
        });
        when(repository.saveAndFlush(any()))
            .thenReturn(new PlatformQuotaResetLog("x", LocalDate.now(ZoneOffset.UTC), Instant.now(), "test"));
        when(freeTierUsageLimiterService.repairAllMissingTtls()).thenReturn(0);
        when(perUserBudgetService.repairAllMissingTtls()).thenReturn(0);

        scheduler.resetDailyQuota();

        verify(repository, times(1)).saveAndFlush(any());
        verify(freeTierUsageLimiterService, times(1)).repairAllMissingTtls();
        verify(perUserBudgetService, times(1)).repairAllMissingTtls();
    }

    @Test
    void jobName_isStableAcrossRestarts() {
        // The unique constraint (job_name, reset_date) only guards against a
        // duplicate reset if the job name is a stable literal, not derived
        // per-instance/per-run - a quick regression guard against that
        // ever silently drifting.
        assertTrue(DailyQuotaResetScheduler.JOB_NAME.equals("free_tier_daily_quota_reset"));
    }
}
