package com.agenticai.core.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.audit.AuditLogRepository;
import com.agenticai.core.audit.IssueStatus;
import com.agenticai.core.audit.PlatformIssueRepository;
import com.agenticai.core.chat.LlmCallLogRepository;
import com.agenticai.core.chat.LlmOverallUsageSummary;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.PlatformSettingsService;
import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 2026-10-09 accuracy fixes: every observability sweep visits registered tenants only (never the
 * plain ACTIVE filter that included /try sandboxes), tenants that never called an LLM are not usage
 * rows, and the open-issue figure is an exact per-tenant COUNT that reports how many tenants could
 * not be counted.
 */
class PlatformObservabilityServiceTest {

    private final TenantRepository tenants = mock(TenantRepository.class);
    private final PlatformIssueRepository issues = mock(PlatformIssueRepository.class);
    private final LlmCallLogRepository calls = mock(LlmCallLogRepository.class);
    private final PlatformObservabilityService service =
            new PlatformObservabilityService(
                    tenants,
                    issues,
                    mock(AuditLogRepository.class),
                    calls,
                    mock(AgentRepository.class),
                    mock(PlatformAlertSettingsRepository.class),
                    mock(PlatformSettingsService.class));

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

    private static String schemaNow() {
        return TenantContext.current().map(TenantContext::getTenantId).orElse(null);
    }

    @Test
    void openIssueCountSumsExactCountsAndReportsFailedTenants() {
        List<Tenant> roster = List.of(tenant("A", "a"), tenant("B", "b"), tenant("C", "c"));
        when(tenants.findActiveExcludingDemo()).thenReturn(roster);
        when(issues.countByStatus(IssueStatus.OPEN))
                .thenAnswer(
                        inv ->
                                switch (schemaNow()) {
                                    case "a" -> 120L; // more than the 50-per-tenant cap the list
                                    // endpoint applies
                                    case "b" ->
                                            throw new IllegalStateException(
                                                    "relation does not exist");
                                    default -> 3L;
                                });

        var count = service.openIssueCount();

        assertEquals(123L, count.open());
        assertEquals(1, count.failedTenants());
        verify(tenants, never()).findByStatus(any());
        assertTrue(TenantContext.current().isEmpty());
    }

    @Test
    void llmUsageSkipsTenantsWithNoCallsAndNeverSweepsDemoTenants() {
        List<Tenant> roster = List.of(tenant("Busy", "busy"), tenant("Idle", "idle"));
        when(tenants.findActiveExcludingDemo()).thenReturn(roster);
        when(calls.overallSummary())
                .thenAnswer(
                        inv ->
                                "busy".equals(schemaNow())
                                        ? new LlmOverallUsageSummary(10, 1, 120.0)
                                        : new LlmOverallUsageSummary(0, 0, null));
        when(calls.tokensSince(any())).thenReturn(500L);
        when(calls.costSince(any())).thenReturn(new BigDecimal("0.10"));

        var rows = service.llmUsageByTenant();

        assertEquals(1, rows.size());
        assertEquals("Busy", rows.get(0).tenantName());
        verify(tenants, never()).findByStatus(any());
    }
}
