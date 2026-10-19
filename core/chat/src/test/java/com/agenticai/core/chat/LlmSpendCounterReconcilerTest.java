package com.agenticai.core.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

class LlmSpendCounterReconcilerTest {

    /** Mutable clock. */
    private static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-10-13T10:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final class Fixture {
        final TestClock clock = new TestClock();
        final TenantSchemaIterator iterator = mock(TenantSchemaIterator.class);
        final PgAdvisoryJobLock lock = mock(PgAdvisoryJobLock.class);
        final List<String> visited = new ArrayList<>();
        final List<String> failing = new ArrayList<>();
        String current;
        BigDecimal driftPerTenant = BigDecimal.ZERO;
        LlmSpendCounterReconciler reconciler;

        Fixture(String interval, int cap, List<String> schemas) {
            when(lock.tryRunExclusive(anyString(), any()))
                    .thenAnswer(
                            inv -> {
                                ((Runnable) inv.getArgument(1)).run();
                                return true;
                            });
            doAnswer(
                            inv -> {
                                Consumer<String> action = inv.getArgument(0);
                                for (String s : schemas) {
                                    current = s;
                                    action.accept(s);
                                }
                                return null;
                            })
                    .when(iterator)
                    .forEachActiveTenantSchema(any());
            reconciler =
                    new LlmSpendCounterReconciler(
                            iterator,
                            lock,
                            mock(TransactionTemplate.class),
                            LlmSpendCounterReconciler.parseInterval(interval),
                            cap,
                            30_000,
                            clock) {
                        @Override
                        public BigDecimal reconcileCurrentTenant() {
                            visited.add(current);
                            if (failing.contains(current)) {
                                throw new IllegalStateException("boom " + current);
                            }
                            return driftPerTenant;
                        }
                    };
        }
    }

    @Test
    void intervalParsingAcceptsDurationsAndDisablesOnZeroOrOff() {
        assertThat(LlmSpendCounterReconciler.parseInterval("1h")).isEqualTo(Duration.ofHours(1));
        assertThat(LlmSpendCounterReconciler.parseInterval("30m"))
                .isEqualTo(Duration.ofMinutes(30));
        assertThat(LlmSpendCounterReconciler.parseInterval("PT2H")).isEqualTo(Duration.ofHours(2));
        assertThat(LlmSpendCounterReconciler.parseInterval("90")).isEqualTo(Duration.ofSeconds(90));
        for (String off : new String[] {"0", "off", "OFF", "false", "", " ", "-5m", "garbage"}) {
            assertThat(LlmSpendCounterReconciler.parseInterval(off))
                    .as("'%s' disables", off)
                    .isEqualTo(Duration.ZERO);
        }
        assertThat(LlmSpendCounterReconciler.parseInterval(null)).isEqualTo(Duration.ZERO);
    }

    @Test
    void disabledNeverTakesTheLockOrTouchesTenants() {
        Fixture f = new Fixture("off", 10, List.of("a", "b"));
        assertThat(f.reconciler.enabled()).isFalse();
        f.reconciler.tick();
        verify(f.lock, never()).tryRunExclusive(anyString(), any());
        assertThat(f.visited).isEmpty();
    }

    @Test
    void enabledRunsThenWaitsForTheIntervalThenRunsAgain() {
        Fixture f = new Fixture("1h", 10, List.of("a", "b"));
        f.reconciler.tick();
        assertThat(f.visited).containsExactly("a", "b");

        f.clock.now = f.clock.now.plusSeconds(59 * 60);
        f.reconciler.tick();
        assertThat(f.visited).as("not due yet").hasSize(2);

        f.clock.now = f.clock.now.plusSeconds(61);
        f.reconciler.tick();
        assertThat(f.visited).hasSize(4);
    }

    @Test
    void perTickCapContinuesWhereItStoppedAndFinishesEveryTenantOnce() {
        Fixture f = new Fixture("1h", 2, List.of("a", "b", "c", "d", "e"));
        f.reconciler.tick();
        assertThat(f.visited).containsExactly("a", "b");
        assertThat(f.reconciler.due()).as("mid-pass: next tick continues at once").isTrue();
        f.reconciler.tick();
        assertThat(f.visited).containsExactly("a", "b", "c", "d");
        f.reconciler.tick();
        assertThat(f.visited).containsExactly("a", "b", "c", "d", "e");
        assertThat(f.reconciler.due()).as("pass complete, waiting for the interval").isFalse();
    }

    @Test
    void oneTenantFailingDoesNotStopTheOthersAndNothingIsThrown() {
        Fixture f = new Fixture("1h", 10, List.of("a", "b", "c"));
        f.failing.add("b");
        f.driftPerTenant = new BigDecimal("0.219");
        assertThatCode(f.reconciler::tick).doesNotThrowAnyException();
        assertThat(f.visited).containsExactly("a", "b", "c");
    }

    @Test
    void aFailingTenantListDoesNotEscapeTheTickAndIsRetriedNextTick() {
        Fixture f = new Fixture("1h", 10, List.of("a"));
        doThrow(new IllegalStateException("db down"))
                .when(f.iterator)
                .forEachActiveTenantSchema(any());
        assertThatCode(f.reconciler::tick).doesNotThrowAnyException();
        assertThat(f.reconciler.due()).as("retried next tick").isTrue();
    }

    @Test
    void aLockFailureIsSwallowed() {
        Fixture f = new Fixture("1h", 10, List.of("a"));
        doThrow(new IllegalStateException("x")).when(f.lock).tryRunExclusive(anyString(), any());
        assertThatCode(f.reconciler::tick).doesNotThrowAnyException();
        assertThat(f.visited).isEmpty();
    }
}
