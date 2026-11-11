package com.agenticai.core.workflow.expression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** {@link Budget}: step counting, the injectable wall clock, io() crediting and the regex guard. */
class BudgetTest {

    private static Limits limitsWithClock(AtomicLong nanos) {
        return Limits.defaults().withNanoTime(nanos::get);
    }

    @Test
    void tickCountsStepsAndThrowsPastTheCap() {
        Budget b = new Budget(Limits.defaults().withMaxSteps(3).withMaxWallNanos(10_000_000_000L));
        b.tick();
        b.tick(2);
        ExpressionException e = assertThrows(ExpressionException.class, b::tick);
        assertTrue(e.getMessage().contains("evaluation steps"));
    }

    @Test
    void tickRejectsNegativeAndOverflowingCounts() {
        Budget b =
                new Budget(Limits.defaults().withMaxSteps(100).withMaxWallNanos(10_000_000_000L));
        assertThrows(ExpressionException.class, () -> b.tick(-1));
        assertThrows(ExpressionException.class, () -> b.tick(Integer.MAX_VALUE));
    }

    @Test
    void checkTimeHonoursTheInjectedClock() {
        AtomicLong nanos = new AtomicLong(0);
        Budget b = new Budget(limitsWithClock(nanos).withMaxWallNanos(100));
        b.checkTime(); // no time has passed
        nanos.set(50);
        b.checkTime(); // inside the wall
        nanos.set(101);
        ExpressionException e = assertThrows(ExpressionException.class, b::checkTime);
        assertTrue(e.getMessage().contains("took too long"));
        assertThrows(ExpressionException.class, b::tick, "tick checks the clock too");
    }

    @Test
    void ioExcludesTheLookupLatencyFromTheWallBudget() {
        AtomicLong nanos = new AtomicLong(0);
        Budget b = new Budget(limitsWithClock(nanos).withMaxWallNanos(100).withMaxSteps(10));
        String v =
                b.io(
                        () -> {
                            nanos.set(10_000); // a slow context lookup
                            return "data";
                        });
        assertEquals("data", v);
        b.checkTime(); // the lookup's latency was credited back, so we are still inside the wall
        b.tick();
    }

    @Test
    void ioPropagatesFailuresAndStillCreditsTheTime() {
        AtomicLong nanos = new AtomicLong(0);
        Budget b = new Budget(limitsWithClock(nanos).withMaxWallNanos(100));
        IllegalStateException boom = new IllegalStateException("store down");
        IllegalStateException thrown =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                b.io(
                                        () -> {
                                            nanos.set(10_000);
                                            throw boom;
                                        }));
        assertEquals("store down", thrown.getMessage());
        b.checkTime(); // the failed lookup's latency was still excluded
    }

    @Test
    void guardedCharSequencesDelegateAndCheckTheDeadlinePeriodically() {
        AtomicLong nanos = new AtomicLong(0);
        Budget b = new Budget(limitsWithClock(nanos).withMaxWallNanos(1_000_000_000L));
        CharSequence g = b.guard("abcdef");
        assertEquals(6, g.length());
        assertEquals('b', g.charAt(1));
        assertEquals("bcd", g.subSequence(1, 4).toString());
        assertEquals("abcdef", g.toString());

        AtomicLong clock = new AtomicLong(0);
        Budget dead = new Budget(limitsWithClock(clock).withMaxWallNanos(10));
        CharSequence guarded = dead.guard("x".repeat(300));
        clock.set(1_000); // the wall is now long gone, but checkTime only runs every 128 reads
        assertThrows(
                ExpressionException.class,
                () -> {
                    for (int i = 0; i < 200; i++) {
                        guarded.charAt(i);
                    }
                });
    }
}
