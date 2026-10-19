package com.agenticai.core.platform.northstar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.platform.northstar.NorthStarAggregator.RunBucket;
import com.agenticai.core.platform.northstar.NorthStarAggregator.RunKind;
import com.agenticai.core.platform.northstar.NorthStarDtos.NorthStarResponse;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantRepository;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * The orchestration around the pure aggregator, with the database mocked at
 * {@link NorthStarQueries}: cache behaviour, the disabled flag, per-source
 * failure reporting (never a silent zero), the total time budget and tenant
 * context hygiene.
 */
class NorthStarServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");
    private static final LocalDate CURRENT_WEEK = LocalDate.of(2026, 9, 28);

    private TenantRepository tenantRepository;
    private NorthStarQueries queries;

    @BeforeEach
    void setUp() {
        tenantRepository = mock(TenantRepository.class);
        queries = mock(NorthStarQueries.class);
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private NorthStarService service(boolean enabled, long ttlSeconds, long budgetSeconds) {
        NorthStarService s = new NorthStarService(tenantRepository, queries, enabled, ttlSeconds, budgetSeconds);
        s.clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return s;
    }

    private static Tenant tenant(String schema) {
        Tenant t = mock(Tenant.class);
        when(t.getSchemaName()).thenReturn(schema);
        when(t.getId()).thenReturn(UUID.nameUUIDFromBytes(schema.getBytes()));
        return t;
    }

    private static List<RunBucket> fiveInteractiveUsers(String tenant) {
        List<RunBucket> out = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            out.add(new RunBucket(CURRENT_WEEK, RunKind.INTERACTIVE, null, true, tenant + ":u" + i, 2));
        }
        return out;
    }

    private void tenants(String... schemas) {
        List<Tenant> list = new ArrayList<>();
        for (String s : schemas) {
            list.add(tenant(s));
        }
        when(tenantRepository.findActiveExcludingDemo()).thenReturn(list);
    }

    @Test
    void disabled_answers404() {
        NorthStarService s = service(false, 3600, 90);
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> s.northStar(12));
        assertEquals(404, e.getStatusCode().value());
    }

    @Test
    void weeksOutOfRange_isABadRequest() {
        NorthStarService s = service(true, 3600, 90);
        assertThrows(IllegalArgumentException.class, () -> s.northStar(0));
        assertThrows(IllegalArgumentException.class, () -> s.northStar(53));
    }

    @Test
    void mergesTenantsIntoOneWeeklySeriesEndingWithThePartialCurrentWeek() {
        tenants("t1", "t2");
        when(queries.jobRuns(eq("t1"), any())).thenReturn(fiveInteractiveUsers("t1"));
        when(queries.jobRuns(eq("t2"), any())).thenReturn(fiveInteractiveUsers("t2"));
        when(queries.quickTaskRuns(any(), any())).thenReturn(List.of());
        when(queries.firstRuns(any(), any(), any())).thenReturn(List.of());
        when(queries.passK(any(), any())).thenReturn(List.of());
        when(queries.signupsByWeek(any())).thenReturn(Map.of());

        NorthStarResponse r = service(true, 3600, 90).northStar(4);

        assertEquals(NOW, r.generatedAt());
        assertEquals(4, r.weeks().size());
        var last = r.weeks().get(3);
        assertEquals(CURRENT_WEEK, last.weekStart());
        assertTrue(last.partial());
        assertEquals(10L, last.wau()); // 5 users in each of 2 tenants, never merged across tenants
        assertEquals(20L, last.runs());
        assertEquals(1.0, last.successRate());
        assertEquals(2, r.coverage().tenantsScanned());
        assertFalse(r.coverage().truncated());
        assertTrue(r.languages().isEmpty());
        assertNull(r.passK());
        assertNull(r.timeToFirstRunMedianMinutes());
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("conversations by language") && n.contains("unavailable")));
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("interactive chat turns")));
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("pass^k") && n.contains("unavailable")));
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("time to first run") && n.contains("unavailable")));
    }

    @Test
    void aFailingSourceInOneTenantIsReportedAndTheRestStillCount() {
        tenants("t1", "t2");
        when(queries.jobRuns(any(), any())).thenReturn(List.of());
        when(queries.jobRuns(eq("t1"), any())).thenReturn(fiveInteractiveUsers("t1"));
        when(queries.quickTaskRuns(eq("t1"), any()))
            .thenThrow(new RuntimeException("wrapped", new SQLException("no table", "42P01")));
        when(queries.quickTaskRuns(eq("t2"), any())).thenReturn(List.of());
        when(queries.firstRuns(any(), any(), any())).thenReturn(List.of());
        when(queries.passK(any(), any())).thenReturn(List.of());
        when(queries.signupsByWeek(any())).thenReturn(Map.of());

        NorthStarResponse r = service(true, 3600, 90).northStar(2);

        assertEquals(5L, r.weeks().get(1).wau());
        assertEquals(10L, r.weeks().get(1).runs());
        String note = r.notes().stream().filter(n -> n.contains("quick_task_run")).findFirst().orElseThrow();
        assertTrue(note.contains("partially unavailable"), note);
        assertTrue(note.contains("1 of 2"), note);
        assertTrue(note.contains("without the source table"), note);
    }

    @Test
    void aSourceFailingEverywhereIsReportedAsUnavailable() {
        tenants("t1");
        when(queries.jobRuns(any(), any())).thenThrow(new RuntimeException(new SQLException("canceled", "57014")));
        when(queries.quickTaskRuns(any(), any())).thenReturn(List.of());
        when(queries.firstRuns(any(), any(), any())).thenReturn(List.of());
        when(queries.passK(any(), any())).thenReturn(List.of());
        when(queries.signupsByWeek(any())).thenReturn(Map.of());

        NorthStarResponse r = service(true, 3600, 90).northStar(2);

        String note = r.notes().stream().filter(n -> n.contains("agent_job")).findFirst().orElseThrow();
        assertTrue(note.contains(" unavailable because"), note);
        assertTrue(note.contains("timed out"), note);
        assertEquals(0L, r.weeks().get(1).runs()); // nothing was read, and the note says why
    }

    @Test
    void unreadableSignupsAreNotedAndCohortSignupsAreOmitted() {
        tenants("t1");
        when(queries.jobRuns(any(), any())).thenReturn(List.of());
        when(queries.quickTaskRuns(any(), any())).thenReturn(List.of());
        when(queries.firstRuns(any(), any(), any())).thenReturn(List.of());
        when(queries.passK(any(), any())).thenReturn(List.of());
        when(queries.signupsByWeek(any())).thenThrow(new RuntimeException("boom"));

        NorthStarResponse r = service(true, 3600, 90).northStar(2);

        assertNull(r.timeToFirstRunCohorts().get(0).signups());
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("Signup counts unavailable")));
    }

    @Test
    void cachesTheSingleEntryForTheTtlAndRecomputesForADifferentWindow() {
        tenants("t1");
        when(queries.jobRuns(any(), any())).thenReturn(List.of());
        when(queries.quickTaskRuns(any(), any())).thenReturn(List.of());
        when(queries.firstRuns(any(), any(), any())).thenReturn(List.of());
        when(queries.passK(any(), any())).thenReturn(List.of());
        when(queries.signupsByWeek(any())).thenReturn(Map.of());
        NorthStarService s = service(true, 3600, 90);

        NorthStarResponse first = s.northStar(12);
        NorthStarResponse again = s.northStar(12);
        assertSame(first, again);
        verify(queries, times(1)).jobRuns(any(), any());

        NorthStarResponse other = s.northStar(8); // different window: size-1 cache is replaced
        assertEquals(8, other.weeks().size());
        verify(queries, times(2)).jobRuns(any(), any());

        s.northStar(12); // the 12-week entry was evicted
        verify(queries, times(3)).jobRuns(any(), any());
    }

    @Test
    void expiredAndZeroTtlEntriesAreRecomputed() {
        tenants("t1");
        when(queries.jobRuns(any(), any())).thenReturn(List.of());
        when(queries.quickTaskRuns(any(), any())).thenReturn(List.of());
        when(queries.firstRuns(any(), any(), any())).thenReturn(List.of());
        when(queries.passK(any(), any())).thenReturn(List.of());
        when(queries.signupsByWeek(any())).thenReturn(Map.of());

        NorthStarService s = service(true, 3600, 90);
        s.northStar(12);
        s.clock = Clock.fixed(NOW.plusSeconds(3601), ZoneOffset.UTC);
        s.northStar(12);
        verify(queries, times(2)).jobRuns(any(), any());

        NorthStarService noCache = service(true, 0, 90);
        noCache.northStar(12);
        noCache.northStar(12);
        verify(queries, times(4)).jobRuns(any(), any());
    }

    @Test
    void theTotalBudgetStopsTheSweepAndSaysSo() {
        tenants("t1", "t2", "t3");
        when(queries.jobRuns(any(), any())).thenReturn(List.of());
        when(queries.quickTaskRuns(any(), any())).thenReturn(List.of());
        when(queries.firstRuns(any(), any(), any())).thenReturn(List.of());
        when(queries.passK(any(), any())).thenReturn(List.of());
        when(queries.signupsByWeek(any())).thenReturn(Map.of());
        NorthStarService s = service(true, 3600, 5);
        // Every clock read jumps 10s: the first read (compute start) fixes the deadline,
        // every later read is already past it, so no tenant is scanned.
        s.clock = new Clock() {
            private Instant t = NOW.minusSeconds(10);

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
                t = t.plusSeconds(10);
                return t;
            }
        };

        NorthStarResponse r = s.northStar(2);

        assertEquals(3, r.coverage().tenantsTotal());
        assertEquals(0, r.coverage().tenantsScanned());
        assertEquals(3, r.coverage().tenantsSkipped());
        assertTrue(r.coverage().truncated());
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("Partial sweep") && n.contains("0 of 3")));
    }

    @Test
    void restoresTheCallersTenantContext() {
        tenants("t1");
        when(queries.jobRuns(any(), any())).thenReturn(List.of());
        when(queries.quickTaskRuns(any(), any())).thenReturn(List.of());
        when(queries.firstRuns(any(), any(), any())).thenReturn(List.of());
        when(queries.passK(any(), any())).thenReturn(List.of());
        when(queries.signupsByWeek(any())).thenReturn(Map.of());
        TenantContext.set(TenantContext.of("operator", "admin", java.util.Set.of("platform-admin")));

        service(true, 3600, 90).northStar(1);

        assertTrue(TenantContext.current().isPresent());
        assertEquals("operator", TenantContext.current().get().getTenantId());
    }

    @Test
    void suppressedCellsAreCountedInTheNotes() {
        tenants("t1");
        List<RunBucket> three = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            three.add(new RunBucket(CURRENT_WEEK, RunKind.INTERACTIVE, null, true, "t1:u" + i, 1));
        }
        when(queries.jobRuns(any(), any())).thenReturn(three);
        when(queries.quickTaskRuns(any(), any())).thenReturn(List.of());
        when(queries.firstRuns(any(), any(), any())).thenReturn(List.of());
        when(queries.passK(any(), any())).thenReturn(List.of());
        when(queries.signupsByWeek(any())).thenReturn(Map.of());

        NorthStarResponse r = service(true, 3600, 90).northStar(1);

        var w = r.weeks().get(0);
        assertTrue(w.suppressed());
        assertNull(w.runs());
        assertNotNull(w.weekStart());
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("1 cell(s) suppressed")));
    }
}
