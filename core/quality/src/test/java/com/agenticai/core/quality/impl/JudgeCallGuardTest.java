package com.agenticai.core.quality.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.quality.impl.JudgeCallGuard.JudgeCallFailedException;
import com.agenticai.core.quality.impl.JudgeCallGuard.JudgeRunAbortedException;
import com.agenticai.core.quality.impl.JudgeCallGuard.Tracker;
import com.agenticai.core.security.TenantContext;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

/**
 * The guard is the difference between "the judge judged badly" and "the judge
 * hung / babbled for two hours while the run burned budget". Pinned: the
 * wall-clock cap, the failure accounting that aborts a hopeless run, and the
 * plain-language reasons an operator reads.
 */
class JudgeCallGuardTest {

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    // ---------------------------------------------------------------- orDefault

    @Test
    void orDefaultKeepsUsableTimeoutsAndReplacesDegenerateOnes() {
        assertEquals(JudgeCallGuard.DEFAULT_CALL_TIMEOUT, JudgeCallGuard.orDefault(null));
        assertEquals(JudgeCallGuard.DEFAULT_CALL_TIMEOUT, JudgeCallGuard.orDefault(Duration.ZERO));
        assertEquals(JudgeCallGuard.DEFAULT_CALL_TIMEOUT, JudgeCallGuard.orDefault(Duration.ofSeconds(-5)));
        assertEquals(Duration.ofMillis(250), JudgeCallGuard.orDefault(Duration.ofMillis(250)));
    }

    // ---------------------------------------------------------------- describe

    @Test
    void describeGivesAPlainReason() {
        assertEquals("the judge did not answer", JudgeCallGuard.describe(null));

        JudgeCallFailedException guarded = new JudgeCallFailedException("timed out after 20 s", null);
        assertEquals("timed out after 20 s", JudgeCallGuard.describe(guarded));

        RuntimeException http = new RuntimeException("wrapper",
            new HttpClientErrorException(HttpStatus.NOT_FOUND, "Not Found"));
        assertEquals("HTTP 404 Not Found", JudgeCallGuard.describe(http),
            "the provider's status is the useful part, not the wrapper's message");

        assertEquals("rate limited", JudgeCallGuard.describe(new IllegalStateException("  rate limited  ")));
        assertEquals("IllegalStateException",
            JudgeCallGuard.describe(new IllegalStateException("   ")),
            "a blank message falls back to the exception's name");
    }

    // ---------------------------------------------------------------- sample

    @Test
    void sampleQuotesACompletionAndCapsIt() {
        assertEquals("empty completion", JudgeCallGuard.sample(null));
        assertEquals("empty completion", JudgeCallGuard.sample("   "));
        assertEquals("unparseable output 'hello world'", JudgeCallGuard.sample("  hello\n world  "));

        String longOutput = "x".repeat(200);
        String sampled = JudgeCallGuard.sample(longOutput);
        assertTrue(sampled.startsWith("unparseable output '"));
        assertTrue(sampled.endsWith("...'"));
        assertTrue(sampled.length() < 110, "the sample is capped, not the whole completion");
    }

    // ---------------------------------------------------------------- callWithTimeout

    @Test
    void aSuccessfulCallReturnsItsVerdict() {
        assertEquals("PASS", JudgeCallGuard.callWithTimeout(() -> "PASS", Duration.ofSeconds(10)));
    }

    @Test
    void aRuntimeFailureIsRethrownAsItCame() {
        IllegalStateException boom = new IllegalStateException("provider exploded");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> JudgeCallGuard.callWithTimeout(() -> {
                throw boom;
            }, Duration.ofSeconds(10)));

        assertSame(boom, thrown, "callers classify failures themselves; the guard must not disguise them");
    }

    @Test
    void aCheckedFailureIsWrappedWithItsReason() {
        JudgeCallFailedException thrown = assertThrows(JudgeCallFailedException.class,
            () -> JudgeCallGuard.callWithTimeout(() -> {
                throw new Exception("checked boom");
            }, Duration.ofSeconds(10)));

        assertEquals("checked boom", thrown.getMessage());
    }

    @Test
    void aHungCallIsCancelledAtTheWallClockLimit() {
        JudgeCallFailedException thrown = assertThrows(JudgeCallFailedException.class,
            () -> JudgeCallGuard.callWithTimeout(() -> {
                Thread.sleep(30_000);
                return "never";
            }, Duration.ofMillis(150)));

        assertTrue(thrown.getMessage().startsWith("timed out after"));
    }

    @Test
    void theCallingThreadsTenantContextTravelsToTheJudgeCall() {
        TenantContext.set(TenantContext.of("tenant-j", "tester", Set.of()));
        AtomicReference<String> seen = new AtomicReference<>();

        JudgeCallGuard.callWithTimeout(
            () -> {
                seen.set(TenantContext.current().map(TenantContext::getTenantId).orElse("NONE"));
                return "ok";
            },
            Duration.ofSeconds(10));

        assertEquals("tenant-j", seen.get(),
            "the worker thread must answer for the caller's tenant, never for nobody");
    }

    // ---------------------------------------------------------------- Tracker

    @Test
    void aHealthyRunNeverAborts() {
        Tracker tracker = new Tracker("judge-x", "judge calls", 10);

        tracker.checkBudget();
        tracker.success();
        tracker.failure("one bad verdict");
        tracker.success();

        assertEquals(3, tracker.attempts());
        assertEquals(1, tracker.failures());
        assertEquals(2, tracker.successes());
        assertEquals(10, tracker.totalCalls());
        assertEquals("one bad verdict", tracker.firstReason());
    }

    @Test
    void leadingFailuresAbortTheRunBeforeAnythingWasMeasured() {
        Tracker tracker = new Tracker("judge-x", "judge calls", 10);

        tracker.failure("unparseable");
        tracker.failure("unparseable");
        JudgeRunAbortedException thrown = assertThrows(JudgeRunAbortedException.class,
            () -> tracker.failure("unparseable"));

        assertTrue(thrown.getMessage().contains("stopped after 3 failures in a row"));
        assertTrue(thrown.getMessage().contains("judge-x"));
        assertTrue(thrown.getMessage().contains("unparseable"));
    }

    @Test
    void afterAVerdictTheRunToleratesMoreConsecutiveFailures() {
        Tracker tracker = new Tracker("judge-x", "judge calls", 20);

        tracker.success();
        for (int i = 0; i < 5; i++) {
            tracker.failure("flaky");
        }
        assertEquals(6, tracker.attempts());

        assertThrows(JudgeRunAbortedException.class, () -> tracker.failure("flaky"),
            "six in a row after a verdict is a dead provider, not noise");
    }

    @Test
    void theConsecutiveLimitIsNeverBelowTheLeadingLimit() {
        Tracker tracker = new Tracker("judge-x", "judge calls", 20,
            Duration.ofSeconds(60), 3, 1);

        tracker.success();
        tracker.failure("x");
        tracker.failure("x");
        assertThrows(JudgeRunAbortedException.class, () -> tracker.failure("x"),
            "consecutiveAbort clamps up to leadingAbort rather than loosening it");
    }

    @Test
    void aSpentBudgetStopsTheRunBetweenCalls() {
        Tracker tracker = new Tracker("judge-x", "judge calls", 10,
            Duration.ofNanos(1), 3, 6);

        tracker.success();
        JudgeRunAbortedException thrown = assertThrows(JudgeRunAbortedException.class,
            tracker::checkBudget);

        assertTrue(thrown.getMessage().contains("budget"));
    }

    @Test
    void checkBudgetDoesNotFireBeforeTheFirstAttempt() {
        Tracker tracker = new Tracker("judge-x", "judge calls", 10,
            Duration.ofNanos(1), 3, 6);

        tracker.checkBudget();
    }

    @Test
    void noUsableVerdictsReportsWhatWasTriedAndWhy() {
        Tracker tracker = new Tracker("judge-x", "judge calls", 10);

        tracker.failure("HTTP 502 Bad Gateway");
        tracker.failure("HTTP 502 Bad Gateway");

        JudgeRunAbortedException error = tracker.noUsableVerdicts();
        assertTrue(error.getMessage().contains("2 of 2 judge calls failed"));
        assertTrue(error.getMessage().contains("HTTP 502 Bad Gateway"));
        assertTrue(error.getMessage().contains("Nothing was recorded"));
    }

    @Test
    void aBlankFirstReasonAndNoModelStillReadAsASentence() {
        Tracker tracker = new Tracker(" ", "judge calls", 10);

        tracker.failure("  ");

        assertEquals("the judge did not answer", tracker.firstReason());
        JudgeRunAbortedException error = tracker.noUsableVerdicts();
        assertTrue(error.getMessage().startsWith("Judge model returned no usable verdicts"));
    }
}
