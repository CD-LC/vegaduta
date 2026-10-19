package com.agenticai.core.security.startup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BackgroundStartupTaskTest {

    @Test
    void flagOffRunsInlineOnTheCallingThreadAndReturnsNoThread() {
        AtomicReference<Thread> ranOn = new AtomicReference<>();

        Thread started = BackgroundStartupTask.run(false, "inline", () -> ranOn.set(Thread.currentThread()));

        assertThat(started).isNull();
        // Completed before run() returned - the old synchronous behaviour.
        assertThat(ranOn.get()).isSameAs(Thread.currentThread());
    }

    @Test
    void flagOffPropagatesTheTaskFailureExactlyAsBefore() {
        assertThatThrownBy(() -> BackgroundStartupTask.run(false, "inline-fail", () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class).hasMessage("boom");
    }

    @Test
    void flagOnRunsOnADaemonThreadAndReturnsWithoutWaiting() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Thread> ranOn = new AtomicReference<>();

        Thread started = BackgroundStartupTask.run(true, "bg", () -> {
            ranOn.set(Thread.currentThread());
            try {
                // Blocks until the test lets go: proves run() itself did not wait for the task.
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            finished.countDown();
        });

        assertThat(started).isNotNull();
        assertThat(finished.getCount()).isEqualTo(1);
        release.countDown();
        assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
        started.join(5_000);
        assertThat(ranOn.get()).isNotSameAs(Thread.currentThread());
        assertThat(ranOn.get().isDaemon()).isTrue();
        assertThat(ranOn.get().getName()).isEqualTo("startup-bg");
    }

    @Test
    void flagOnContainsAFailureSoStartupCanNeverBeFailedByAWarmup() throws Exception {
        Thread started = BackgroundStartupTask.run(true, "bg-fail", () -> {
            throw new IllegalStateException("warm-up blew up");
        });

        assertThat(started).isNotNull();
        started.join(5_000);
        assertThat(started.isAlive()).isFalse();
        // Reaching here without an exception on this thread is the assertion: the failure stayed on the background thread.
    }

    @Test
    void flagOnContainsAnErrorSuchAsAMissingOptionalClass() throws Exception {
        Thread started = BackgroundStartupTask.run(true, "bg-error", () -> {
            throw new NoClassDefFoundError("optional/Dependency");
        });

        started.join(5_000);
        assertThat(started.isAlive()).isFalse();
    }
}
