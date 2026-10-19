package com.agenticai.core.agents.quota;

import com.agenticai.core.agents.budget.PerUserBudgetService;
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Issue 8 fix - "no {@code @Scheduled}/cron job resets any quota enforcement
 * counter" (confirmed by repo-wide grep before this class existed). {@link
 * FreeTierUsageLimiterService} and {@link PerUserBudgetService} both use
 * fixed-window Redis {@code INCR}/{@code INCRBY} + conditional {@code EXPIRE}
 * counters that self-expire on their own TTL - by design (see both classes'
 * javadocs) they roll on a per-tenant/per-user window that starts from
 * whenever that subject's first call landed, not a shared UTC-calendar
 * boundary. <b>Switching those counters themselves to UTC-calendar-day-
 * bucketed keys (making "resets in Xh" become "resets at UTC midnight" for
 * every user) is a real, billing-adjacent user-facing behavior change that
 * the investigation for this issue explicitly flagged as needing the maintainer's
 * product sign-off before implementing - it is deliberately NOT done here.</b>
 *
 * <p>What this class delivers now, safely and without that pending product
 * decision, is the piece that actually was an unambiguous defect regardless
 * of which option is eventually chosen: an automatic, UTC-midnight-anchored,
 * idempotent, restart-safe, horizontally-scale-safe job, wired to a real
 * action that is pure hygiene - a proactive sweep of {@link
 * FreeTierUsageLimiterService#repairAllMissingTtls} / {@link
 * PerUserBudgetService#repairAllMissingTtls}, extending both services'
 * existing lazy self-heal (a counter that lost its TTL between {@code INCR}
 * and {@code EXPIRE} - process death, a Redis hiccup on just the second call
 * - would otherwise stay permanently stuck until something happens to read
 * that exact key again) to run eagerly once a day instead of only on next
 * read. Once the calendar-alignment decision is made, the chosen reset
 * action slots into {@link #runForDay} without changing anything about the
 * scheduling, idempotency, or multi-instance guard below.
 *
 * <p><b>Idempotency / multi-instance guard.</b> {@link
 * PlatformQuotaResetLog} has a DB unique constraint on {@code (job_name,
 * reset_date)} (V189) - {@link #runForDay} claims a day by inserting that
 * row; whichever instance's insert wins runs the reset action, every other
 * instance (or a duplicate tick on the same instance) hits {@link
 * DataIntegrityViolationException} and skips. This is a real DB-level guard,
 * not in-memory instance state, so it holds across horizontally-scaled
 * {@code core} replicas. The {@link PgAdvisoryJobLock} wrap on the scheduled
 * tick is therefore belt-and-braces, not correctness-critical - the unique
 * constraint stays the guard of record; the advisory lock just spares N-1
 * replicas a doomed claim-insert (and its constraint-violation noise) at
 * every midnight boundary. {@link #catchUpMissedReset} deliberately stays
 * unwrapped: it is an {@code ApplicationReadyEvent} listener, not a
 * scheduled tick, and the claim insert alone already de-duplicates
 * concurrent startups.
 *
 * <p><b>Missed jobs / restarts.</b> {@link #catchUpMissedReset} runs once on
 * {@link ApplicationReadyEvent} and compares the most recent claimed date to
 * today (UTC) - if the server was down across a UTC-midnight boundary, this
 * claims and runs today exactly once, via the same idempotent claim {@link
 * #resetDailyQuota}'s scheduled tick uses (so a tick firing moments later,
 * or another instance starting up concurrently, can't double-run it).
 */
@Component
public class DailyQuotaResetScheduler {

    private static final Logger log = LoggerFactory.getLogger(DailyQuotaResetScheduler.class);

    static final String JOB_NAME = "free_tier_daily_quota_reset";

    private static final String JOB_LOCK_NAME = "daily-quota-reset";

    private final PlatformQuotaResetLogRepository repository;
    private final FreeTierUsageLimiterService freeTierUsageLimiterService;
    private final PerUserBudgetService perUserBudgetService;
    private final PgAdvisoryJobLock jobLock;
    private final String instanceId;

    public DailyQuotaResetScheduler(
        PlatformQuotaResetLogRepository repository,
        FreeTierUsageLimiterService freeTierUsageLimiterService,
        PerUserBudgetService perUserBudgetService,
        PgAdvisoryJobLock jobLock
    ) {
        this.repository = repository;
        this.freeTierUsageLimiterService = freeTierUsageLimiterService;
        this.perUserBudgetService = perUserBudgetService;
        this.jobLock = jobLock;
        this.instanceId = resolveInstanceId();
    }

    private static String resolveInstanceId() {
        try {
            return InetAddress.getLocalHost().getHostName() + "-" + ProcessHandle.current().pid();
        } catch (UnknownHostException e) {
            return "instance-" + UUID.randomUUID();
        }
    }

    /**
     * Explicit UTC schedule (not a bare cron string, which would implicitly
     * use the host's local timezone) - fires exactly at UTC 00:00 regardless
     * of which region/timezone the {@code core} host itself runs in.
     */
    @Scheduled(cron = "0 0 0 * * *", zone = "UTC")
    public void resetDailyQuota() {
        // Belt-and-braces on top of the (job_name, reset_date) claim - see
        // class javadoc. Lock-not-acquired is only DEBUG-logged (by the lock
        // service itself); the winning replica's claim covers the day.
        jobLock.tryRunExclusive(JOB_LOCK_NAME, () -> runForDay(LocalDate.now(ZoneOffset.UTC), false));
    }

    /**
     * Startup catch-up: if the most recently claimed reset date is before
     * today (UTC) - including "no row at all" on a fresh database - the
     * server was down across at least one UTC-midnight boundary and missed
     * that tick. Claims and runs exactly today (never a loop over every
     * missed day; there is nothing to backfill per-day since the reset
     * action itself is a point-in-time sweep, not a per-day accumulation).
     */
    @EventListener(ApplicationReadyEvent.class)
    public void catchUpMissedReset() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Optional<PlatformQuotaResetLog> last = repository.findTopByJobNameOrderByResetDateDesc(JOB_NAME);
        if (last.isPresent() && !last.get().getResetDate().isBefore(today)) {
            return;
        }
        log.warn(
            "Quota-reset job '{}' has not run for UTC day {} yet ({}) - missed the scheduled boundary, catching up now",
            JOB_NAME, today, last.map(l -> "last recorded reset was " + l.getResetDate()).orElse("no prior reset recorded")
        );
        runForDay(today, true);
    }

    // Verify-pass fix: was @Transactional, which is INERT here - both callers
    // (resetDailyQuota/catchUpMissedReset) invoke this directly on `this`,
    // not through the Spring AOP proxy, so the annotation never applied
    // (Spring's well-known self-invocation limitation). Removed rather than
    // left in as misleading decoration; saveAndFlush already carries its own
    // transactional boundary for the claim insert, which is all that
    // genuinely needs one here.
    void runForDay(LocalDate day, boolean isCatchUp) {
        log.info("Quota-reset job '{}' starting for UTC day {}{}", JOB_NAME, day, isCatchUp ? " (missed-boundary catch-up)" : "");
        try {
            repository.saveAndFlush(new PlatformQuotaResetLog(JOB_NAME, day, Instant.now(), instanceId));
        } catch (DataIntegrityViolationException e) {
            log.info("Quota-reset job '{}' already claimed for UTC day {} by another instance/trigger - idempotency skip", JOB_NAME, day);
            return;
        }
        // Verify-pass fix (confirmed correctness gap): the claim row above is
        // already committed by the time we get here - if a repair call threw
        // uncaught, that exception would propagate out of this method with
        // the day already marked claimed, permanently blocking any retry for
        // this UTC day (the unique constraint would reject every future
        // attempt) even though the repair itself never completed. Each
        // service's repair is now independently caught/logged so one
        // service's transient failure (e.g. a Redis blip reading platform
        // config) can never silently swallow the other's repair or leave the
        // day's claim orphaned with zero diagnostic trail.
        int freeTierRepaired = 0;
        try {
            freeTierRepaired = freeTierUsageLimiterService.repairAllMissingTtls();
        } catch (RuntimeException e) {
            log.error("Quota-reset job '{}' free-tier TTL repair sweep failed for UTC day {} - counters self-heal lazily on next read regardless", JOB_NAME, day, e);
        }
        int perUserRepaired = 0;
        try {
            perUserRepaired = perUserBudgetService.repairAllMissingTtls();
        } catch (RuntimeException e) {
            log.error("Quota-reset job '{}' per-user-budget TTL repair sweep failed for UTC day {} - counters self-heal lazily on next read regardless", JOB_NAME, day, e);
        }
        log.info(
            "Quota-reset job '{}' finished for UTC day {} on instance '{}' - repaired {} free-tier and {} per-user-budget "
                + "counter(s) that had lost their TTL",
            JOB_NAME, day, instanceId, freeTierRepaired, perUserRepaired
        );
    }
}
