package com.agenticai.core.omnichannel.session;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.security.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Shared fixtures for the {@link InboundChannelExecutor} test classes. */
final class InboundExecutorTestSupport {

    private InboundExecutorTestSupport() {}

    static void asTenant(String tenant) {
        TenantContext.set(TenantContext.of(tenant, "sub", Set.of()));
    }

    /** Captures tasks so the test controls when they finish. */
    static final class Manual implements Executor {
        final List<Runnable> queued = new ArrayList<>();

        @Override
        public void execute(Runnable r) {
            queued.add(r);
        }

        void runAll() {
            List<Runnable> copy = new ArrayList<>(queued);
            queued.clear();
            copy.forEach(Runnable::run);
        }
    }

    /** Polls (no fixed sleep) until n callers are parked in the wait queue. */
    static void awaitWaiting(InboundChannelExecutor ex, int n) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (ex.waiting() != n) {
            assertTrue(
                    System.nanoTime() < deadline,
                    "timed out waiting for " + n + " parked callers, have " + ex.waiting());
            Thread.sleep(1);
        }
    }

    static Thread acquireAsync(
            InboundChannelExecutor ex,
            String tenant,
            boolean neverDrop,
            AtomicReference<InboundChannelExecutor.Permit> out,
            CountDownLatch done) {
        return Thread.ofVirtual()
                .start(
                        () -> {
                            asTenant(tenant);
                            out.set(neverDrop ? ex.acquireNeverDrop("t") : ex.acquire("t"));
                            done.countDown();
                        });
    }
}
