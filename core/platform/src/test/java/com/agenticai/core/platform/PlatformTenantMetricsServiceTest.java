package com.agenticai.core.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.admin.keycloak.KeycloakAdminClient;
import com.agenticai.core.admin.keycloak.KeycloakUserRepresentation;
import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.chat.LlmCallLogRepository;
import com.agenticai.core.security.AccountType;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.AppUser;
import com.agenticai.core.tenancy.AppUserRepository;
import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantRepository;
import com.agenticai.core.tenancy.TenantStatus;
import com.agenticai.core.tenancy.UserRole;
import com.agenticai.core.workflow.WorkflowDefinitionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link PlatformTenantMetricsService#listTenantSummaries()} sets {@link TenantContext} per tenant
 * around three schema-routed lookups - that ThreadLocal is the ONLY thing standing between the
 * admin roster and cross-tenant counts (both {@link AgentRepository} and {@link
 * WorkflowDefinitionRepository} are routed to a Postgres schema by Hibernate's multi-tenancy
 * resolver reading exactly this context). The suite pins: which context is live INSIDE each
 * repository call (captured via a Mockito {@code Answer} at call time, since verifying arguments
 * after the fact would prove nothing about a ThreadLocal), that the context is cleared in the
 * {@code finally} even when a lookup throws, that each of the three counts fails independently
 * (flagging {@code metricsComplete=false} without blanking the other two), and - the documented,
 * already-once-regressed bug - that {@code countUsersByTenant} receives the SCHEMA NAME, not the
 * tenant UUID (the UUID matched no Keycloak {@code tenant_id} attribute, so every tenant's user
 * count silently read 0 on the admin roster; flagged twice by independent sessions before being
 * fixed).
 *
 * <p>Real (not mocked) {@link TenantContext} static holder with an {@code @AfterEach} clear - same
 * convention as {@code TenantDataErasureServiceTest} (core/tenancy). No Spring context: the
 * {@code @Profile("security")} annotation on the service is inert for a {@code new}-constructed
 * instance.
 */
class PlatformTenantMetricsServiceTest {

    private TenantRepository tenantRepository;
    private AgentRepository agentRepository;
    private WorkflowDefinitionRepository workflowDefinitionRepository;
    private KeycloakAdminClient keycloakAdminClient;
    private AppUserRepository appUserRepository;
    private LlmCallLogRepository llmCallLogRepository;
    private PlatformSpendService spendService;
    private PlatformTenantMetricsService service;

    @BeforeEach
    void setUp() {
        tenantRepository = mock(TenantRepository.class);
        agentRepository = mock(AgentRepository.class);
        workflowDefinitionRepository = mock(WorkflowDefinitionRepository.class);
        keycloakAdminClient = mock(KeycloakAdminClient.class);
        appUserRepository = mock(AppUserRepository.class);
        llmCallLogRepository = mock(LlmCallLogRepository.class);
        spendService = mock(PlatformSpendService.class);
        service =
                new PlatformTenantMetricsService(
                        tenantRepository,
                        agentRepository,
                        workflowDefinitionRepository,
                        keycloakAdminClient,
                        appUserRepository,
                        llmCallLogRepository,
                        spendService);
    }

    /** Keycloak users carrying the given tenant_id attribute (schema name). */
    private static List<KeycloakUserRepresentation> kcUsers(String schema, int n) {
        List<KeycloakUserRepresentation> users = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            users.add(
                    new KeycloakUserRepresentation(
                            schema + "-u" + i,
                            null,
                            null,
                            null,
                            null,
                            true,
                            true,
                            null,
                            Map.of("tenant_id", List.of(schema)),
                            null));
        }
        return users;
    }

    private static Tenant mockTenant(String name, String schema, TenantStatus status) {
        Tenant t = mock(Tenant.class);
        when(t.getId()).thenReturn(UUID.randomUUID());
        when(t.getName()).thenReturn(name);
        when(t.getSchemaName()).thenReturn(schema);
        when(t.getStatus()).thenReturn(status);
        when(t.getPlan()).thenReturn(TenantPlan.FREE);
        when(t.getCreatedAt()).thenReturn(Instant.parse("2026-01-01T00:00:00Z"));
        return t;
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private Tenant activeTenant(String name, String schema) {
        Tenant tenant =
                new Tenant(
                        name,
                        TenantPlan.FREE,
                        schema,
                        "agentic-ai",
                        AccountType.B2C,
                        ExperienceMode.ADVANCED);
        tenant.markActive();
        return tenant;
    }

    /** The tenant schema live on the calling thread at the moment a mocked lookup runs. */
    private static String schemaAtCallTime() {
        return TenantContext.current().map(TenantContext::getTenantId).orElse(null);
    }

    @Test
    void theRosterComesFromTheDemoExcludingQueryNeverFromThePlainStatusFilter() {
        // The bug this pins: findByStatus(ACTIVE) returns every anonymous /try
        // sandbox too (is_demo tenants are ACTIVE), so the admin tenant count
        // rose with landing-page traffic and no registrations. Only
        // findActiveExcludingDemo() is stubbed - a regression back to
        // findByStatus (or findAll) gets Mockito's empty-list default and
        // fails the size assertion, and verifyNoMoreInteractions fails it again.
        when(tenantRepository.findRosterExcludingDemo())
                .thenReturn(List.of(activeTenant("Acme", "acme")));

        List<PlatformTenantSummary> summaries = service.listTenantSummaries();

        assertEquals(1, summaries.size());
        verify(tenantRepository).findRosterExcludingDemo();
        verifyNoMoreInteractions(tenantRepository);
    }

    @Test
    void tenantCountsReportsRegisteredAndDemoSandboxesAsSeparateFigures() {
        when(tenantRepository.countActiveExcludingDemo()).thenReturn(42L);
        when(tenantRepository.countDemoByStatus("ACTIVE")).thenReturn(17L);
        when(tenantRepository.countDemoByStatus("SUSPENDED")).thenReturn(230L);

        PlatformTenantCounts counts = service.tenantCounts();

        // Never summed: a sandbox is not a registration.
        assertEquals(42L, counts.registeredActive());
        assertEquals(17L, counts.demoLive());
        assertEquals(230L, counts.demoExpiredUnreclaimed());
        // Counts only - no per-tenant schema work, so no TenantContext and no
        // schema-routed repository is touched.
        verifyNoMoreInteractions(
                agentRepository, workflowDefinitionRepository, keycloakAdminClient);
        assertTrue(TenantContext.current().isEmpty());
    }

    @Test
    void
            everyRepositoryLookupRunsUnderExactlyThatTenantsSchemaContextAndTheContextIsClearedAfter() {
        when(tenantRepository.findRosterExcludingDemo())
                .thenReturn(
                        List.of(activeTenant("Acme", "acme"), activeTenant("Globex", "globex")));

        // Capture the LIVE ThreadLocal inside each lookup - the only moment
        // the isolation property can actually be observed.
        List<String> agentCallSchemas = new ArrayList<>();
        List<String> workflowCallSchemas = new ArrayList<>();
        List<String> llmCallSchemas = new ArrayList<>();
        when(agentRepository.count())
                .thenAnswer(
                        inv -> {
                            agentCallSchemas.add(schemaAtCallTime());
                            return 3L;
                        });
        when(workflowDefinitionRepository.count())
                .thenAnswer(
                        inv -> {
                            workflowCallSchemas.add(schemaAtCallTime());
                            return 5L;
                        });
        when(llmCallLogRepository.findLatestStartedAt())
                .thenAnswer(
                        inv -> {
                            llmCallSchemas.add(schemaAtCallTime());
                            return null;
                        });
        when(keycloakAdminClient.listUsersGroupedByTenant())
                .thenReturn(Map.of("acme", kcUsers("acme", 7)));

        List<PlatformTenantSummary> summaries = service.listTenantSummaries();

        // Each lookup ran once per tenant, under THAT tenant's schema - never
        // the previous tenant's (a leak here is a cross-tenant count on the
        // admin roster) and never a null/public fallback.
        assertEquals(List.of("acme", "globex"), agentCallSchemas);
        assertEquals(List.of("acme", "globex"), workflowCallSchemas);
        assertEquals(List.of("acme", "globex"), llmCallSchemas);
        // And nothing may remain on the thread afterwards - this thread goes
        // back to a pool and would otherwise poison an unrelated request.
        assertTrue(TenantContext.current().isEmpty());
        assertEquals(2, summaries.size());
        assertTrue(summaries.get(0).metricsComplete());
        assertEquals(3L, summaries.get(0).agentCount());
        assertEquals(5L, summaries.get(0).workflowCount());
        assertEquals(7L, summaries.get(0).userCount());
    }

    @Test
    void eachOfTheThreeCountsFailsIndependentlyFlaggingMetricsIncompleteWithoutBlankingTheOthers() {
        // Three tenants, each failing a DIFFERENT lookup - proves per-count
        // isolation AND that one tenant's failure never stops the loop from
        // reaching the next tenant.
        when(tenantRepository.findRosterExcludingDemo())
                .thenReturn(
                        List.of(
                                activeTenant("AgentFail", "agentfail"),
                                activeTenant("WorkflowFail", "workflowfail"),
                                activeTenant("UserFail", "userfail")));

        when(agentRepository.count())
                .thenAnswer(
                        inv -> {
                            if ("agentfail".equals(schemaAtCallTime())) {
                                throw new RuntimeException("schema query failed");
                            }
                            return 3L;
                        });
        when(workflowDefinitionRepository.count())
                .thenAnswer(
                        inv -> {
                            if ("workflowfail".equals(schemaAtCallTime())) {
                                throw new RuntimeException("schema query failed");
                            }
                            return 5L;
                        });
        when(keycloakAdminClient.listUsersGroupedByTenant())
                .thenReturn(
                        Map.of(
                                "agentfail",
                                kcUsers("agentfail", 7),
                                "workflowfail",
                                kcUsers("workflowfail", 7)));

        List<PlatformTenantSummary> summaries = service.listTenantSummaries();

        assertEquals(3, summaries.size());
        // Failed count reads 0 but metricsComplete=false marks it as "couldn't
        // determine", never a fabricated zero - the other two stay real.
        PlatformTenantSummary agentFail = summaries.get(0);
        assertEquals(0L, agentFail.agentCount());
        assertEquals(5L, agentFail.workflowCount());
        assertEquals(7L, agentFail.userCount());
        assertFalse(agentFail.metricsComplete());

        PlatformTenantSummary workflowFail = summaries.get(1);
        assertEquals(3L, workflowFail.agentCount());
        assertEquals(0L, workflowFail.workflowCount());
        assertEquals(7L, workflowFail.userCount());
        assertFalse(workflowFail.metricsComplete());

        // A tenant with no Keycloak users is a real zero (the realm read succeeded).
        PlatformTenantSummary noUsers = summaries.get(2);
        assertEquals(3L, noUsers.agentCount());
        assertEquals(5L, noUsers.workflowCount());
        assertEquals(0L, noUsers.userCount());
        assertTrue(noUsers.metricsComplete());

        // The finally-clear must have run for every tenant, including the ones
        // whose lookups threw mid-iteration.
        assertTrue(TenantContext.current().isEmpty());
    }

    @Test
    void keycloakUsersAreReadOnceAndMatchedBySchemaNameNeverByTheTenantUuid() {
        Tenant acme = activeTenant("Acme", "acme");
        Tenant globex = activeTenant("Globex", "globex");
        when(tenantRepository.findRosterExcludingDemo()).thenReturn(List.of(acme, globex));
        when(keycloakAdminClient.listUsersGroupedByTenant())
                .thenReturn(Map.of("acme", kcUsers("acme", 4)));

        List<PlatformTenantSummary> summaries = service.listTenantSummaries();

        // One paged realm read for the whole roster - not one per tenant - and
        // grouped by the tenant_id attribute, which holds the SCHEMA NAME.
        verify(keycloakAdminClient).listUsersGroupedByTenant();
        verifyNoMoreInteractions(keycloakAdminClient);
        assertEquals(4L, summaries.get(0).userCount());
        assertEquals(0L, summaries.get(1).userCount());
        assertTrue(summaries.get(0).metricsComplete());
    }

    @Test
    void aKeycloakFailureFlagsEveryRowIncompleteWithoutBlankingSchemaCounts() {
        when(tenantRepository.findRosterExcludingDemo())
                .thenReturn(List.of(activeTenant("Acme", "acme")));
        when(keycloakAdminClient.listUsersGroupedByTenant())
                .thenThrow(new RuntimeException("keycloak down"));
        when(agentRepository.count()).thenReturn(2L);

        PlatformTenantSummary row = service.listTenantSummaries().get(0);

        assertFalse(row.metricsComplete());
        assertEquals(2L, row.agentCount());
    }

    @Test
    void newColumnsOwnerLastActivityAndSpendAreFilledFromTheirSources() {
        Tenant acme = mockTenant("Acme", "acme", TenantStatus.ACTIVE);
        UUID id = acme.getId();
        when(tenantRepository.findRosterExcludingDemo()).thenReturn(List.of(acme));
        when(keycloakAdminClient.listUsersGroupedByTenant()).thenReturn(Map.of());

        AppUser builder =
                appUser(id, "first@acme.io", UserRole.AGENT_BUILDER, "2026-01-01T00:00:00Z");
        AppUser laterAdmin =
                appUser(id, "admin2@acme.io", UserRole.TENANT_ADMIN, "2026-03-01T00:00:00Z");
        AppUser earlyAdmin =
                appUser(id, "admin1@acme.io", UserRole.TENANT_ADMIN, "2026-02-01T00:00:00Z");
        when(appUserRepository.findAll()).thenReturn(List.of(builder, laterAdmin, earlyAdmin));
        AppUserRepository.TenantLastSeen seen = mock(AppUserRepository.TenantLastSeen.class);
        when(seen.getTenantId()).thenReturn(id);
        when(seen.getLastSeenAt()).thenReturn(Instant.parse("2026-10-01T00:00:00Z"));
        when(appUserRepository.findLastSeenPerTenant()).thenReturn(List.of(seen));
        when(llmCallLogRepository.findLatestStartedAt())
                .thenReturn(Instant.parse("2026-10-05T00:00:00Z"));
        when(spendService.spendForTenants(eq(List.of(acme)), any()))
                .thenReturn(
                        Map.of(
                                id,
                                new PlatformSpendService.TenantSpendTotals(
                                        new BigDecimal("3.50"), new BigDecimal("0.50"), 42L)));

        PlatformTenantSummary row = service.listTenantSummaries().get(0);

        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), row.createdAt());
        assertEquals("ACTIVE", row.status());
        // Earliest TENANT_ADMIN wins over an earlier non-admin.
        assertEquals("admin1@acme.io", row.ownerEmail());
        // The later of last-seen and last LLM call.
        assertEquals(Instant.parse("2026-10-05T00:00:00Z"), row.lastActivityAt());
        assertEquals(0, new BigDecimal("3.50").compareTo(row.spend30dUsd()));
        assertEquals(0, new BigDecimal("0.50").compareTo(row.hostedSpend30dUsd()));
        assertEquals(42L, row.llmCalls30d());
    }

    @Test
    void failedSourcesAreNullNeverZero() {
        Tenant acme = mockTenant("Acme", "acme", TenantStatus.ACTIVE);
        when(tenantRepository.findRosterExcludingDemo()).thenReturn(List.of(acme));
        when(keycloakAdminClient.listUsersGroupedByTenant()).thenReturn(Map.of());
        when(appUserRepository.findAll()).thenThrow(new RuntimeException("db blip"));
        when(llmCallLogRepository.findLatestStartedAt())
                .thenThrow(new RuntimeException("schema gone"));
        when(spendService.spendForTenants(any(), any()))
                .thenThrow(new RuntimeException("rollup down"));

        PlatformTenantSummary row = service.listTenantSummaries().get(0);

        assertNull(row.ownerEmail());
        assertNull(row.lastActivityAt());
        assertNull(row.spend30dUsd());
        assertNull(row.hostedSpend30dUsd());
        assertNull(row.llmCalls30d());
    }

    @Test
    void provisioningTenantsAreListedWithoutSchemaLookupsOrSpend() {
        Tenant pending = mockTenant("Pending", "pending", TenantStatus.PROVISIONING_FAILED);
        when(tenantRepository.findRosterExcludingDemo()).thenReturn(List.of(pending));
        when(keycloakAdminClient.listUsersGroupedByTenant()).thenReturn(Map.of());

        PlatformTenantSummary row = service.listTenantSummaries().get(0);

        assertEquals("PROVISIONING_FAILED", row.status());
        verify(agentRepository, never()).count();
        verify(llmCallLogRepository, never()).findLatestStartedAt();
        verify(spendService).spendForTenants(eq(List.of()), any());
        assertNull(row.spend30dUsd());
    }

    @Test
    void ownerEmailFallsBackToTheEarliestUserWhenNoAdminExists() {
        UUID id = UUID.randomUUID();
        assertEquals(
                "a@x.io",
                PlatformTenantMetricsService.ownerEmail(
                        List.of(
                                appUser(
                                        id,
                                        "b@x.io",
                                        UserRole.AGENT_BUILDER,
                                        "2026-02-01T00:00:00Z"),
                                appUser(
                                        id,
                                        "a@x.io",
                                        UserRole.DELEGATED_USER,
                                        "2026-01-01T00:00:00Z"))));
        assertNull(PlatformTenantMetricsService.ownerEmail(List.of()));
        assertNull(PlatformTenantMetricsService.ownerEmail(null));
    }

    private static AppUser appUser(UUID tenantId, String email, UserRole role, String createdAt) {
        AppUser u = mock(AppUser.class);
        when(u.getTenantId()).thenReturn(tenantId);
        when(u.getEmail()).thenReturn(email);
        when(u.getRole()).thenReturn(role);
        when(u.getCreatedAt()).thenReturn(Instant.parse(createdAt));
        return u;
    }
}
