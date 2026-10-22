package com.agenticai.core.agents.research;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.capability.AgentCapabilityState;
import com.agenticai.core.agents.capability.AgentCapabilityStateId;
import com.agenticai.core.agents.capability.AgentCapabilityStateRepository;
import com.agenticai.core.agents.capability.DeepResearchCapability;
import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.dto.AgentCreateRequest;
import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.agents.job.AgentJob;
import com.agenticai.core.agents.job.AgentJobNotFoundException;
import com.agenticai.core.agents.job.AgentJobOrigin;
import com.agenticai.core.agents.job.AgentJobService;
import com.agenticai.core.agents.job.dto.AgentJobResponse;
import com.agenticai.core.agents.job.dto.AgentJobStartRequest;
import com.agenticai.core.agents.research.dto.ResearchRunStartRequest;
import com.agenticai.core.agents.research.dto.ResearchStatusResponse;
import com.agenticai.core.providers.PlatformToolCredentialService;
import com.agenticai.core.providers.PlatformToolKey;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

/**
 * The research-run facade: availability gates, the daily cap, the per-user research agent
 * (find-or-create, idempotent) and what is handed to the job service. Plain mocks, no Spring.
 */
class ResearchRunServiceTest {

    private static final String TENANT = "t_test";
    private static final String ALICE = "alice-sub";

    private ResearchRunUsageService usage;
    private AgentService agentService;
    private AgentRepository agentRepository;
    private AgentCapabilityStateRepository capabilityStates;
    private AgentJobService jobService;
    private PlatformToolCredentialService credentials;
    private ObjectProvider<PlatformToolCredentialService> credentialProvider;
    private ProviderCredentialResolver providerResolver;
    private ObjectProvider<ProviderCredentialResolver> providerResolverProvider;
    private TenantLimits tenantLimits;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        usage = mock(ResearchRunUsageService.class);
        agentService = mock(AgentService.class);
        agentRepository = mock(AgentRepository.class);
        capabilityStates = mock(AgentCapabilityStateRepository.class);
        jobService = mock(AgentJobService.class);
        credentials = mock(PlatformToolCredentialService.class);
        credentialProvider = mock(ObjectProvider.class);
        when(credentialProvider.getIfAvailable()).thenReturn(credentials);
        when(credentials.resolveActiveApiKey(PlatformToolKey.BRAVE_SEARCH))
                .thenReturn(Optional.of("k"));
        providerResolver = mock(ProviderCredentialResolver.class);
        providerResolverProvider = mock(ObjectProvider.class);
        when(providerResolverProvider.getIfAvailable()).thenReturn(providerResolver);
        tenantLimits = mock(TenantLimits.class);
        when(tenantLimits.plan(TENANT)).thenReturn(TenantPlan.FREE);
        when(capabilityStates.findById(any())).thenReturn(Optional.empty());
        when(agentRepository.findByCreatedBy(anyString())).thenReturn(List.of());
        TenantContext.set(TenantContext.of(TENANT, ALICE, Set.of("ROLE_user")));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private ResearchRunService service(boolean runsEnabled, boolean deepEnabled) {
        return new ResearchRunService(
                ResearchRunUsageServiceTest.properties(runsEnabled, deepEnabled, 5),
                usage,
                agentService,
                agentRepository,
                capabilityStates,
                jobService,
                credentialProvider,
                providerResolverProvider,
                tenantLimits);
    }

    private static ResearchRunStartRequest question(String q) {
        return new ResearchRunStartRequest(q, null, null, null);
    }

    /** An agent owned by the caller; {@code marked} = its deep-research capability row is on. */
    private Agent researchAgent(UUID id, String name, boolean marked, Instant createdAt) {
        Agent agent = mock(Agent.class);
        when(agent.getId()).thenReturn(id);
        when(agent.getName()).thenReturn(name);
        when(agent.getCreatedAt()).thenReturn(createdAt);
        if (marked) {
            when(capabilityStates.findById(
                            new AgentCapabilityStateId(id, DeepResearchCapability.CAPABILITY_KEY)))
                    .thenReturn(
                            Optional.of(
                                    new AgentCapabilityState(
                                            id, DeepResearchCapability.CAPABILITY_KEY, true)));
        }
        return agent;
    }

    private static AgentJobResponse queuedJob(UUID agentId) {
        AgentJob job =
                AgentJob.queued(
                        UUID.randomUUID(),
                        TENANT,
                        agentId,
                        "goal",
                        ALICE,
                        ALICE,
                        Set.of("ROLE_user"),
                        AgentJobOrigin.API,
                        null,
                        600,
                        1,
                        15,
                        ResearchRunPlan.RESULT_SCHEMA);
        return AgentJobResponse.from(job, com.agenticai.core.json.JsonMappers.plain());
    }

    private static int statusOf(ResponseStatusException e) {
        return e.getStatusCode().value();
    }

    // ------------------------------------------------------------ availability

    @Test
    void statusAlwaysAnswersAndReportsEveryGateAndLimit() {
        when(usage.remainingToday(TENANT, ALICE)).thenReturn(4);
        ResearchStatusResponse off = service(false, false).status();
        assertFalse(off.enabled());
        assertFalse(off.runsEnabled());
        assertTrue(off.searchConfigured());

        ResearchStatusResponse on = service(true, true).status();
        assertTrue(on.enabled());
        assertTrue(on.runsEnabled());
        assertEquals(12, on.limits().maxSearchesPerRun());
        assertEquals(12, on.limits().maxFetchesPerRun());
        assertEquals(8, on.limits().maxSources());
        assertEquals(120, on.limits().totalDeadlineSeconds());
        assertEquals(5, on.limits().maxRunsPerUserPerDay());
        assertEquals(600, on.limits().runTimeoutSeconds());
        assertEquals(0, on.limits().runBudgetUsd().compareTo(new java.math.BigDecimal("2.0")));
        assertEquals(Integer.valueOf(4), on.remainingToday());

        when(credentials.resolveActiveApiKey(PlatformToolKey.BRAVE_SEARCH))
                .thenReturn(Optional.empty());
        assertFalse(service(true, true).status().searchConfigured());
    }

    @Test
    void everyRunEndpointIs404WhileTheRunsFlagIsOff() {
        ResearchRunService off = service(false, true);
        UUID jobId = UUID.randomUUID();
        assertEquals(
                404,
                statusOf(
                        assertThrows(
                                ResponseStatusException.class, () -> off.start(question("q")))));
        assertEquals(
                404, statusOf(assertThrows(ResponseStatusException.class, () -> off.list(50, 0))));
        assertEquals(
                404, statusOf(assertThrows(ResponseStatusException.class, () -> off.get(jobId))));
        assertEquals(
                404,
                statusOf(assertThrows(ResponseStatusException.class, () -> off.cancel(jobId))));
        verifyNoInteractions(jobService, agentService, usage);
    }

    @Test
    void startIs503WithARelayableMessageWhenDeepResearchIsOffOrTheSearchKeyIsMissing() {
        ResponseStatusException deepOff =
                assertThrows(
                        ResponseStatusException.class,
                        () -> service(true, false).start(question("q")));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE.value(), statusOf(deepOff));
        assertEquals(ResearchRunService.MSG_DEEP_OFF, deepOff.getReason());

        when(credentials.resolveActiveApiKey(PlatformToolKey.BRAVE_SEARCH))
                .thenReturn(Optional.empty());
        ResponseStatusException noKey =
                assertThrows(
                        ResponseStatusException.class,
                        () -> service(true, true).start(question("q")));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE.value(), statusOf(noKey));
        assertEquals(ResearchRunService.MSG_NO_SEARCH_KEY, noKey.getReason());
        verifyNoInteractions(jobService, agentService, usage);
    }

    @Test
    void anInvalidRequestIsA400BeforeAnythingIsCreatedOrCounted() {
        assertThrows(
                IllegalArgumentException.class, () -> service(true, true).start(question("  ")));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service(true, true)
                                .start(new ResearchRunStartRequest("q", "huge", null, null)));
        verifyNoInteractions(jobService, agentService, usage);
    }

    // ------------------------------------------------------------- happy path

    @Test
    void firstRunCreatesTheCallersResearchAgentThenSubmitsTheJobWithTheFixedSchema() {
        UUID agentId = UUID.randomUUID();
        AgentResponse created = mock(AgentResponse.class);
        when(created.id()).thenReturn(agentId);
        when(agentService.create(any())).thenReturn(created);
        AgentJobResponse queued = queuedJob(agentId);
        when(jobService.submit(eq(agentId), any(), eq(true))).thenReturn(queued);

        AgentJobResponse result =
                service(true, true)
                        .start(
                                new ResearchRunStartRequest(
                                        "Compare A and B",
                                        "comparison",
                                        List.of("https://a.example"),
                                        null));
        assertSame(queued, result);

        ArgumentCaptor<AgentCreateRequest> create =
                ArgumentCaptor.forClass(AgentCreateRequest.class);
        verify(agentService).create(create.capture());
        assertEquals("Deep Research", create.getValue().name());
        assertEquals(ResearchRunPlan.SYSTEM_PROMPT, create.getValue().systemPrompt());
        assertEquals(ProviderName.PLATFORM_HOSTED, create.getValue().providerName());
        assertEquals(ExperienceMode.ADVANCED, create.getValue().experienceMode());
        assertEquals(Boolean.FALSE, create.getValue().knowledgeBaseEnabled());
        // The whole report plus every source travels in one JSON answer: the default 4096 would
        // truncate it.
        assertEquals(Integer.valueOf(8192), create.getValue().maxTokens());

        // Allow-list first, capability marker last, and only then the cap and the job.
        InOrder order = inOrder(agentService, usage, jobService);
        order.verify(agentService).setAllowedToolNames(agentId, ResearchRunPlan.AGENT_TOOLS);
        order.verify(agentService).setCapabilityEnabled(agentId, "deep-research", true);
        order.verify(usage).reserve(TENANT, ALICE);
        ArgumentCaptor<AgentJobStartRequest> job =
                ArgumentCaptor.forClass(AgentJobStartRequest.class);
        order.verify(jobService).submit(eq(agentId), job.capture(), eq(true));
        assertTrue(job.getValue().goal().contains("Compare A and B"));
        assertTrue(job.getValue().goal().startsWith(ResearchRunPlan.GOAL_PREFIX));
        assertTrue(job.getValue().goal().contains("Depth (pass as effort): comparison"));
        assertTrue(job.getValue().goal().contains("- https://a.example"));
        assertEquals(Integer.valueOf(600), job.getValue().timeoutSeconds());
        assertEquals(ResearchRunPlan.RESULT_SCHEMA, job.getValue().resultSchemaJson());
        assertNull(job.getValue().maxToolCalls());
        verify(usage, never()).release(anyString(), anyString());
    }

    @Test
    void aSecondRunReusesTheExistingAgentAndPicksTheOldestWhenThereAreTwo() {
        UUID older = UUID.randomUUID();
        UUID newer = UUID.randomUUID();
        Agent unrelated =
                researchAgent(
                        UUID.randomUUID(),
                        "Support bot",
                        true,
                        Instant.parse("2026-01-01T00:00:00Z"));
        Agent sameNameNoMarker =
                researchAgent(
                        UUID.randomUUID(),
                        "Deep Research",
                        false,
                        Instant.parse("2026-01-02T00:00:00Z"));
        Agent newerAgent =
                researchAgent(newer, "Deep Research", true, Instant.parse("2026-03-01T00:00:00Z"));
        Agent olderAgent =
                researchAgent(older, "Deep Research", true, Instant.parse("2026-02-01T00:00:00Z"));
        when(agentRepository.findByCreatedBy(ALICE))
                .thenReturn(List.of(unrelated, sameNameNoMarker, newerAgent, olderAgent));
        when(jobService.submit(eq(older), any(), eq(true))).thenReturn(queuedJob(older));

        service(true, true).start(question("q"));
        service(true, true).start(question("q again"));

        verify(agentService, never()).create(any());
        verify(jobService, times(2)).submit(eq(older), any(), eq(true));
        verify(jobService, never()).submit(eq(newer), any(), eq(true));
    }

    @Test
    void paidTenantUsesItsOwnActiveProviderAndFallsBackToTheHostedPool() {
        when(tenantLimits.plan(TENANT)).thenReturn(TenantPlan.PREMIUM);
        when(providerResolver.resolveAnyActiveKey())
                .thenReturn(
                        new ProviderCredentialResolver.ActiveProvider(ProviderName.OPENAI, "sk"));
        AgentResponse created = mock(AgentResponse.class);
        UUID agentId = UUID.randomUUID();
        when(created.id()).thenReturn(agentId);
        when(agentService.create(any())).thenReturn(created);
        when(jobService.submit(eq(agentId), any(), eq(true))).thenReturn(queuedJob(agentId));

        service(true, true).start(question("q"));
        when(providerResolver.resolveAnyActiveKey())
                .thenThrow(new IllegalStateException("no active provider"));
        service(true, true).start(question("q"));

        ArgumentCaptor<AgentCreateRequest> create =
                ArgumentCaptor.forClass(AgentCreateRequest.class);
        verify(agentService, times(2)).create(create.capture());
        assertEquals(ProviderName.OPENAI, create.getAllValues().get(0).providerName());
        assertEquals(ProviderName.PLATFORM_HOSTED, create.getAllValues().get(1).providerName());
    }

    // ---------------------------------------------------------------- failures

    @Test
    void dailyCap429StopsTheRunBeforeAnyJobIsSubmitted() {
        UUID agentId = UUID.randomUUID();
        // Built first: stubbing a mock inside another stubbing's thenReturn(...) is a Mockito
        // misuse.
        Agent mine = researchAgent(agentId, "Deep Research", true, Instant.now());
        when(agentRepository.findByCreatedBy(ALICE)).thenReturn(List.of(mine));
        doThrow(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "cap"))
                .when(usage)
                .reserve(TENANT, ALICE);

        ResponseStatusException refused =
                assertThrows(
                        ResponseStatusException.class,
                        () -> service(true, true).start(question("q")));
        assertEquals(429, statusOf(refused));
        verify(jobService, never()).submit(any(), any());
        verify(usage, never()).release(anyString(), anyString());
    }

    @Test
    void aFailedSubmissionGivesTheDailySlotBack() {
        UUID agentId = UUID.randomUUID();
        // Built first: stubbing a mock inside another stubbing's thenReturn(...) is a Mockito
        // misuse.
        Agent mine = researchAgent(agentId, "Deep Research", true, Instant.now());
        when(agentRepository.findByCreatedBy(ALICE)).thenReturn(List.of(mine));
        when(jobService.submit(eq(agentId), any(), eq(true)))
                .thenThrow(new IllegalStateException("db down"));

        assertThrows(IllegalStateException.class, () -> service(true, true).start(question("q")));
        verify(usage).reserve(TENANT, ALICE);
        verify(usage).release(TENANT, ALICE);
    }

    @Test
    void agentCeilingSurfacesAsThePlanLimitAndCountsNothing() {
        when(agentService.create(any()))
                .thenThrow(new TenantLimitExceededException("This plan allows up to 3 agents"));
        assertThrows(
                TenantLimitExceededException.class, () -> service(true, true).start(question("q")));
        verifyNoInteractions(usage, jobService);
    }

    @Test
    void aFullWorkspaceGetsAResearchSpecificRefusalThatNeverTellsAFreeUserToDelete() {
        when(tenantLimits.maxAgents(TENANT)).thenReturn(Optional.of(5));
        when(agentRepository.count()).thenReturn(5L);

        TenantLimitExceededException free =
                assertThrows(
                        TenantLimitExceededException.class,
                        () -> service(true, true).start(question("q")));
        assertTrue(free.getMessage().contains("Research needs one agent slot"));
        assertTrue(free.getMessage().endsWith(ResearchRunService.MSG_SLOT_FREE_PLAN));
        assertFalse(free.getMessage().contains("delete"));

        when(tenantLimits.plan(TENANT)).thenReturn(TenantPlan.PREMIUM);
        TenantLimitExceededException paid =
                assertThrows(
                        TenantLimitExceededException.class,
                        () -> service(true, true).start(question("q")));
        assertTrue(paid.getMessage().endsWith(ResearchRunService.MSG_SLOT_PAID_PLAN));

        verify(agentService, never()).create(any());
        verifyNoInteractions(usage, jobService);
    }

    @Test
    void aSlotBelowTheCeilingStillCreatesTheAgent() {
        when(tenantLimits.maxAgents(TENANT)).thenReturn(Optional.of(5));
        when(agentRepository.count()).thenReturn(4L);
        UUID agentId = UUID.randomUUID();
        AgentResponse created = mock(AgentResponse.class);
        when(created.id()).thenReturn(agentId);
        when(agentService.create(any())).thenReturn(created);
        when(jobService.submit(eq(agentId), any(), eq(true))).thenReturn(queuedJob(agentId));

        service(true, true).start(question("q"));
        verify(agentService).create(any());
    }

    @Test
    void aHalfBuiltAgentIsNeverDeletedBecauseAFreeWorkspaceCannotDelete() {
        UUID agentId = UUID.randomUUID();
        AgentResponse created = mock(AgentResponse.class);
        when(created.id()).thenReturn(agentId);
        when(agentService.create(any())).thenReturn(created);
        when(agentService.setCapabilityEnabled(agentId, "deep-research", true))
                .thenThrow(new IllegalArgumentException("Unknown capability"));

        assertThrows(
                IllegalArgumentException.class, () -> service(true, true).start(question("q")));
        verify(agentService, never()).delete(any());
        verifyNoInteractions(usage, jobService);
    }

    @Test
    void theNextRunAdoptsAHalfBuiltAgentInsteadOfCreatingAnother() {
        UUID halfBuilt = UUID.randomUUID();
        Agent unfinished =
                researchAgent(
                        halfBuilt, "Deep Research", false, Instant.parse("2026-02-01T00:00:00Z"));
        when(unfinished.getSystemPrompt()).thenReturn(ResearchRunPlan.SYSTEM_PROMPT);
        // Same name, the user's own prompt: theirs, never taken over.
        Agent usersOwn =
                researchAgent(
                        UUID.randomUUID(),
                        "Deep Research",
                        false,
                        Instant.parse("2026-01-01T00:00:00Z"));
        when(usersOwn.getSystemPrompt()).thenReturn("You are my reading-list helper.");
        when(agentRepository.findByCreatedBy(ALICE)).thenReturn(List.of(usersOwn, unfinished));
        when(jobService.submit(eq(halfBuilt), any(), eq(true))).thenReturn(queuedJob(halfBuilt));

        service(true, true).start(question("q"));

        verify(agentService, never()).create(any());
        InOrder order = inOrder(agentService, jobService);
        order.verify(agentService).setAllowedToolNames(halfBuilt, ResearchRunPlan.AGENT_TOOLS);
        order.verify(agentService).setCapabilityEnabled(halfBuilt, "deep-research", true);
        order.verify(jobService).submit(eq(halfBuilt), any(), eq(true));
    }

    @Test
    void aCallerWithoutASubjectIsRefusedBeforeAnythingIsCreatedOrCounted() {
        UUID jobId = UUID.randomUUID();
        for (String subject : new String[] {null, "", "  "}) {
            TenantContext.set(TenantContext.of(TENANT, subject, Set.of()));
            assertEquals(
                    401,
                    statusOf(
                            assertThrows(
                                    ResponseStatusException.class,
                                    () -> service(true, true).start(question("q")))));
            assertEquals(
                    401,
                    statusOf(
                            assertThrows(
                                    ResponseStatusException.class,
                                    () -> service(true, true).list(50, 0))));
            assertEquals(
                    401,
                    statusOf(
                            assertThrows(
                                    ResponseStatusException.class,
                                    () -> service(true, true).get(jobId))));
            assertEquals(
                    401,
                    statusOf(
                            assertThrows(
                                    ResponseStatusException.class,
                                    () -> service(true, true).cancel(jobId))));
        }
        verifyNoInteractions(jobService, agentService, usage);
        verify(agentRepository, never()).findByCreatedBy(any());
    }

    // ------------------------------------------------- history / get / cancel

    @Test
    void historyIsEmptyBeforeTheFirstRunAndOtherwiseTheResearchAgentsJobs() {
        assertEquals(List.of(), service(true, true).list(50, 0));
        verify(jobService, never()).listForAgentsWithGoalPrefix(any(), any(), anyInt(), anyInt());

        UUID agentId = UUID.randomUUID();
        // Built first: stubbing a mock inside another stubbing's thenReturn(...) is a Mockito
        // misuse.
        Agent mine = researchAgent(agentId, "Deep Research", true, Instant.now());
        when(agentRepository.findByCreatedBy(ALICE)).thenReturn(List.of(mine));
        when(jobService.listForAgentsWithGoalPrefix(any(), any(), anyInt(), anyInt()))
                .thenReturn(new AgentJobService.SummarySlice(List.of(), true));
        AgentJobService.SummarySlice page = service(true, true).listPage(20, 40);
        assertTrue(page.hasMore());
        verify(jobService)
                .listForAgentsWithGoalPrefix(List.of(agentId), ResearchRunPlan.GOAL_PREFIX, 20, 40);
    }

    @Test
    void historySpansAllOfTheCallersResearchAgentsOldestFirst() {
        UUID older = UUID.randomUUID();
        UUID newer = UUID.randomUUID();
        Agent newerAgent =
                researchAgent(newer, "Deep Research", true, Instant.parse("2026-03-01T00:00:00Z"));
        Agent olderAgent =
                researchAgent(older, "Deep Research", true, Instant.parse("2026-02-01T00:00:00Z"));
        when(agentRepository.findByCreatedBy(ALICE)).thenReturn(List.of(newerAgent, olderAgent));
        when(jobService.listForAgentsWithGoalPrefix(any(), any(), anyInt(), anyInt()))
                .thenReturn(new AgentJobService.SummarySlice(List.of(), false));

        service(true, true).list(50, 0);
        verify(jobService)
                .listForAgentsWithGoalPrefix(
                        List.of(older, newer), ResearchRunPlan.GOAL_PREFIX, 50, 0);
    }

    @Test
    void getFindsTheJobOnWhicheverResearchAgentOwnsIt() {
        UUID older = UUID.randomUUID();
        UUID newer = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        Agent newerAgent =
                researchAgent(newer, "Deep Research", true, Instant.parse("2026-03-01T00:00:00Z"));
        Agent olderAgent =
                researchAgent(older, "Deep Research", true, Instant.parse("2026-02-01T00:00:00Z"));
        when(agentRepository.findByCreatedBy(ALICE)).thenReturn(List.of(newerAgent, olderAgent));
        when(jobService.get(older, jobId)).thenThrow(new AgentJobNotFoundException(jobId));
        AgentJobResponse onNewer = queuedJob(newer);
        when(jobService.get(newer, jobId)).thenReturn(onNewer);

        assertSame(onNewer, service(true, true).get(jobId));
    }

    @Test
    void getDropsSourcesThatAreNotWebLinks() throws Exception {
        UUID agentId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        Agent mine = researchAgent(agentId, "Deep Research", true, Instant.now());
        when(agentRepository.findByCreatedBy(ALICE)).thenReturn(List.of(mine));
        AgentJobResponse base = queuedJob(agentId);
        tools.jackson.databind.JsonNode result =
                com.agenticai.core.json.JsonMappers.plain()
                        .readTree(
                                "{\"title\":\"t\",\"reportMarkdown\":\"r\",\"sources\":["
                                        + "{\"id\":\"S1\",\"url\":\"https://ok.example/a\"},"
                                        + "{\"id\":\"S2\",\"url\":\"javascript:alert(1)\"}]}");
        AgentJobResponse withResult =
                new AgentJobResponse(
                        base.id(),
                        base.agentId(),
                        base.goal(),
                        base.status(),
                        base.terminal(),
                        base.origin(),
                        base.originRef(),
                        base.sessionId(),
                        base.requestedBy(),
                        base.progressNote(),
                        base.progressUpdatedAt(),
                        base.resultText(),
                        result,
                        base.schemaAttempts(),
                        base.costUsd(),
                        base.errorCode(),
                        base.errorMessage(),
                        base.timeoutSeconds(),
                        base.deadlineAt(),
                        base.turnsUsed(),
                        base.maxTurns(),
                        base.cancelRequested(),
                        base.createdAt(),
                        base.startedAt(),
                        base.finishedAt(),
                        base.scheduleId(),
                        base.scheduleOccurrenceAt(),
                        base.parkedAt(),
                        base.approvalDeadlineAt(),
                        base.parkCount());
        when(jobService.get(agentId, jobId)).thenReturn(withResult);

        AgentJobResponse out = service(true, true).get(jobId);
        assertEquals(1, out.resultJson().get("sources").size());
        assertEquals("S1", out.resultJson().get("sources").get(0).get("id").asString());
    }

    @Test
    void getAndCancelAreScopedToTheCallersResearchAgent() {
        UUID jobId = UUID.randomUUID();
        // No research agent: any job id is "not found", never a lookup on somebody else's agent.
        assertThrows(AgentJobNotFoundException.class, () -> service(true, true).get(jobId));
        assertThrows(AgentJobNotFoundException.class, () -> service(true, true).cancel(jobId));
        verifyNoInteractions(jobService);

        UUID agentId = UUID.randomUUID();
        // Built first: stubbing a mock inside another stubbing's thenReturn(...) is a Mockito
        // misuse.
        Agent mine = researchAgent(agentId, "Deep Research", true, Instant.now());
        when(agentRepository.findByCreatedBy(ALICE)).thenReturn(List.of(mine));
        service(true, true).get(jobId);
        service(true, true).cancel(jobId);
        verify(jobService).get(agentId, jobId);
        verify(jobService).cancel(agentId, jobId);
        // Only the caller's own agents are ever considered.
        verify(agentRepository, never()).findAll();
    }

    @Test
    void withoutATenantContextNothingRuns() {
        TenantContext.clear();
        assertEquals(
                401,
                statusOf(
                        assertThrows(
                                ResponseStatusException.class,
                                () -> service(true, true).status())));
        assertEquals(
                401,
                statusOf(
                        assertThrows(
                                ResponseStatusException.class,
                                () -> service(true, true).start(question("q")))));
        verifyNoInteractions(jobService, agentService);
    }

    // -------------------------------------------------------------- controller

    @Test
    void controllerAnswers202OnStartAndDelegatesEverythingElse() {
        ResearchRunService runs = mock(ResearchRunService.class);
        ResearchRunController controller = new ResearchRunController(runs);
        UUID agentId = UUID.randomUUID();
        AgentJobResponse queued = queuedJob(agentId);
        ResearchRunStartRequest request = question("q");
        when(runs.start(request)).thenReturn(queued);

        ResponseEntity<AgentJobResponse> response = controller.start(request);
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertSame(queued, response.getBody());

        UUID jobId = UUID.randomUUID();
        when(runs.listPage(10, 20)).thenReturn(new AgentJobService.SummarySlice(List.of(), true));
        ResponseEntity<java.util.List<com.agenticai.core.agents.job.dto.AgentJobSummaryResponse>>
                page = controller.list(10, 20);
        assertEquals("true", page.getHeaders().getFirst("X-Has-More"));
        assertEquals(List.of(), page.getBody());
        controller.status();
        controller.get(jobId);
        controller.cancel(jobId);
        verify(runs).status();
        verify(runs).listPage(10, 20);
        verify(runs).get(jobId);
        verify(runs).cancel(jobId);
    }
}
