package com.agenticai.core.omnichannel.session;

import static com.agenticai.core.omnichannel.session.InboundExecutorTestSupport.asTenant;
import static com.agenticai.core.omnichannel.session.InboundExecutorTestSupport.acquireAsync;
import static com.agenticai.core.omnichannel.session.InboundExecutorTestSupport.awaitWaiting;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.omnichannel.session.InboundExecutorTestSupport.Manual;
import com.agenticai.core.security.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** {@link InboundChannelExecutor} permits, the FIFO wait queue, overflow and per-tenant fairness. */
class InboundChannelExecutorAdmissionTest {

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void waitQueueIsFifoAndANewcomerDoesNotBargeAheadOfAParkedWaiter() throws Exception {
        InboundChannelExecutor ex =
                new InboundChannelExecutor(Runnable::run, 8, 2, 8, 10_000, 0, 8, 0, false, null);
        asTenant("a");
        InboundChannelExecutor.Permit h1 = ex.acquire("t");
        InboundChannelExecutor.Permit h2 = ex.acquire("t");
        AtomicReference<InboundChannelExecutor.Permit> w1 = new AtomicReference<>();
        AtomicReference<InboundChannelExecutor.Permit> w2 = new AtomicReference<>();
        CountDownLatch d1 = new CountDownLatch(1);
        CountDownLatch d2 = new CountDownLatch(1);
        acquireAsync(ex, "a", false, w1, d1);
        awaitWaiting(ex, 1);
        acquireAsync(ex, "a", false, w2, d2);
        awaitWaiting(ex, 2);

        h1.release();
        assertTrue(d1.await(5, TimeUnit.SECONDS), "the older waiter is admitted first");
        assertEquals(1, d2.getCount(), "the younger waiter keeps waiting");
        // A newcomer (zero wait) while a waiter is still parked must not take the slot freed below.
        h2.release();
        assertTrue(d2.await(5, TimeUnit.SECONDS));
        assertTrue(w1.get() != null && w2.get() != null);
        w1.get().release();
        w2.get().release();
        assertEquals(0, ex.inFlight());
    }

    @Test
    void oneTenantCannotFillTheWaitQueueSoAnotherTenantCanStillQueue() throws Exception {
        // cap 1 per tenant, global 4, queue 8 but only 2 per tenant.
        InboundChannelExecutor ex =
                new InboundChannelExecutor(Runnable::run, 4, 1, 8, 10_000, 0, 2, 0, false, null);
        asTenant("flood");
        InboundChannelExecutor.Permit held = ex.acquire("t");
        List<AtomicReference<InboundChannelExecutor.Permit>> outs = new ArrayList<>();
        List<CountDownLatch> dones = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            outs.add(new AtomicReference<>());
            dones.add(new CountDownLatch(1));
            acquireAsync(ex, "flood", false, outs.get(i), dones.get(i));
        }
        awaitWaiting(ex, 2);
        asTenant("flood");
        long t0 = System.nanoTime();
        assertEquals(
                null,
                ex.acquire("t"),
                "third flood waiter exceeds queue-per-tenant and is rejected at once");
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 2_000);

        asTenant("other");
        InboundChannelExecutor.Permit other = ex.acquire("t");
        assertTrue(
                other != null,
                "other tenant still admitted (own cap free) while the flood is parked");
        other.release();
        held.release();
        // Arrival order of the two virtual threads is not deterministic, so release whichever is
        // admitted.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        boolean[] released = new boolean[2];
        while (!(released[0] && released[1])) {
            assertTrue(System.nanoTime() < deadline, "flood waiters were not admitted in turn");
            for (int i = 0; i < 2; i++) {
                if (!released[i] && dones.get(i).getCount() == 0) {
                    outs.get(i).get().release();
                    released[i] = true;
                }
            }
            Thread.sleep(1);
        }
        assertEquals(0, ex.inFlight());
    }

    @Test
    void neverDropOverflowIsBoundedPerTenantAndThenRunsOnTheCallerThread() {
        // global 4 -> tenant cap 1; overflow-max 3 but 1 per tenant.
        InboundChannelExecutor ex =
                new InboundChannelExecutor(new Manual(), 4, 16, 8, 0, 3, 2, 1, false, null);
        asTenant("a");
        InboundChannelExecutor.Permit slot = ex.acquireNeverDrop("t");
        InboundChannelExecutor.Permit over = ex.acquireNeverDrop("t");
        assertEquals(2, ex.inFlight(), "one slot plus one overflow");
        InboundChannelExecutor.Permit inline = ex.acquireNeverDrop("t");
        assertTrue(inline != null, "never null");
        assertEquals(2, ex.inFlight(), "tenant overflow ceiling reached: no third counted slot");
        Thread caller = Thread.currentThread();
        AtomicReference<Thread> ranOn = new AtomicReference<>();
        assertTrue(inline.run(() -> ranOn.set(Thread.currentThread())));
        assertEquals(caller, ranOn.get(), "last resort is the request thread, not a drop");

        // Another tenant's overflow is not consumed by the flooding tenant.
        asTenant("b");
        InboundChannelExecutor.Permit b1 = ex.acquireNeverDrop("t");
        InboundChannelExecutor.Permit b2 = ex.acquireNeverDrop("t");
        assertEquals(4, ex.inFlight(), "b got its own slot and its own overflow slot");
        slot.release();
        over.release();
        b1.release();
        b2.release();
        assertEquals(0, ex.inFlight());
    }

    @Test
    void neverDropPermitRunsOnTheCallerThreadWhenTheDelegateRejects() {
        InboundChannelExecutor ex =
                new InboundChannelExecutor(
                        r -> {
                            throw new RejectedExecutionException("closed");
                        },
                        4,
                        4,
                        null);
        asTenant("a");
        InboundChannelExecutor.Permit p = ex.acquireNeverDrop("t");
        AtomicReference<Boolean> ran = new AtomicReference<>(false);
        assertTrue(p.run(() -> ran.set(true)));
        assertTrue(ran.get());
        assertEquals(0, ex.inFlight());
    }

    @Test
    void inlinePermitHoldsNoSlotAndRunsOnTheCallerThread() {
        InboundChannelExecutor ex = new InboundChannelExecutor(new Manual(), 1, 1, null);
        asTenant("a");
        InboundChannelExecutor.Permit held = ex.acquire("t");
        assertTrue(held != null);
        assertEquals(null, ex.acquire("t"), "at cap");
        InboundChannelExecutor.Permit cheap = ex.inline();
        AtomicReference<Boolean> ran = new AtomicReference<>(false);
        assertTrue(cheap.run(() -> ran.set(true)), "an inline permit is available even at the cap");
        assertTrue(ran.get());
        cheap.release();
        assertEquals(1, ex.inFlight(), "inline permit never touched the counters");
        held.release();
    }

    @Test
    void runInlineHoldsTheSlotOnTheCallerThreadAndFreesItAfterwards() {
        InboundChannelExecutor ex = new InboundChannelExecutor(new Manual(), 4, 4, null);
        asTenant("a");
        InboundChannelExecutor.Permit p = ex.acquire("t");
        AtomicReference<Integer> during = new AtomicReference<>();
        p.runInline(() -> during.set(ex.inFlight()));
        assertEquals(1, during.get());
        assertEquals(0, ex.inFlight());
        p.runInline(() -> during.set(-1));
        assertEquals(1, during.get(), "second runInline is a no-op");
    }

    @Test
    void interruptWhileParkedReturnsNullKeepsTheFlagAndLeaksNothing() throws Exception {
        InboundChannelExecutor ex =
                new InboundChannelExecutor(Runnable::run, 1, 1, 8, 10_000, 0, 8, 0, false, null);
        asTenant("a");
        InboundChannelExecutor.Permit held = ex.acquire("t");
        AtomicReference<InboundChannelExecutor.Permit> out = new AtomicReference<>(held);
        AtomicReference<Boolean> flag = new AtomicReference<>(false);
        CountDownLatch done = new CountDownLatch(1);
        Thread t =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    asTenant("a");
                                    out.set(ex.acquire("t"));
                                    flag.set(Thread.currentThread().isInterrupted());
                                    done.countDown();
                                });
        awaitWaiting(ex, 1);
        t.interrupt();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(null, out.get());
        assertTrue(flag.get(), "interrupt status preserved");
        assertEquals(0, ex.waiting());
        held.release();
        assertEquals(0, ex.inFlight());
    }

    @Test
    void acquireWaitsInTheBoundedQueueAndIsAdmittedWhenASlotFrees() throws Exception {
        InboundChannelExecutor ex =
                new InboundChannelExecutor(Runnable::run, 1, 1, 8, 5_000, 0, false, null);
        asTenant("a");
        InboundChannelExecutor.Permit first = ex.acquire("t");
        assertTrue(first != null);
        AtomicReference<InboundChannelExecutor.Permit> second = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread waiter =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    asTenant("a");
                                    second.set(ex.acquire("t"));
                                    done.countDown();
                                });
        awaitWaiting(ex, 1);
        assertEquals(1, done.getCount(), "second caller must be parked, not shed");

        first.release();

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertTrue(second.get() != null, "burst delayed, not dropped");
        waiter.join();
        second.get().release();
        assertEquals(0, ex.inFlight());
    }

    @Test
    void acquireTimesOutWhenNoSlotFreesAndRejectsImmediatelyWhenTheWaitQueueIsFull()
            throws Exception {
        InboundChannelExecutor ex =
                new InboundChannelExecutor(Runnable::run, 1, 1, 1, 300, 0, false, null);
        asTenant("a");
        InboundChannelExecutor.Permit held = ex.acquire("t");
        CountDownLatch firstWaiterQueued = new CountDownLatch(1);
        AtomicReference<InboundChannelExecutor.Permit> waiterResult = new AtomicReference<>(held);
        Thread waiter =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    asTenant("a");
                                    firstWaiterQueued.countDown();
                                    waiterResult.set(ex.acquire("t"));
                                });
        firstWaiterQueued.await();
        awaitWaiting(ex, 1);

        long t0 = System.nanoTime();
        assertEquals(null, ex.acquire("t"), "queue (capacity 1) already holds a waiter");
        assertTrue(
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 250,
                "queue-full rejection must not wait");

        waiter.join();
        assertEquals(null, waiterResult.get(), "the queued waiter times out after queue-wait-ms");
        held.release();
        assertEquals(0, ex.inFlight());
        assertEquals(0, ex.trackedTenants());
    }

    @Test
    void overflowAdmitsPastTheCapUpToItsOwnCeilingThenRejects() {
        InboundChannelExecutor ex =
                new InboundChannelExecutor(Runnable::run, 1, 1, 8, 0, 2, false, null);
        asTenant("a");
        InboundChannelExecutor.Permit held = ex.acquire("t");
        assertTrue(held != null);
        assertEquals(null, ex.acquire("t", 0, false), "no overflow unless the channel asks for it");
        InboundChannelExecutor.Permit o1 = ex.acquire("qr", 0, true);
        assertEquals(
                null,
                ex.acquire("qr", 0, true),
                "same tenant: per-tenant overflow ceiling (min(16, tenant cap 1)) reached");
        asTenant("b");
        InboundChannelExecutor.Permit o2 = ex.acquire("qr", 0, true);
        assertTrue(o1 != null && o2 != null);
        assertEquals(null, ex.acquire("qr", 0, true), "global overflow ceiling reached");
        asTenant("a");

        o1.release();
        o2.release();
        held.release();
        assertEquals(0, ex.inFlight());
        assertTrue(ex.acquire("qr", 0, true) != null);
    }

    @Test
    void busyTenantsCannotFillTheReplicaSoAnIdleTenantStillGetsASlot() {
        // global 8, per-tenant 16 -> a tenant is capped at 8/4 = 2 and the last 2 slots are held
        // for idle tenants.
        InboundChannelExecutor ex =
                new InboundChannelExecutor(new Manual(), 8, 16, 8, 0, 0, false, null);
        for (String t : List.of("a", "b", "c", "d")) {
            asTenant(t);
            assertTrue(ex.acquire("t") != null, t + " first slot");
        }
        for (String t : List.of("a", "b")) {
            asTenant(t);
            assertTrue(ex.acquire("t") != null, t + " second slot");
        }
        // 6 in flight = 8 - headroom(2): busy tenants are now shut out, a tenant at its own cap
        // too.
        asTenant("a");
        assertEquals(null, ex.acquire("t"), "tenant cap is min(16, 8/4) = 2");
        asTenant("c");
        assertEquals(null, ex.acquire("t"), "busy tenant may not eat the reserved headroom");
        asTenant("fresh1");
        assertTrue(ex.acquire("t") != null, "idle tenant uses the reserved headroom");
        asTenant("fresh2");
        assertTrue(ex.acquire("t") != null);
        asTenant("fresh3");
        assertEquals(null, ex.acquire("t"), "global cap of 8 still holds");
    }

    @Test
    void permitRunIsIdempotentAndReleaseAfterRunDoesNotDoubleFree() {
        Manual manual = new Manual();
        InboundChannelExecutor ex = new InboundChannelExecutor(manual, 4, 4, null);
        asTenant("a");
        InboundChannelExecutor.Permit p = ex.acquire("t");
        assertTrue(p.run(() -> {}));
        p.release();
        assertFalse(p.run(() -> {}));
        assertEquals(1, ex.inFlight());
        manual.runAll();
        assertEquals(0, ex.inFlight());
    }

}
