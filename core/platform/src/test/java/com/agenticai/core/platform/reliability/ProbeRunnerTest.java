package com.agenticai.core.platform.reliability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.platform.reliability.ProbeRunner.Outcome;
import com.agenticai.core.platform.reliability.ProbeRunner.Spec;
import com.agenticai.core.platform.reliability.ReliabilityApi.DependencyEntry;
import java.net.ConnectException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The probe mechanics: success, failure, hard timeout, parallelism, and that no message text leaks. */
class ProbeRunnerTest {

    private ExecutorService executor;
    private ProbeRunner runner;

    @BeforeEach
    void setUp() {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        runner = new ProbeRunner(executor, Duration.ofMillis(300), Clock.systemUTC());
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void aSuccessfulBodyIsReportedWithItsStatusDetailAndLatency() {
        DependencyEntry e = runner.run(new Spec("redis", "cache", () -> new Outcome(DependencyStatus.UP, "PING answered")));
        assertThat(e.name()).isEqualTo("redis");
        assertThat(e.category()).isEqualTo("cache");
        assertThat(e.status()).isEqualTo(DependencyStatus.UP);
        assertThat(e.detail()).isEqualTo("PING answered");
        assertThat(e.latencyMs()).isNotNull().isGreaterThanOrEqualTo(0L);
        assertThat(e.lastCheckedAt()).isNotNull();
    }

    @Test
    void aBodyThatDoesNotAnswerInTimeIsDownWithTheTimeoutAsItsLatency() {
        DependencyEntry e = runner.run(new Spec("slow", "x", () -> {
            blockUntilInterrupted();
            return new Outcome(DependencyStatus.UP, "never");
        }));
        assertThat(e.status()).isEqualTo(DependencyStatus.DOWN);
        assertThat(e.latencyMs()).isEqualTo(300L);
        assertThat(e.detail()).contains("300 ms");
    }

    @Test
    void aThrowingBodyIsDownAndOnlyTheExceptionClassIsReported() {
        DependencyEntry e = runner.run(new Spec("db", "database", () -> {
            throw new IllegalStateException("jdbc:postgresql://secret-host:5432/prod password=hunter2");
        }));
        assertThat(e.status()).isEqualTo(DependencyStatus.DOWN);
        assertThat(e.detail()).contains("IllegalStateException");
        assertThat(e.detail()).doesNotContain("secret-host").doesNotContain("hunter2");
    }

    @Test
    void commonNetworkFailuresReadInPlainWords() {
        assertThat(ProbeRunner.describe(new ConnectException("Connection refused: host:1234"))).isEqualTo("connection refused");
        assertThat(ProbeRunner.describe(new java.net.http.HttpTimeoutException("x"))).isEqualTo("timed out");
        assertThat(ProbeRunner.describe(new java.net.UnknownHostException("internal-host"))).isEqualTo("name not resolved");
        assertThat(ProbeRunner.describe(null)).isEqualTo("unknown error");
    }

    @Test
    void aNullOutcomeIsUnknownNotACrash() {
        DependencyEntry e = runner.run(new Spec("odd", "x", () -> null));
        assertThat(e.status()).isEqualTo(DependencyStatus.UNKNOWN);
        assertThat(e.latencyMs()).isNull();
    }

    @Test
    void runAllKeepsTheOrderGivenAndRunsProbesInParallel() {
        // A barrier that only opens once all three bodies are running at the same moment proves parallelism without
        // timing: run one after another, the first body would wait for parties that never start and fail the probe.
        ProbeRunner patient = new ProbeRunner(executor, Duration.ofSeconds(10), Clock.systemUTC());
        CyclicBarrier allRunning = new CyclicBarrier(3);
        List<DependencyEntry> out = patient.runAll(List.of(
            new Spec("a", "x", () -> {
                allRunning.await(5, TimeUnit.SECONDS);
                return new Outcome(DependencyStatus.UP, "a");
            }),
            new Spec("b", "x", () -> {
                allRunning.await(5, TimeUnit.SECONDS);
                return new Outcome(DependencyStatus.DEGRADED, "b");
            }),
            new Spec("c", "x", () -> {
                allRunning.await(5, TimeUnit.SECONDS);
                return new Outcome(DependencyStatus.UNKNOWN, "c");
            })));

        assertThat(out).extracting(DependencyEntry::name).containsExactly("a", "b", "c");
        assertThat(out).extracting(DependencyEntry::status)
            .containsExactly(DependencyStatus.UP, DependencyStatus.DEGRADED, DependencyStatus.UNKNOWN);
    }

    @Test
    void oneHungProbeDoesNotStopTheOthersAnswering() {
        List<DependencyEntry> out = runner.runAll(List.of(
            new Spec("hung", "x", () -> {
                blockUntilInterrupted();
                return new Outcome(DependencyStatus.UP, "never");
            }),
            new Spec("fine", "x", () -> new Outcome(DependencyStatus.UP, "ok"))));
        assertThat(out.get(0).status()).isEqualTo(DependencyStatus.DOWN);
        assertThat(out.get(1).status()).isEqualTo(DependencyStatus.UP);
    }

    @Test
    void callPropagatesTimeoutAndFailureToTheCaller() throws Exception {
        assertThatThrownBy(() -> runner.call(() -> {
            blockUntilInterrupted();
            return "never";
        })).isInstanceOf(TimeoutException.class);
        assertThatThrownBy(() -> runner.call(() -> {
            throw new IllegalArgumentException("boom");
        })).isInstanceOf(IllegalArgumentException.class);
        assertThat(runner.call(() -> "fine")).isEqualTo("fine");
    }

    /** Parks the probe body until the runner cancels (interrupts) it; no wall-clock sleep is relied on. */
    private static void blockUntilInterrupted() throws InterruptedException {
        new CountDownLatch(1).await();
    }
}
