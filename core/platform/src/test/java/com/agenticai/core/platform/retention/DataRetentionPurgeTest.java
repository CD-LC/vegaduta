package com.agenticai.core.platform.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class DataRetentionPurgeTest {

    private static final Instant NOW = Instant.parse("2026-10-12T00:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TenantSchemaIterator iterator = mock(TenantSchemaIterator.class);
    private final PgAdvisoryJobLock lock = mock(PgAdvisoryJobLock.class);

    private DataRetentionPurge job(boolean enabled, List<DataRetentionPurge.Target> targets, int batch, int maxBatches) {
        return new DataRetentionPurge(jdbc, iterator, lock, clock, ms -> { }, enabled, false, batch, maxBatches, 1, 120,
            targets);
    }

    private DataRetentionPurge dryRunJob(List<DataRetentionPurge.Target> targets) {
        return new DataRetentionPurge(jdbc, iterator, lock, clock, ms -> { }, true, true, 100, 5, 1, 120, targets);
    }

    /** Clock that advances 1 minute on every read, to exercise the tick budget. */
    private static Clock advancing() {
        return new Clock() {
            private Instant t = NOW;
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId z) { return this; }
            @Override public synchronized Instant instant() { t = t.plusSeconds(60); return t; }
        };
    }

    private void tenants(String... schemas) {
        doAnswer(inv -> {
            Consumer<String> c = inv.getArgument(0);
            for (String s : schemas) {
                c.accept(s);
            }
            return null;
        }).when(iterator).forEachActiveTenantSchema(any());
    }

    @Test
    void targetsUseExpectedPredicatesAndOptInToolCallLog() {
        List<DataRetentionPurge.Target> off = DataRetentionPurge.targets(7, 30, 90, false, 90);
        assertEquals(List.of("workflow_event_outbox", "webhook_delivery", "notification"),
            off.stream().map(DataRetentionPurge.Target::table).toList());
        assertTrue(off.get(0).predicate().contains("published_at IS NOT NULL"));
        assertTrue(off.get(1).predicate().contains("status <> 'PENDING'"));
        List<DataRetentionPurge.Target> on = DataRetentionPurge.targets(7, 30, 90, true, 90);
        assertEquals("tool_call_log", on.get(3).table());
        assertFalse(on.stream().anyMatch(t -> t.table().equals("llm_call_log") || t.table().equals("audit_log_entry")));
    }

    @Test
    void windowsAreFlooredAtOneDay() {
        assertEquals(1, DataRetentionPurge.targets(0, -5, 0, false, 0).get(0).window().toDays());
    }

    @Test
    void deleteSqlIsSchemaQualifiedAndBatched() {
        var t = DataRetentionPurge.targets(7, 30, 90, false, 90).get(2);
        assertEquals("DELETE FROM \"t_acme\".\"notification\" WHERE id IN (SELECT id FROM \"t_acme\".\"notification\""
            + " WHERE created_at < ? LIMIT 5000)", t.deleteSql("t_acme", 5000));
    }

    @Test
    void disabledDoesNothing() {
        job(false, DataRetentionPurge.targets(7, 30, 90, false, 90), 5000, 20).purge();
        verify(lock, never()).tryRunExclusive(anyString(), any());
    }

    @Test
    void enabledRunsUnderAdvisoryLock() {
        job(true, DataRetentionPurge.targets(7, 30, 90, false, 90), 5000, 20).purge();
        verify(lock).tryRunExclusive(eq(DataRetentionPurge.LOCK_NAME), any());
    }

    @Test
    void loopsBatchesUntilShortBatchAndBindsCutoff() {
        tenants("t_a");
        when(jdbc.update(anyString(), any(Object.class))).thenReturn(100, 100, 5);
        var targets = List.of(DataRetentionPurge.targets(7, 30, 90, false, 90).get(0));
        job(true, targets, 100, 20).purgeExclusively();
        ArgumentCaptor<Object> cutoff = ArgumentCaptor.forClass(Object.class);
        verify(jdbc, times(3)).update(contains("\"t_a\".\"workflow_event_outbox\""), cutoff.capture());
        assertEquals(Timestamp.from(NOW.minusSeconds(7 * 86400L)), cutoff.getValue());
    }

    @Test
    void boundsBatchesPerTablePerTick() {
        tenants("t_a");
        when(jdbc.update(anyString(), any(Object.class))).thenReturn(100);
        var targets = List.of(DataRetentionPurge.targets(7, 30, 90, false, 90).get(0));
        job(true, targets, 100, 3).purgeExclusively();
        verify(jdbc, times(3)).update(anyString(), any(Object.class));
    }

    @Test
    void oneTableFailureDoesNotStopOthersAndBadSchemaIsSkipped() {
        tenants("bad\"schema", "t_a");
        when(jdbc.update(contains("workflow_event_outbox"), any(Object.class))).thenThrow(new RuntimeException("boom"));
        when(jdbc.update(contains("webhook_delivery"), any(Object.class))).thenReturn(0);
        when(jdbc.update(contains("\"notification\""), any(Object.class))).thenReturn(0);
        job(true, DataRetentionPurge.targets(7, 30, 90, false, 90), 100, 5).purgeExclusively();
        verify(jdbc, times(3)).update(anyString(), any(Object.class));
        verify(jdbc).update(contains("\"t_a\".\"webhook_delivery\""), any(Object.class));
    }

    @Test
    void dryRunOnlyCountsAndNeverDeletes() {
        tenants("t_a");
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object.class))).thenReturn(42);
        dryRunJob(DataRetentionPurge.targets(7, 30, 90, false, 90)).purgeExclusively();
        verify(jdbc, never()).update(anyString(), any(Object.class));
        verify(jdbc, times(3)).queryForObject(contains("SELECT count(*) FROM (SELECT 1 FROM \"t_a\""),
            eq(Integer.class), any(Object.class));
    }

    @Test
    void tickBudgetDefersTenantsAndNextTickRotatesToThem() {
        tenants("t_a", "t_b", "t_c");
        when(jdbc.update(anyString(), any(Object.class))).thenReturn(0);
        var targets = List.of(DataRetentionPurge.targets(7, 30, 90, false, 90).get(0));
        // advancing clock: deadline (120s) is passed after ~2 reads, so only the first tenant is reached per tick
        var j = new DataRetentionPurge(jdbc, iterator, lock, advancing(), ms -> { }, true, false, 100, 5, 1, 120, targets);
        j.purgeExclusively();
        verify(jdbc).update(contains("\"t_a\""), any(Object.class));
        verify(jdbc, never()).update(contains("\"t_b\""), any(Object.class));
        j.purgeExclusively();
        verify(jdbc, atLeastOnce()).update(contains("\"t_b\""), any(Object.class));
    }

    @Test
    void interruptedThreadStopsBeforeAnyDelete() {
        tenants("t_a");
        Thread.currentThread().interrupt();
        try {
            job(true, DataRetentionPurge.targets(7, 30, 90, false, 90), 100, 5).purgeExclusively();
        } finally {
            assertTrue(Thread.interrupted());
        }
        verify(jdbc, never()).update(anyString(), any(Object.class));
    }

    @Test
    void purgeRunsJobBodyUnderLock() {
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return true;
        }).when(lock).tryRunExclusive(anyString(), any());
        tenants("t_a");
        when(jdbc.update(anyString(), any(Object.class))).thenReturn(0);
        job(true, DataRetentionPurge.targets(7, 30, 90, false, 90), 100, 5).purge();
        verify(jdbc, times(3)).update(anyString(), any(Object.class));
    }
}
