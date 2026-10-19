package com.agenticai.core.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.admin.keycloak.KeycloakAdminClient;
import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.agents.quota.FreeTierUsageLimiterService;
import com.agenticai.core.chat.LlmCallLogRepository;
import com.agenticai.core.security.AccountType;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.AppUserRepository;
import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantRepository;
import com.agenticai.core.tenancy.TenantStatus;
import com.agenticai.core.workflow.WorkflowDefinitionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlatformTenantDetailControllerTest {

    private final TenantRepository tenants = mock(TenantRepository.class);
    private final AgentRepository agents = mock(AgentRepository.class);
    private final WorkflowDefinitionRepository workflows = mock(WorkflowDefinitionRepository.class);
    private final KeycloakAdminClient keycloak = mock(KeycloakAdminClient.class);
    private final FreeTierUsageLimiterService limiter = mock(FreeTierUsageLimiterService.class);
    private final LlmCallLogRepository calls = mock(LlmCallLogRepository.class);
    private final AppUserRepository appUsers = mock(AppUserRepository.class);
    private final PlatformSpendService spend = mock(PlatformSpendService.class);
    private final PlatformTenantDetailController controller =
            new PlatformTenantDetailController(
                    tenants, agents, workflows, keycloak, limiter, calls, appUsers, spend);

    @BeforeEach
    void operator() {
        TenantContext.set(TenantContext.of(null, "operator-1", Set.of("ROLE_platform-admin")));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static Tenant tenant(UUID id, String schema, TenantStatus status, boolean stopped) {
        Tenant t = mock(Tenant.class);
        when(t.getId()).thenReturn(id);
        when(t.getName()).thenReturn("Acme");
        when(t.getSchemaName()).thenReturn(schema);
        when(t.getStatus()).thenReturn(status);
        when(t.getPlan()).thenReturn(TenantPlan.FREE);
        when(t.getAccountType()).thenReturn(AccountType.B2B);
        when(t.isEmergencyStopped()).thenReturn(stopped);
        return t;
    }

    @Test
    void unknownTenantIs404Exception() {
        UUID id = UUID.randomUUID();
        when(tenants.findById(id)).thenReturn(Optional.empty());
        assertThrows(PlatformTenantNotFoundException.class, () -> controller.get(id));
    }

    @Test
    void activeTenantReadsPerSchemaCountsWithTargetTenantBoundAndRestoresCaller() {
        UUID id = UUID.randomUUID();
        Tenant tenantRow = tenant(id, "t_acme", TenantStatus.ACTIVE, false);
        when(tenants.findById(id)).thenReturn(Optional.of(tenantRow));
        when(keycloak.countUsersByTenant("t_acme")).thenReturn(4);
        AtomicReference<String> seenDuringAgentCount = new AtomicReference<>();
        when(agents.count())
                .thenAnswer(
                        inv -> {
                            seenDuringAgentCount.set(
                                    TenantContext.current()
                                            .map(TenantContext::getTenantId)
                                            .orElse(null));
                            return 7L;
                        });
        when(workflows.count()).thenReturn(3L);
        Instant when = Instant.parse("2026-09-01T10:00:00Z");
        when(calls.findLatestStartedAt()).thenReturn(when);
        when(appUsers.findLatestSeenForTenant(id))
                .thenReturn(Instant.parse("2026-09-02T08:00:00Z"));
        when(spend.spendForTenants(eq(List.of(tenantRow)), any()))
                .thenReturn(
                        Map.of(
                                id,
                                new PlatformSpendService.TenantSpendTotals(
                                        new BigDecimal("1.25"), new BigDecimal("0.25"), 9)));
        when(limiter.quotaSnapshot("t_acme", TenantPlan.FREE))
                .thenReturn(new FreeTierUsageLimiterService.QuotaSnapshot(120, 1000));

        var d = controller.get(id);

        assertEquals("t_acme", seenDuringAgentCount.get());
        assertEquals(7L, d.agentCount());
        assertEquals(3L, d.workflowCount());
        assertEquals(4L, d.userCount());
        assertEquals(when, d.lastLlmCallAt());
        assertTrue(d.lastLlmCallKnown());
        // The single MAX() aggregate, never the old 200-row fetch.
        verify(calls, never()).findTop200ByOrderByStartedAtDesc();
        // lastActivityAt = the later of last-seen and last LLM call.
        assertEquals(Instant.parse("2026-09-02T08:00:00Z"), d.lastActivityAt());
        assertEquals(0, new BigDecimal("1.25").compareTo(d.spend30dUsd()));
        assertEquals(120, d.quota().tokensUsedMonth());
        assertEquals("ALLOWED", d.accessState());
        assertTrue(d.unavailable().isEmpty());
        // Caller's tenant-less context is restored.
        assertEquals(
                "operator-1", TenantContext.current().map(TenantContext::getSubject).orElse(null));
        assertNull(TenantContext.current().map(TenantContext::getTenantId).orElse(null));
    }

    @Test
    void quotaIsKeyedBySchemaNameNotUuid() {
        UUID id = UUID.randomUUID();
        Tenant tenantRow = tenant(id, "t_acme", TenantStatus.ACTIVE, false);
        when(tenants.findById(id)).thenReturn(Optional.of(tenantRow));
        when(limiter.quotaSnapshot(anyString(), eq(TenantPlan.FREE)))
                .thenReturn(new FreeTierUsageLimiterService.QuotaSnapshot(0, 1));
        controller.get(id);
        verify(limiter).quotaSnapshot("t_acme", TenantPlan.FREE);
    }

    @Test
    void nonActiveTenantSkipsPerSchemaReadsAndReportsBlockedStatus() {
        UUID id = UUID.randomUUID();
        Tenant tenantRow = tenant(id, "t_x", TenantStatus.SUSPENDED, false);
        when(tenants.findById(id)).thenReturn(Optional.of(tenantRow));
        when(keycloak.countUsersByTenant("t_x")).thenReturn(2);
        when(limiter.quotaSnapshot("t_x", TenantPlan.FREE))
                .thenReturn(new FreeTierUsageLimiterService.QuotaSnapshot(0, 1));

        var d = controller.get(id);

        verify(agents, never()).count();
        verify(workflows, never()).count();
        assertNull(d.agentCount());
        assertFalse(d.lastLlmCallKnown());
        assertNull(d.spend30dUsd());
        verify(spend, never()).spendForTenants(any(), any());
        assertEquals("BLOCKED_STATUS", d.accessState());
    }

    @Test
    void emergencyStopReportsBlockedAndOneFailingMetricDoesNotBlankOthers() {
        UUID id = UUID.randomUUID();
        Tenant tenantRow = tenant(id, "t_acme", TenantStatus.ACTIVE, true);
        when(tenants.findById(id)).thenReturn(Optional.of(tenantRow));
        when(keycloak.countUsersByTenant("t_acme"))
                .thenThrow(new RuntimeException("keycloak down"));
        when(agents.count()).thenReturn(5L);
        when(workflows.count()).thenReturn(1L);
        when(limiter.quotaSnapshot("t_acme", TenantPlan.FREE))
                .thenReturn(new FreeTierUsageLimiterService.QuotaSnapshot(anyLongSafe(), 1));

        var d = controller.get(id);

        assertEquals("BLOCKED_EMERGENCY_STOP", d.accessState());
        assertNull(d.userCount());
        assertEquals(5L, d.agentCount());
        assertTrue(d.unavailable().contains("users"));
    }

    private static long anyLongSafe() {
        return 0L;
    }

    @Test
    void listIncludesLifecycleStatesSortedByName() {
        Tenant a = tenant(UUID.randomUUID(), "a", TenantStatus.ACTIVE, false);
        when(a.getName()).thenReturn("zeta");
        Tenant s = tenant(UUID.randomUUID(), "s", TenantStatus.SUSPENDED, false);
        when(s.getName()).thenReturn("alpha");
        when(tenants.findRosterExcludingDemo()).thenReturn(List.of(a, s));

        var rows = controller.list();

        // Demo-excluding roster query only - findByStatus(SUSPENDED) would pull in legacy
        // sandboxes.
        verify(tenants, never()).findByStatus(any());
        assertEquals(2, rows.size());
        assertEquals("alpha", rows.get(0).name());
        assertEquals("SUSPENDED", rows.get(0).status());
    }

    @Test
    void lastActivityFallsBackToLastSeenWhenTheLlmLookupFailsAndSpendFailureIsReported() {
        UUID id = UUID.randomUUID();
        Tenant tenantRow = tenant(id, "t_acme", TenantStatus.ACTIVE, false);
        when(tenants.findById(id)).thenReturn(Optional.of(tenantRow));
        when(calls.findLatestStartedAt()).thenThrow(new RuntimeException("schema gone"));
        Instant seen = Instant.parse("2026-09-03T00:00:00Z");
        when(appUsers.findLatestSeenForTenant(id)).thenReturn(seen);
        when(spend.spendForTenants(any(), any())).thenReturn(Map.of());
        when(limiter.quotaSnapshot("t_acme", TenantPlan.FREE))
                .thenReturn(new FreeTierUsageLimiterService.QuotaSnapshot(0, 1));

        var d = controller.get(id);

        assertEquals(seen, d.lastActivityAt());
        assertNull(d.spend30dUsd());
        assertTrue(d.unavailable().contains("lastLlmCall"));
        assertTrue(d.unavailable().contains("spend30d"));
    }

    @Test
    void latestPicksTheLaterNonNullInstant() {
        Instant a = Instant.parse("2026-01-01T00:00:00Z");
        Instant b = Instant.parse("2026-02-01T00:00:00Z");
        assertEquals(b, PlatformTenantDetailController.latest(a, b));
        assertEquals(b, PlatformTenantDetailController.latest(b, a));
        assertEquals(a, PlatformTenantDetailController.latest(a, null));
        assertEquals(b, PlatformTenantDetailController.latest(null, b));
        assertNull(PlatformTenantDetailController.latest(null, null));
    }
}
