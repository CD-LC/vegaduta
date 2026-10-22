package com.agenticai.core.agents.job.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.job.AgentJob;
import com.agenticai.core.agents.job.AgentJobExecutor;
import com.agenticai.core.agents.job.AgentJobLaunch;
import com.agenticai.core.agents.job.AgentJobOrigin;
import com.agenticai.core.agents.job.AgentJobProperties;
import com.agenticai.core.agents.job.AgentJobRepository;
import com.agenticai.core.agents.job.AgentJobService;
import com.agenticai.core.agents.job.dto.AgentJobStartRequest;
import com.agenticai.core.security.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Which schema a job actually runs under, and WHEN a bad one is reported.
 *
 * <p>These close the other half of the shipped defect. Even once the execution path reads {@code
 * agent_job.result_schema_json}, an agent-level {@code result_schema_json} that no enqueue path
 * ever copied onto the row would leave the agent-level API write-only: an admin sets a schema
 * through {@code PUT /api/agents/{id}/result-schema}, every job of that agent quietly ignores it,
 * and nothing anywhere reports a problem.
 */
class AgentJobResultSchemaResolutionTest {

    private static final String AGENT_LEVEL_SCHEMA = StructuredOutputJobFixtures.SCHEMA;
    private static final String PER_JOB_SCHEMA =
            """
        { "type": "object", "properties": { "total": { "type": "number" } }, "required": ["total"] }
        """;

    private AgentJobRepository repository;
    private AgentRepository agentRepository;
    private AgentJobService service;
    private UUID agentId;

    @BeforeEach
    void setUp() {
        agentId = UUID.randomUUID();
        repository = mock(AgentJobRepository.class);
        when(repository.save(any(AgentJob.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        agentRepository = mock(AgentRepository.class);

        service =
                new AgentJobService(
                        repository,
                        agentRepository,
                        mock(AgentService.class),
                        mock(AgentJobExecutor.class),
                        new AgentJobProperties(),
                        new SimpleMeterRegistry(),
                        com.agenticai.core.json.JsonMappers.plain(),
                        15);

        TenantContext.set(
                TenantContext.of(
                        StructuredOutputJobFixtures.TENANT,
                        StructuredOutputJobFixtures.REQUESTED_BY,
                        Set.of("ROLE_user")));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void anApiJobWithNoSchemaOfItsOwnInheritsTheAgentsResultSchema() {
        givenAgentSchema(AGENT_LEVEL_SCHEMA);

        service.submit(
                agentId,
                new AgentJobStartRequest("Summarise yesterday's tickets", null, null, null));

        assertEquals(AGENT_LEVEL_SCHEMA, savedJob().getResultSchemaJson());
    }

    @Test
    void aPerJobSchemaWinsOverTheAgentLevelOne() {
        givenAgentSchema(AGENT_LEVEL_SCHEMA);

        service.submit(
                agentId,
                new AgentJobStartRequest("Total yesterday's invoices", null, PER_JOB_SCHEMA, null));

        assertEquals(PER_JOB_SCHEMA, savedJob().getResultSchemaJson());
    }

    /**
     * The non-HTTP path - a fired schedule or an event trigger. Before the agent-level fallback
     * reached it, this hard-coded {@code null}, so exactly the jobs nobody is watching (the
     * unattended ones) were the jobs that silently ignored their agent's schema.
     */
    @Test
    void aScheduledJobAlsoInheritsTheAgentsResultSchema() {
        givenAgentSchema(AGENT_LEVEL_SCHEMA);

        service.enqueue(
                new AgentJobLaunch(
                        agentId,
                        "Summarise yesterday's tickets",
                        AgentJobOrigin.SCHEDULE,
                        "sched-1",
                        UUID.randomUUID(),
                        Instant.now(),
                        StructuredOutputJobFixtures.REQUESTED_BY,
                        Set.of("ROLE_user"),
                        StructuredOutputJobFixtures.REQUESTED_BY,
                        null));

        assertEquals(AGENT_LEVEL_SCHEMA, savedJob().getResultSchemaJson());
    }

    @Test
    void anAgentWithNoSchemaProducesJobsWithNoSchema() {
        givenAgentSchema(null);

        service.submit(
                agentId,
                new AgentJobStartRequest("Summarise yesterday's tickets", null, null, null));

        assertNull(savedJob().getResultSchemaJson());
    }

    /**
     * An unusable schema must be a 400 the caller sees at submit, not a job accepted with a 202
     * that dies ten minutes later on a worker pointing at the agent instead of at the configuration
     * that actually broke.
     */
    @Test
    void anUnusableSchemaIsRejectedAtSubmitAndNoJobRowIsWritten() {
        givenAgentSchema(null);

        assertThrows(
                InvalidResultSchemaException.class,
                () ->
                        service.submit(
                                agentId,
                                new AgentJobStartRequest(
                                        "Do the thing", null, "{ not json at all", null)));

        verify(repository, never()).save(any(AgentJob.class));
    }

    /**
     * A remote {@code $ref} would have the validator fetch a tenant-supplied URL from inside the
     * cluster, with the platform's network position, at validation time. There is no legitimate use
     * for one in a result schema.
     */
    @Test
    void aRemoteRefSchemaIsRejectedAtSubmit() {
        givenAgentSchema(null);

        InvalidResultSchemaException rejected =
                assertThrows(
                        InvalidResultSchemaException.class,
                        () ->
                                service.submit(
                                        agentId,
                                        new AgentJobStartRequest(
                                                "Do the thing",
                                                null,
                                                "{\"type\":\"object\",\"properties\":{\"a\":{\"$ref\":\"http://169.254.169.254/latest\"}}}",
                                                null)));

        assertTrue(rejected.getMessage().contains("self-contained"));
    }

    /**
     * The enqueue path has no caller to hand a 400 to and runs inside a scheduler tick (and, once
     * event triggers land, inside a webhook's transaction). Throwing there would abort a whole
     * batch over one agent's bad configuration, so the job is enqueued and immediately failed - the
     * row exists, so the operator can SEE the reason instead of watching a schedule silently do
     * nothing.
     */
    @Test
    void anUnusableAgentSchemaFailsTheEnqueuedJobRatherThanThrowingIntoTheScheduler() {
        givenAgentSchema("{ not json at all");

        UUID jobId =
                service.enqueue(
                        new AgentJobLaunch(
                                agentId,
                                "Summarise yesterday's tickets",
                                AgentJobOrigin.SCHEDULE,
                                "sched-1",
                                UUID.randomUUID(),
                                Instant.now(),
                                StructuredOutputJobFixtures.REQUESTED_BY,
                                Set.of("ROLE_user"),
                                StructuredOutputJobFixtures.REQUESTED_BY,
                                null));

        verify(repository)
                .failIfStillQueued(
                        eq(jobId), eq("SCHEMA_VIOLATION"), anyString(), any(Instant.class));
    }

    // --------------------------------------------------------------- internals

    private void givenAgentSchema(String schemaJson) {
        Agent agent = mock(Agent.class);
        when(agent.getResultSchemaJson()).thenReturn(schemaJson);
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));
    }

    private AgentJob savedJob() {
        ArgumentCaptor<AgentJob> saved = ArgumentCaptor.forClass(AgentJob.class);
        verify(repository).save(saved.capture());
        return saved.getValue();
    }
}
