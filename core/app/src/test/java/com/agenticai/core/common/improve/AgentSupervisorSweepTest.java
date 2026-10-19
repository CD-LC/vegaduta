package com.agenticai.core.common.improve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link AgentSupervisorSweep} is the proactive-scheduling wrapper around two
 * pieces of logic that already have their own tests/safety posture -
 * {@code AgentImprovementService#generateForAgent} and {@code
 * AgentHealthScoreService#recompute}. These tests therefore stay at this
 * seam: does the sweep call both, once per agent, per active tenant, and
 * does one agent's failure stay contained to that agent instead of losing
 * the rest of the tenant's batch - the same per-tenant/per-item isolation
 * contract {@code ChannelSessionResolutionSweep} and {@code
 * WorkflowRunRecoveryJob} already carry for their own sweeps.
 *
 * <p>{@link TenantSchemaIterator} is mocked with a {@code doAnswer} that
 * immediately invokes the given action with one fixed schema name - same
 * convention as {@code WorkflowRunRecoveryJobTest}; multi-tenant iteration
 * itself is that class's own separately-tested concern, not this sweep's.
 */
class AgentSupervisorSweepTest {

    private static final String SCHEMA = "tenant_test";

    private TenantSchemaIterator schemaIterator;
    private PgAdvisoryJobLock jobLock;
    private AgentRepository agentRepository;
    private AgentImprovementService improvementService;
    private AgentHealthScoreService healthScoreService;

    private static Agent agentWithId(UUID id, String name) {
        Agent agent = new Agent(
            name, "You are a helpful agent.", ProviderName.ANTHROPIC, "claude-sonnet-5",
            1024, 0.7, 1.0, false, ExperienceMode.ADVANCED);
        ReflectionTestUtils.setField(agent, "id", id);
        return agent;
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        schemaIterator = mock(TenantSchemaIterator.class);
        jobLock = mock(PgAdvisoryJobLock.class);
        agentRepository = mock(AgentRepository.class);
        improvementService = mock(AgentImprovementService.class);
        healthScoreService = mock(AgentHealthScoreService.class);

        // The lock always wins in these tests - PgAdvisoryJobLock's own
        // acquire/release/fail-open behaviour is covered by PgAdvisoryJobLockTest.
        doAnswer(invocation -> {
            Runnable job = invocation.getArgument(1);
            job.run();
            return true;
        }).when(jobLock).tryRunExclusive(any(), any());

        doAnswer(invocation -> {
            Consumer<String> action = invocation.getArgument(0);
            action.accept(SCHEMA);
            return null;
        }).when(schemaIterator).forEachActiveTenantSchema(any());
    }

    private AgentSupervisorSweep sweepWith(boolean enabled) {
        return new AgentSupervisorSweep(
            schemaIterator, jobLock, agentRepository, improvementService, healthScoreService, enabled);
    }

    @Test
    void disabledSweepNeverAcquiresTheLockOrTouchesAnyAgent() {
        AgentSupervisorSweep sweep = sweepWith(false);

        sweep.sweep();

        verify(jobLock, never()).tryRunExclusive(any(), any());
        verify(agentRepository, never()).findAll();
    }

    @Test
    void enabledSweepGeneratesSuggestionsAndRecomputesHealthForEveryAgent() {
        UUID agentA = UUID.randomUUID();
        UUID agentB = UUID.randomUUID();
        when(agentRepository.findAll()).thenReturn(List.of(
            agentWithId(agentA, "Agent A"), agentWithId(agentB, "Agent B")));

        AgentSupervisorSweep sweep = sweepWith(true);
        sweep.sweep();

        verify(improvementService).generateForAgent(agentA, true);
        verify(improvementService).generateForAgent(agentB, true);
        verify(healthScoreService).recompute(eq(agentA), any());
        verify(healthScoreService).recompute(eq(agentB), any());
    }

    @Test
    void oneAgentsSuggestionGenerationFailureNeverStopsTheRestOfTheTenantsAgents() {
        UUID failingAgent = UUID.randomUUID();
        UUID healthyAgent = UUID.randomUUID();
        when(agentRepository.findAll()).thenReturn(List.of(
            agentWithId(failingAgent, "Failing Agent"), agentWithId(healthyAgent, "Healthy Agent")));
        doThrow(new RuntimeException("LLM distillation blew up"))
            .when(improvementService).generateForAgent(failingAgent, true);

        AgentSupervisorSweep sweep = sweepWith(true);
        sweep.sweep();

        // The failing agent never reaches the health recompute step for this tick...
        verify(healthScoreService, never()).recompute(eq(failingAgent), any());
        // ...but the next agent in the same tenant is still swept end to end.
        verify(improvementService).generateForAgent(healthyAgent, true);
        verify(healthScoreService).recompute(eq(healthyAgent), any());
    }

    @Test
    void sweepTenantReturnsTheCountOfSuccessfullySweptAgents() {
        UUID failingAgent = UUID.randomUUID();
        UUID healthyAgent = UUID.randomUUID();
        when(agentRepository.findAll()).thenReturn(List.of(
            agentWithId(failingAgent, "Failing Agent"), agentWithId(healthyAgent, "Healthy Agent")));
        doThrow(new RuntimeException("boom")).when(improvementService).generateForAgent(failingAgent, true);

        AgentSupervisorSweep sweep = sweepWith(true);

        assertEquals(1, sweep.sweepTenant());
    }

    @Test
    void noAgentsInTheTenantIsANoOpNotAFailure() {
        when(agentRepository.findAll()).thenReturn(List.of());

        AgentSupervisorSweep sweep = sweepWith(true);

        assertEquals(0, sweep.sweepTenant());
        verify(improvementService, never()).generateForAgent(any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    /**
     * Prod 2026-10-05 (R1): 24 failed AI calls a day, for two weeks, from this sweep calling a provider key that
     * was refused. Once the provider is cooling down no further agent of the tenant asks it; health scores still run.
     */
    @Test
    void aProviderThatIsCoolingDownIsAskedOncePerTenantNotOncePerAgent() {
        UUID agentA = UUID.randomUUID();
        UUID agentB = UUID.randomUUID();
        UUID agentC = UUID.randomUUID();
        when(agentRepository.findAll()).thenReturn(List.of(
            agentWithId(agentA, "A"), agentWithId(agentB, "B"), agentWithId(agentC, "C")));
        doThrow(new com.agenticai.core.providers.ProviderCoolingDownException(
            ProviderName.GOOGLE_VERTEX, java.time.Instant.now(), java.time.Duration.ofHours(6)))
            .when(improvementService).generateForAgent(agentA, true);

        AgentSupervisorSweep sweep = sweepWith(true);

        assertEquals(3, sweep.sweepTenant());
        verify(improvementService).generateForAgent(agentA, true);
        verify(improvementService, never()).generateForAgent(agentB, true);
        verify(improvementService, never()).generateForAgent(agentC, true);
        verify(healthScoreService).recompute(eq(agentA), any());
        verify(healthScoreService).recompute(eq(agentB), any());
        verify(healthScoreService).recompute(eq(agentC), any());
    }
}
