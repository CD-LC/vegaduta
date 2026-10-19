package com.agenticai.core.platform.fleet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.platform.fleet.FleetHealthService.AgentHealth;
import com.agenticai.core.platform.fleet.FleetHealthService.FleetHealthResponse;
import com.agenticai.core.platform.fleet.FleetHealthService.FleetSlo;
import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * FleetHealthService with the database mocked at the native-query boundary -
 * what is under test is the AGGREGATION and ALERT DERIVATION: windowed error
 * rates, job success SLOs, eval-score drift between the two latest runs,
 * spend spikes against the 7-day baseline, and the honest-absence rules (no
 * traffic is not a breach; one eval run is not drift; an unreadable tenant
 * schema reports as unavailable rather than as zeros).
 */
class FleetHealthServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID AGENT_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID AGENT_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");

    private TenantRepository tenantRepository;
    private EntityManager entityManager;
    private FleetHealthService service;

    private Query agentQuery;
    private Query llmQuery;
    private Query jobQuery;
    private Query evalQuery;

    @BeforeEach
    void setUp() {
        tenantRepository = mock(TenantRepository.class);
        entityManager = mock(EntityManager.class);
        service = new FleetHealthService(tenantRepository);
        ReflectionTestUtils.setField(service, "entityManager", entityManager);

        agentQuery = routedQuery();
        llmQuery = routedQuery();
        jobQuery = routedQuery();
        evalQuery = routedQuery();

        when(entityManager.createNativeQuery(anyString())).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            if (sql.contains("FROM llm_call_log")) {
                return llmQuery;
            }
            if (sql.contains("FROM agent_job")) {
                return jobQuery;
            }
            if (sql.contains("FROM eval_run")) {
                return evalQuery;
            }
            if (sql.contains("FROM agent")) {
                return agentQuery;
            }
            throw new IllegalArgumentException("unexpected native SQL in test: " + sql);
        });
    }

    @AfterEach
    void clearTenantContext() {
        com.agenticai.core.security.TenantContext.clear();
    }

    private static Query routedQuery() {
        Query q = mock(Query.class);
        when(q.setParameter(anyString(), any())).thenReturn(q);
        when(q.getResultList()).thenReturn(List.of());
        return q;
    }

    private static Tenant tenant(boolean stopped) {
        Tenant t = mock(Tenant.class);
        when(t.getId()).thenReturn(TENANT_ID);
        when(t.getName()).thenReturn("Acme");
        when(t.getSchemaName()).thenReturn("t_acme");
        when(t.isEmergencyStopped()).thenReturn(stopped);
        return t;
    }

    private void oneTenant(boolean stopped) {
        Tenant t = tenant(stopped);
        when(tenantRepository.findActiveExcludingDemo()).thenReturn(List.of(t));
        when(agentQuery.getResultList()).thenReturn(List.<Object[]>of(
            new Object[]{AGENT_A, "Support bot"}, new Object[]{AGENT_B, "Sales bot"}));
    }

    /** {agentId, calls_w, failed_w, cost_w, tokens_w, daily_baseline_cost, last_call_at} */
    private void llmRows(Object[]... rows) {
        when(llmQuery.getResultList()).thenReturn(List.of(rows));
    }

    /** {agentId, finished_w, ok_w, failed_w} */
    private void jobRows(Object[]... rows) {
        when(jobQuery.getResultList()).thenReturn(List.of(rows));
    }

    /** {agentId, verdict, overall_score, finished_at} - newest first per agent. */
    private void evalRows(Object[]... rows) {
        when(evalQuery.getResultList()).thenReturn(List.of(rows));
    }

    private AgentHealth row(FleetHealthResponse r, UUID agentId) {
        return r.agents().stream().filter(a -> a.agentId().equals(agentId)).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------

    @Test
    void healthyAgentReportsOkWithPopulatedStats() {
        oneTenant(false);
        llmRows(new Object[]{AGENT_A, 100L, 2L, new BigDecimal("3.50"), 50_000L,
            new BigDecimal("2.00"), Timestamp.from(NOW)});
        jobRows(new Object[]{AGENT_A, 10L, 10L, 0L});
        evalRows(new Object[]{AGENT_A, "PASSED", new BigDecimal("0.84"), Timestamp.from(NOW)},
            new Object[]{AGENT_A, "PASSED", new BigDecimal("0.80"), Timestamp.from(NOW.minusSeconds(86400))});

        FleetHealthResponse r = service.health(FleetSlo.defaults(24));

        AgentHealth a = row(r, AGENT_A);
        assertEquals("OK", a.health());
        assertTrue(a.alerts().isEmpty());
        assertEquals(100, a.calls());
        assertEquals(0.02, a.errorRate(), 1e-9);
        assertEquals(1.0, a.jobSuccessRate(), 1e-9);
        assertEquals(0.84, a.latestEvalScore(), 1e-9);
        assertEquals(0.80, a.previousEvalScore(), 1e-9);
        // Agent B exists but has no traffic - honest absence, not zeros-as-health.
        AgentHealth b = row(r, AGENT_B);
        assertEquals("OK", b.health());
        assertNull(b.errorRate());
        assertNull(b.jobSuccessRate());
    }

    @Test
    void errorRateAboveSloBreachesOnlyWithEnoughTraffic() {
        oneTenant(false);
        // AGENT_A: 8 calls, 3 failed -> 37.5% > 10% SLO, over the 5-call floor.
        // AGENT_B: 2 calls, 2 failed -> 100% but under the floor: too thin to
        // page an operator over.
        llmRows(new Object[]{AGENT_A, 8L, 3L, new BigDecimal("0.10"), 4_000L,
            new BigDecimal("0.05"), Timestamp.from(NOW)},
            new Object[]{AGENT_B, 2L, 2L, new BigDecimal("0.02"), 1_000L,
            new BigDecimal("0.01"), Timestamp.from(NOW)});

        FleetHealthResponse r = service.health(FleetSlo.defaults(24));

        AgentHealth a = row(r, AGENT_A);
        assertEquals("BREACH", a.health());
        assertTrue(a.alerts().contains(FleetHealthService.ALERT_ERROR_RATE_BREACH));
        AgentHealth b = row(r, AGENT_B);
        assertEquals("OK", b.health());
        assertTrue(b.alerts().isEmpty());
    }

    @Test
    void evalScoreRegressionBetweenLatestTwoRunsWarns() {
        oneTenant(false);
        evalRows(new Object[]{AGENT_A, "PASSED", new BigDecimal("0.70"), Timestamp.from(NOW)},
            new Object[]{AGENT_A, "PASSED", new BigDecimal("0.85"), Timestamp.from(NOW.minusSeconds(86400))});

        AgentHealth a = row(service.health(FleetSlo.defaults(24)), AGENT_A);
        assertEquals("WARN", a.health());
        assertTrue(a.alerts().contains(FleetHealthService.ALERT_EVAL_REGRESSION));
    }

    @Test
    void latestEvalFailedIsABreachNotAWarn() {
        oneTenant(false);
        evalRows(new Object[]{AGENT_A, "FAILED", new BigDecimal("0.40"), Timestamp.from(NOW)},
            new Object[]{AGENT_A, "PASSED", new BigDecimal("0.85"), Timestamp.from(NOW.minusSeconds(86400))});

        AgentHealth a = row(service.health(FleetSlo.defaults(24)), AGENT_A);
        assertEquals("BREACH", a.health());
        assertTrue(a.alerts().contains(FleetHealthService.ALERT_EVAL_FAILED));
        // A FAILED latest is also a regression - both alerts are honest.
        assertTrue(a.alerts().contains(FleetHealthService.ALERT_EVAL_REGRESSION));
    }

    @Test
    void singleEvalRunYieldsNoDriftVerdict() {
        oneTenant(false);
        evalRows(new Object[]{AGENT_A, "PASSED", new BigDecimal("0.50"), Timestamp.from(NOW)});

        AgentHealth a = row(service.health(FleetSlo.defaults(24)), AGENT_A);
        assertEquals("OK", a.health());
        assertNull(a.previousEvalScore());
    }

    @Test
    void spendSpikeAgainstBaselineWarns() {
        oneTenant(false);
        // $12 today vs $2/day baseline -> 6x, over the 3x ratio and the $1 floor.
        llmRows(new Object[]{AGENT_A, 30L, 0L, new BigDecimal("12.00"), 90_000L,
            new BigDecimal("2.00"), Timestamp.from(NOW)});

        AgentHealth a = row(service.health(FleetSlo.defaults(24)), AGENT_A);
        assertEquals("WARN", a.health());
        assertTrue(a.alerts().contains(FleetHealthService.ALERT_SPEND_SPIKE));
        assertEquals(6.0, a.spendRatio(), 1e-9);
    }

    @Test
    void jobSuccessBelowSloBreaches() {
        oneTenant(false);
        jobRows(new Object[]{AGENT_A, 10L, 6L, 4L});

        AgentHealth a = row(service.health(FleetSlo.defaults(24)), AGENT_A);
        assertEquals("BREACH", a.health());
        assertTrue(a.alerts().contains(FleetHealthService.ALERT_JOB_SUCCESS_BREACH));
        assertEquals(0.6, a.jobSuccessRate(), 1e-9);
    }

    @Test
    void emergencyStoppedTenantReadsStopped() {
        oneTenant(true);
        FleetHealthResponse r = service.health(FleetSlo.defaults(24));

        AgentHealth a = row(r, AGENT_A);
        assertEquals("STOPPED", a.health());
        assertTrue(a.tenantEmergencyStopped());
        assertTrue(a.alerts().contains(FleetHealthService.ALERT_EMERGENCY_STOPPED));
    }

    @Test
    void unreadableTenantSchemaReportsUnavailableAndKeepsTheRest() {
        Tenant good = tenant(false);
        Tenant bad = mock(Tenant.class);
        when(bad.getName()).thenReturn("Broken");
        when(bad.getSchemaName()).thenReturn("t_broken");
        when(bad.getId()).thenReturn(UUID.randomUUID());
        when(tenantRepository.findActiveExcludingDemo()).thenReturn(List.of(bad, good));
        when(agentQuery.getResultList()).thenReturn(List.<Object[]>of(new Object[]{AGENT_A, "Support bot"}));

        // First schema call (bad tenant) throws; the second (good) proceeds.
        // Routing is by call order on the AGENT query - the first query each
        // tenant issues - so arm a one-shot failure then the normal answer.
        // doAnswer (not when().thenAnswer()): re-stubbing with when() would
        // invoke the setUp answer with the matcher's empty-string arg.
        org.mockito.Mockito.doAnswer(new org.mockito.stubbing.Answer<Query>() {
            private boolean firstAgentQuery = true;
            @Override
            public Query answer(org.mockito.invocation.InvocationOnMock inv) {
                String sql = inv.getArgument(0);
                if (sql.contains("FROM agent") && firstAgentQuery) {
                    firstAgentQuery = false;
                    throw new RuntimeException("schema t_broken does not exist");
                }
                if (sql.contains("FROM llm_call_log")) {
                    return llmQuery;
                }
                if (sql.contains("FROM agent_job")) {
                    return jobQuery;
                }
                if (sql.contains("FROM eval_run")) {
                    return evalQuery;
                }
                if (sql.contains("FROM agent")) {
                    return agentQuery;
                }
                throw new IllegalArgumentException("unexpected native SQL in test: " + sql);
            }
        }).when(entityManager).createNativeQuery(anyString());

        FleetHealthResponse r = service.health(FleetSlo.defaults(24));

        assertEquals(List.of("Broken"), r.unavailableTenants());
        // The healthy tenant still reported; one bad schema did not blank the fleet.
        assertEquals(1, r.agents().size());
        assertEquals(AGENT_A, r.agents().get(0).agentId());
    }
}
