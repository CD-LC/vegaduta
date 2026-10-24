package com.agenticai.core.agents.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.job.dto.AgentJobStartRequest;
import com.agenticai.core.agents.runtime.AgentRuntimeSettings;
import com.agenticai.core.agents.runtime.AgentRuntime;
import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * A job that names no tool-call budget gets {@code maxTurns} times what ONE turn of ITS agent may make: the
 * agent's own runtime ceiling, not the platform's one number. (An explicit {@code maxToolCalls} is the
 * caller's.)
 */
class AgentJobToolBudgetFollowsAgentTest {

    private static final String TENANT = "t_budget";
    private AgentJobRepository repository;
    private AgentRuntime resolver;
    private AgentJobService service;
    private final UUID agentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(AgentJobRepository.class);
        when(repository.save(any(AgentJob.class))).thenAnswer(i -> i.getArgument(0));
        resolver = mock(AgentRuntime.class);
        service = new AgentJobService(
            repository, mock(AgentRepository.class), mock(AgentService.class), mock(AgentJobExecutor.class),
            new AgentJobProperties(), new SimpleMeterRegistry(), new ObjectMapper(), 15);
        TenantContext.set(TenantContext.of(TENANT, "alice", Set.of("ROLE_user")));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static AgentRuntimeSettings ceiling(int steps) {
        return new AgentRuntimeSettings(steps, Duration.ofSeconds(240), Duration.ZERO, 20_000, 3, 0.0, 8, Map.of());
    }

    private AgentJob submitted() {
        service.submit(agentId, new AgentJobStartRequest("do the thing", null, null, null));
        ArgumentCaptor<AgentJob> saved = ArgumentCaptor.forClass(AgentJob.class);
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        return saved.getAllValues().get(0);
    }

    @Test
    void withNoResolverTheDefaultIsTheGlobalPerTurnCeilingAsBefore() {
        assertEquals(15, submitted().getMaxToolCalls());
    }

    @Test
    void theDefaultIsMaxTurnsTimesTheAgentsOwnCeiling() {
        when(resolver.forAgent(agentId)).thenReturn(ceiling(40));
        service.setRuntimeSettings(resolver);

        assertEquals(40, submitted().getMaxToolCalls(), "max-turns is 1; the agent may make 40 calls per turn");
    }

    @Test
    void aResolverThatFailsLeavesTheGlobalDefault() {
        when(resolver.forAgent(agentId)).thenThrow(new IllegalStateException("db down"));
        service.setRuntimeSettings(resolver);

        assertEquals(15, submitted().getMaxToolCalls());
    }

    @Test
    void anExplicitBudgetIsTheCallersWhateverTheAgentAllows() {
        when(resolver.forAgent(agentId)).thenReturn(ceiling(40));
        service.setRuntimeSettings(resolver);

        service.submit(agentId, new AgentJobStartRequest("do the thing", null, null, 7));
        ArgumentCaptor<AgentJob> saved = ArgumentCaptor.forClass(AgentJob.class);
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        assertEquals(7, saved.getAllValues().get(0).getMaxToolCalls());
    }
}
