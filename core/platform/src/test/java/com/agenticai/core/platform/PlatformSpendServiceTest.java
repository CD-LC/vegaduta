package com.agenticai.core.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.chat.LlmCallLogRepository;
import com.agenticai.core.chat.LlmUsageRollupService;
import com.agenticai.core.chat.SpendReport;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlatformSpendServiceTest {

    private final TenantRepository tenants = mock(TenantRepository.class);
    private final LlmUsageRollupService rollup = mock(LlmUsageRollupService.class);
    private final LlmCallLogRepository callLog = mock(LlmCallLogRepository.class);
    private final PlatformSpendService service = new PlatformSpendService(tenants, rollup, callLog);
    private final PlatformSpendService.Range range =
            new PlatformSpendService.Range(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 5));

    @BeforeEach
    void operator() {
        TenantContext.set(TenantContext.of(null, "operator-1", Set.of("ROLE_platform-admin")));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static Tenant tenant(String name, String schema) {
        Tenant t = mock(Tenant.class);
        when(t.getId()).thenReturn(UUID.randomUUID());
        when(t.getName()).thenReturn(name);
        when(t.getSchemaName()).thenReturn(schema);
        return t;
    }

    private static SpendReport.UsageMetrics m(long calls, String cost, Double avg) {
        return new SpendReport.UsageMetrics(
                calls, 1, 1.0, 10, 20, 30, calls, 1, new BigDecimal(cost), avg, null, null, null);
    }

    @Test
    void summaryMergesTenantsAndKeepsHostedSeparate() {
        Tenant t0 = tenant("A", "t_a");
        Tenant t1 = tenant("B", "t_b");
        when(tenants.findActiveExcludingDemo()).thenReturn(List.of(t0, t1));
        when(rollup.byProvider(any(), any()))
                .thenReturn(
                        List.of(
                                new SpendReport.ProviderUsage(
                                        "PLATFORM_HOSTED", m(10, "1.5", 100.0)),
                                new SpendReport.ProviderUsage("OPENAI", m(30, "4.0", 200.0))));
        var s = service.summary(range);
        assertEquals(0, new BigDecimal("11.0").compareTo(s.allProviders().estimatedCostUsd()));
        assertEquals(0, new BigDecimal("3.0").compareTo(s.hostedPool().estimatedCostUsd()));
        assertEquals(80, s.allProviders().totalCalls());
        assertEquals(20, s.hostedPool().totalCalls());
        assertEquals(175.0, s.allProviders().avgLatencyMs(), 0.0001);
        assertEquals(2, s.meta().tenantsScanned());
    }

    @Test
    void failingTenantIsCountedNotFatal() {
        Tenant good = tenant("A", "t_a");
        Tenant bad = tenant("B", "t_b");
        when(tenants.findActiveExcludingDemo()).thenReturn(List.of(good, bad));
        when(rollup.byProvider(any(), any()))
                .thenAnswer(
                        inv -> {
                            if ("t_b".equals(TenantContext.current().orElseThrow().getTenantId())) {
                                throw new IllegalStateException("schema missing");
                            }
                            return List.of(
                                    new SpendReport.ProviderUsage("OPENAI", m(5, "1", null)));
                        });
        var r = service.byTenant(range);
        assertEquals(1, r.rows().size());
        assertEquals(1, r.meta().tenantsFailed());
        assertNull(r.rows().get(0).allProviders().avgLatencyMs());
    }

    @Test
    void sweepBindsTenantSchemaAndRestoresOperatorContext() {
        Tenant t0 = tenant("A", "t_a");
        when(tenants.findActiveExcludingDemo()).thenReturn(List.of(t0));
        List<String> seen = new ArrayList<>();
        when(rollup.byProvider(any(), any()))
                .thenAnswer(
                        inv -> {
                            seen.add(TenantContext.current().orElseThrow().getTenantId());
                            return List.of();
                        });
        service.summary(range);
        assertEquals(List.of("t_a"), seen);
        assertEquals("operator-1", TenantContext.current().orElseThrow().getSubject());
    }

    @Test
    void tenantCapTruncatesAndFlags() {
        List<Tenant> many = new ArrayList<>();
        for (int i = 0; i < PlatformSpendService.MAX_TENANTS + 3; i++) {
            many.add(tenant("T" + i, "t_" + i));
        }
        when(tenants.findActiveExcludingDemo()).thenReturn(many);
        when(rollup.byProvider(any(), any())).thenReturn(List.of());
        var s = service.summary(range);
        assertTrue(s.meta().truncated());
        assertEquals(PlatformSpendService.MAX_TENANTS, s.meta().tenantsScanned());
        assertEquals(PlatformSpendService.MAX_TENANTS + 3, s.meta().tenantsTotal());
    }

    @Test
    void rangeDefaultsAndRejectsBadInput() {
        var r = PlatformSpendService.resolveRange(null, LocalDate.of(2026, 9, 30));
        assertEquals(LocalDate.of(2026, 9, 1), r.from());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        PlatformSpendService.resolveRange(
                                LocalDate.of(2026, 9, 5), LocalDate.of(2026, 9, 1)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        PlatformSpendService.resolveRange(
                                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 9, 1)));
        // exactly 92 days apart is allowed
        PlatformSpendService.resolveRange(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 9, 1));
    }

    @Test
    void csvCellNeutralisesFormulaInjectionAndQuotes() {
        assertEquals("\"'=A,B\"", PlatformSpendService.cell("=A,B"));
        assertEquals("plain", PlatformSpendService.cell("plain"));
        assertEquals("", PlatformSpendService.cell(null));
    }

    @Test
    void exportTenantCsvHasScopeHeaderAndRows() {
        Tenant t0 = tenant("=evil", "t_a");
        when(tenants.findActiveExcludingDemo()).thenReturn(List.of(t0));
        when(rollup.byProvider(any(), any()))
                .thenReturn(
                        List.of(
                                new SpendReport.ProviderUsage(
                                        "PLATFORM_HOSTED", m(2, "0.5", 10.0))));
        String csv = service.exportCsv("tenant", range);
        assertTrue(csv.startsWith("# allProviders"));
        assertTrue(csv.contains("hosted_pool_estimated_cost_usd"));
        assertTrue(csv.contains("'=evil"));
        assertThrows(IllegalArgumentException.class, () -> service.exportCsv("agent", range));
    }

    @Test
    void sweepUsesTheOrderedDemoExcludingListNeverThePlainStatusFilter() {
        Tenant t0 = tenant("A", "t_a");
        when(tenants.findActiveExcludingDemo()).thenReturn(List.of(t0));
        when(rollup.byProvider(any(), any())).thenReturn(List.of());
        var s = service.summary(range);
        assertEquals(1, s.meta().tenantsScanned());
        assertEquals("platform-wide", s.meta().scope());
        assertNull(s.meta().tenantId());
        org.mockito.Mockito.verify(tenants, org.mockito.Mockito.never()).findByStatus(any());
    }

    @Test
    void truncationKeepsTheFirstTenantsInTheOrderGiven() {
        List<Tenant> many = new ArrayList<>();
        for (int i = 0; i < PlatformSpendService.MAX_TENANTS + 1; i++) {
            many.add(tenant("T" + i, "t_" + i));
        }
        when(tenants.findActiveExcludingDemo()).thenReturn(many);
        List<String> seen = new ArrayList<>();
        when(rollup.byProvider(any(), any()))
                .thenAnswer(
                        inv -> {
                            seen.add(TenantContext.current().orElseThrow().getTenantId());
                            return List.of();
                        });
        var s = service.summary(range);
        assertTrue(s.meta().truncated());
        assertEquals("t_0", seen.get(0));
        assertEquals("t_" + (PlatformSpendService.MAX_TENANTS - 1), seen.get(seen.size() - 1));
    }

    @Test
    void tenantIdRestrictsTheSweepToThatTenant() {
        Tenant only = tenant("B", "t_b");
        UUID id = only.getId();
        when(tenants.findById(id)).thenReturn(java.util.Optional.of(only));
        List<String> seen = new ArrayList<>();
        when(rollup.byProvider(any(), any()))
                .thenAnswer(
                        inv -> {
                            seen.add(TenantContext.current().orElseThrow().getTenantId());
                            return List.of(
                                    new SpendReport.ProviderUsage("OPENAI", m(4, "2", null)));
                        });
        var r = service.byTenant(range, id);
        assertEquals(List.of("t_b"), seen);
        assertEquals("tenant", r.meta().scope());
        assertEquals(id, r.meta().tenantId());
        assertEquals(1, r.rows().size());
        org.mockito.Mockito.verify(tenants, org.mockito.Mockito.never()).findActiveExcludingDemo();
    }

    @Test
    void unknownTenantIdIsANotFound() {
        UUID id = UUID.randomUUID();
        when(tenants.findById(id)).thenReturn(java.util.Optional.empty());
        assertThrows(PlatformTenantNotFoundException.class, () -> service.summary(range, id));
    }

    @Test
    void spendForTenantsReturnsTotalsAndLeavesFailedTenantsAbsent() {
        Tenant good = tenant("A", "t_a");
        Tenant bad = tenant("B", "t_b");
        when(rollup.byProvider(any(), any()))
                .thenAnswer(
                        inv -> {
                            if ("t_b".equals(TenantContext.current().orElseThrow().getTenantId())) {
                                throw new IllegalStateException("schema missing");
                            }
                            return List.of(
                                    new SpendReport.ProviderUsage(
                                            "PLATFORM_HOSTED", m(3, "0.25", null)),
                                    new SpendReport.ProviderUsage("OPENAI", m(7, "1.75", null)));
                        });
        var out = service.spendForTenants(List.of(good, bad), range);
        assertEquals(1, out.size());
        var totals = out.get(good.getId());
        assertEquals(0, new BigDecimal("2.00").compareTo(totals.allProvidersUsd()));
        assertEquals(0, new BigDecimal("0.25").compareTo(totals.hostedPoolUsd()));
        assertEquals(10, totals.totalCalls());
        assertNull(out.get(bad.getId()));
        assertEquals("operator-1", TenantContext.current().orElseThrow().getSubject());
    }
}
