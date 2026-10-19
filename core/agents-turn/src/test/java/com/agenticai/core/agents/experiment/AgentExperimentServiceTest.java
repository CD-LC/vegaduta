package com.agenticai.core.agents.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.agents.core.AgentSchemaService;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.version.AgentConfigSnapshotCodec;
import com.agenticai.core.agents.version.AgentVersionRepository;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link AgentExperimentService} with every collaborator mocked (no DB, no
 * Spring context) - the two contracts every chat turn depends on
 * ({@link #resolveEffectiveAgentId} bucketing/stickiness and its fail-open
 * posture), plus the {@link #metrics} aggregation math. Mirrors {@code
 * FeatureFlagServiceTest}'s structure and reasoning for the bucketing half,
 * since {@link AgentExperimentService#resolveEffectiveAgentId} deliberately
 * replicates {@code FeatureFlagService#bucketOf}'s math (see that method's
 * javadoc for why it is a replica, not a shared call).
 */
class AgentExperimentServiceTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID CONTROL_ID = AGENT_ID;
    private static final UUID TREATMENT_ID = UUID.randomUUID();
    private static final UUID EXPERIMENT_ID = UUID.randomUUID();

    private AgentService agentService;
    private AgentRepository agentRepository;
    private AgentSchemaService agentSchemaService;
    private AgentVersionRepository agentVersionRepository;
    private AgentConfigSnapshotCodec codec;
    private AgentExperimentRepository experimentRepository;
    private AgentExperimentAssignmentRepository assignmentRepository;
    private TenantSchemaIterator tenantSchemaIterator;
    private ExperimentMetricsReader metricsReader;
    private PgAdvisoryJobLock jobLock;
    private AgentExperimentService service;

    @BeforeEach
    void setUp() {
        agentService = mock(AgentService.class);
        agentRepository = mock(AgentRepository.class);
        agentSchemaService = mock(AgentSchemaService.class);
        agentVersionRepository = mock(AgentVersionRepository.class);
        codec = mock(AgentConfigSnapshotCodec.class);
        experimentRepository = mock(AgentExperimentRepository.class);
        assignmentRepository = mock(AgentExperimentAssignmentRepository.class);
        tenantSchemaIterator = mock(TenantSchemaIterator.class);
        metricsReader = mock(ExperimentMetricsReader.class);
        jobLock = mock(PgAdvisoryJobLock.class);
        when(jobLock.tryRunExclusive(org.mockito.ArgumentMatchers.anyString(), any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return true;
        });
        service = new AgentExperimentService(
            agentService, agentRepository, agentSchemaService, agentVersionRepository, codec,
            experimentRepository, assignmentRepository, tenantSchemaIterator, metricsReader, jobLock);

        // Assignment save() round-trips its argument by default - keeps the
        // "first sight of a subject" tests below simple.
        when(assignmentRepository.save(any(AgentExperimentAssignment.class)))
            .thenAnswer(inv -> inv.getArgument(0));
    }

    private AgentExperiment runningExperiment(int trafficPercent) {
        AgentExperiment experiment = new AgentExperiment(
            AGENT_ID, "test experiment", CONTROL_ID, TREATMENT_ID, UUID.randomUUID(), trafficPercent, null, "tester");
        experiment.start();
        // id is @GeneratedValue - never set outside real JPA persistence, so
        // tests that need a stable id to stub assignment lookups against set
        // it directly, the same way EvalGateServiceTest sets an
        // EntityManager field it cannot reach through a constructor.
        org.springframework.test.util.ReflectionTestUtils.setField(experiment, "id", EXPERIMENT_ID);
        when(experimentRepository.findByAgentIdAndStatus(AGENT_ID, ExperimentStatus.RUNNING))
            .thenReturn(Optional.of(experiment));
        return experiment;
    }

    // -- resolveEffectiveAgentId: no experiment -> unchanged --

    @Test
    void noRunningExperimentReturnsTheRequestedAgentIdUnchanged() {
        when(experimentRepository.findByAgentIdAndStatus(AGENT_ID, ExperimentStatus.RUNNING))
            .thenReturn(Optional.empty());

        assertEquals(AGENT_ID, service.resolveEffectiveAgentId(AGENT_ID, "session-1"));
        verify(assignmentRepository, never()).save(any());
    }

    @Test
    void nullRequestedAgentIdIsReturnedUnchangedWithoutTouchingAnyRepository() {
        assertNull(service.resolveEffectiveAgentId(null, "session-1"));
        verify(experimentRepository, never()).findByAgentIdAndStatus(any(), any());
    }

    // -- Bucketing: deterministic and sticky --

    @Test
    void sameSubjectAlwaysYieldsTheSameVariantAcrossRepeatedCallsOnceAssigned() {
        runningExperiment(50);
        // First call: no stored assignment yet.
        when(assignmentRepository.findByExperimentIdAndSubjectKey(EXPERIMENT_ID, "session-sticky"))
            .thenReturn(Optional.empty());

        UUID first = service.resolveEffectiveAgentId(AGENT_ID, "session-sticky");

        // Second call: simulate the assignment now being stored (as the
        // first call's save would have done in a real repository).
        AgentExperimentAssignment stored = new AgentExperimentAssignment(
            EXPERIMENT_ID, "session-sticky", first.equals(TREATMENT_ID) ? ExperimentVariant.TREATMENT : ExperimentVariant.CONTROL);
        when(assignmentRepository.findByExperimentIdAndSubjectKey(EXPERIMENT_ID, "session-sticky"))
            .thenReturn(Optional.of(stored));

        UUID second = service.resolveEffectiveAgentId(AGENT_ID, "session-sticky");
        UUID third = service.resolveEffectiveAgentId(AGENT_ID, "session-sticky");

        assertEquals(first, second);
        assertEquals(first, third);
    }

    @Test
    void storedAssignmentWinsOverAChangedTrafficPercentage() {
        AgentExperiment experiment = runningExperiment(0); // 0% treatment at bucketing time
        // But this subject was already assigned TREATMENT earlier (e.g.
        // trafficPercent was 100 at the time, then lowered to 0).
        AgentExperimentAssignment stickyTreatment =
            new AgentExperimentAssignment(EXPERIMENT_ID, "session-already-treatment", ExperimentVariant.TREATMENT);
        when(assignmentRepository.findByExperimentIdAndSubjectKey(EXPERIMENT_ID, "session-already-treatment"))
            .thenReturn(Optional.of(stickyTreatment));

        assertEquals(experiment.getTreatmentAgentId(),
            service.resolveEffectiveAgentId(AGENT_ID, "session-already-treatment"));
        // Stored-assignment-wins means no new bucketing decision was written.
        verify(assignmentRepository, never()).save(any());
    }

    @Test
    void zeroPercentTrafficNeverRoutesAFreshSubjectToTreatment() {
        runningExperiment(0);
        when(assignmentRepository.findByExperimentIdAndSubjectKey(any(), anyString())).thenReturn(Optional.empty());

        for (int i = 0; i < 30; i++) {
            UUID result = service.resolveEffectiveAgentId(AGENT_ID, "subject-" + i);
            assertEquals(CONTROL_ID, result, "0% traffic leaked subject-" + i + " into treatment");
        }
    }

    @Test
    void hundredPercentTrafficAlwaysRoutesAFreshSubjectToTreatment() {
        runningExperiment(100);
        when(assignmentRepository.findByExperimentIdAndSubjectKey(any(), anyString())).thenReturn(Optional.empty());

        for (int i = 0; i < 30; i++) {
            UUID result = service.resolveEffectiveAgentId(AGENT_ID, "subject-" + i);
            assertEquals(TREATMENT_ID, result, "100% traffic stranded subject-" + i + " in control");
        }
    }

    @Test
    void blankSubjectKeyStillBucketsDeterministicallyInsteadOfSkippingRouting() {
        runningExperiment(100);
        when(assignmentRepository.findByExperimentIdAndSubjectKey(any(), anyString())).thenReturn(Optional.empty());

        UUID viaNull = service.resolveEffectiveAgentId(AGENT_ID, null);
        UUID viaBlank = service.resolveEffectiveAgentId(AGENT_ID, "   ");

        assertEquals(TREATMENT_ID, viaNull);
        assertEquals(TREATMENT_ID, viaBlank);
    }

    // -- Fail-open: never throws, always falls back to the requested id --

    @Test
    void repositoryFailureFallsOpenToTheRequestedAgentIdInsteadOfPropagating() {
        when(experimentRepository.findByAgentIdAndStatus(AGENT_ID, ExperimentStatus.RUNNING))
            .thenThrow(new RuntimeException("connection pool exhausted"));

        UUID result = service.resolveEffectiveAgentId(AGENT_ID, "session-1");

        assertEquals(AGENT_ID, result);
    }

    @Test
    void assignmentLookupFailureFallsOpenToTheRequestedAgentId() {
        runningExperiment(100);
        when(assignmentRepository.findByExperimentIdAndSubjectKey(any(), anyString()))
            .thenThrow(new RuntimeException("assignment table locked"));

        assertEquals(AGENT_ID, service.resolveEffectiveAgentId(AGENT_ID, "session-1"));
    }

    // -- create(): rejects a second active experiment --

    @Test
    void createRejectsASecondActiveExperimentForTheSameAgent() {
        Agent agent = mock(Agent.class);
        when(agentService.requireAccess(AGENT_ID, PermissionLevel.EDITOR)).thenReturn(agent);
        when(experimentRepository.findByAgentIdAndStatusIn(AGENT_ID, List.of(ExperimentStatus.DRAFT, ExperimentStatus.RUNNING)))
            .thenReturn(Optional.of(new AgentExperiment(
                AGENT_ID, "existing", CONTROL_ID, TREATMENT_ID, UUID.randomUUID(), 10, null, "tester")));

        assertThrows(AgentExperimentConflictException.class, () ->
            service.create(AGENT_ID, "second", UUID.randomUUID(), 10, null, "tester"));
    }

    @Test
    void createRejectsOutOfRangeTrafficPercent() {
        Agent agent = mock(Agent.class);
        when(agentService.requireAccess(AGENT_ID, PermissionLevel.EDITOR)).thenReturn(agent);

        assertThrows(IllegalArgumentException.class, () ->
            service.create(AGENT_ID, "bad traffic", UUID.randomUUID(), 101, null, "tester"));
        assertThrows(IllegalArgumentException.class, () ->
            service.create(AGENT_ID, "bad traffic", UUID.randomUUID(), -1, null, "tester"));
    }

    // -- create(): the treatment clone is a real extra agent, so it must respect the plan's agent cap --

    @Test
    void createIsBlockedWhenTheTenantIsAtItsAgentCapAndNeverSavesTheClone() {
        Agent agent = mock(Agent.class);
        when(agentService.requireAccess(AGENT_ID, PermissionLevel.EDITOR)).thenReturn(agent);
        when(experimentRepository.findByAgentIdAndStatusIn(AGENT_ID, List.of(ExperimentStatus.DRAFT, ExperimentStatus.RUNNING)))
            .thenReturn(Optional.empty());
        UUID versionId = UUID.randomUUID();
        when(agentVersionRepository.findByIdAndAgentId(versionId, AGENT_ID)).thenReturn(Optional.of(mock(com.agenticai.core.agents.version.AgentVersion.class)));
        org.mockito.Mockito.doThrow(new com.agenticai.core.security.TenantLimitExceededException("at the cap"))
            .when(agentService).requireAgentSlot();

        assertThrows(com.agenticai.core.security.TenantLimitExceededException.class, () ->
            service.create(AGENT_ID, "over the cap", versionId, 10, null, "tester"));

        verify(agentRepository, never()).save(any(Agent.class));
        verify(experimentRepository, never()).save(any(AgentExperiment.class));
    }

    // -- start/stop lifecycle guards --

    @Test
    void startingAnAlreadyRunningExperimentIsRejected() {
        Agent agent = mock(Agent.class);
        when(agentService.requireAccess(AGENT_ID, PermissionLevel.EDITOR)).thenReturn(agent);
        AgentExperiment running = runningExperiment(50);
        when(experimentRepository.findByIdAndAgentId(EXPERIMENT_ID, AGENT_ID)).thenReturn(Optional.of(running));

        assertThrows(AgentExperimentConflictException.class, () -> service.start(AGENT_ID, EXPERIMENT_ID));
    }

    @Test
    void stoppingAnAlreadyStoppedExperimentIsRejected() {
        Agent agent = mock(Agent.class);
        when(agentService.requireAccess(AGENT_ID, PermissionLevel.EDITOR)).thenReturn(agent);
        AgentExperiment experiment = new AgentExperiment(
            AGENT_ID, "test", CONTROL_ID, TREATMENT_ID, UUID.randomUUID(), 50, null, "tester");
        experiment.start();
        experiment.stop("manual stop");
        when(experimentRepository.findByIdAndAgentId(EXPERIMENT_ID, AGENT_ID)).thenReturn(Optional.of(experiment));

        assertThrows(AgentExperimentConflictException.class, () -> service.stop(AGENT_ID, EXPERIMENT_ID, "again", "tester"));
    }

    // -- metrics(): aggregation math --

    @Test
    void metricsComputesErrorRateAndThumbsUpRateFromRawAggregateRows() {
        Agent agent = mock(Agent.class);
        when(agentService.requireAccess(AGENT_ID, PermissionLevel.VIEWER)).thenReturn(agent);
        AgentExperiment experiment = new AgentExperiment(
            AGENT_ID, "metrics test", CONTROL_ID, TREATMENT_ID, UUID.randomUUID(), 50, null, "tester");
        experiment.start();
        when(experimentRepository.findByIdAndAgentId(EXPERIMENT_ID, AGENT_ID)).thenReturn(Optional.of(experiment));

        // Control: 10 calls, 2 errors -> 20% error rate; 4 up / 5 up+down -> 80% thumbs-up.
        when(metricsReader.queryLlmCallStats(eq(CONTROL_ID), any(), any()))
            .thenReturn(new Object[] {10L, 250.0, java.math.BigDecimal.valueOf(1.5), 2L});
        when(metricsReader.queryFeedbackStats(eq(CONTROL_ID), any(), any()))
            .thenReturn(new Object[] {4L, 5L});

        // Treatment: zero calls, zero feedback -> both rates null, not zero.
        when(metricsReader.queryLlmCallStats(eq(TREATMENT_ID), any(), any()))
            .thenReturn(new Object[] {0L, null, java.math.BigDecimal.ZERO, 0L});
        when(metricsReader.queryFeedbackStats(eq(TREATMENT_ID), any(), any()))
            .thenReturn(new Object[] {0L, 0L});

        AgentExperimentService.ExperimentMetrics metrics = service.metrics(AGENT_ID, EXPERIMENT_ID);

        assertEquals(10L, metrics.control().callCount());
        assertEquals(250.0, metrics.control().avgLatencyMs());
        assertEquals(1.5, metrics.control().totalCostUsd());
        assertEquals(20.0, metrics.control().errorRatePct());
        assertEquals(80.0, metrics.control().thumbsUpRatePct());

        assertEquals(0L, metrics.treatment().callCount());
        assertNull(metrics.treatment().errorRatePct(), "zero calls must yield a null rate, not a fabricated zero");
        assertNull(metrics.treatment().thumbsUpRatePct(), "zero feedback must yield a null rate, not a fabricated zero");
    }

    // -- buildTreatmentClone: rejects a version with no provider/model --

    @Test
    void createRejectsATreatmentVersionWithNoStoredProviderOrModel() {
        Agent agent = mock(Agent.class);
        when(agentService.requireAccess(AGENT_ID, PermissionLevel.EDITOR)).thenReturn(agent);
        when(experimentRepository.findByAgentIdAndStatusIn(AGENT_ID, List.of(ExperimentStatus.DRAFT, ExperimentStatus.RUNNING)))
            .thenReturn(Optional.empty());

        UUID versionId = UUID.randomUUID();
        // AgentVersion has no public constructor/factory reachable from this
        // package (by design - see its javadoc) - a mock stands in for "a
        // stored version row with this config_json/agentName" instead.
        com.agenticai.core.agents.version.AgentVersion version = mock(com.agenticai.core.agents.version.AgentVersion.class);
        when(version.getId()).thenReturn(versionId);
        when(version.getConfigJson()).thenReturn("{}");
        when(version.getAgentName()).thenReturn("Some Agent");
        when(agentVersionRepository.findByIdAndAgentId(versionId, AGENT_ID)).thenReturn(Optional.of(version));
        // No provider/model in the decoded snapshot - every field explicit
        // (matching AgentConfigSnapshot's declared order exactly) rather than
        // relying on a builder, since the record has none.
        com.agenticai.core.agents.version.AgentConfigSnapshot emptySnapshot =
            new com.agenticai.core.agents.version.AgentConfigSnapshot(
                1,                  // formatVersion
                "Some Agent",       // name
                "prompt",           // systemPrompt
                null,               // providerName - THE missing field under test
                null,               // model - THE missing field under test
                null,               // maxTokens
                null,               // temperature
                null,               // topP
                null,               // fallbackProviderName
                null,               // fallbackModel
                null,               // autonomyTier
                null,               // tuningMode
                null,               // reasoningDepth
                null,               // experienceMode
                null,               // edgeRoutingPolicy
                null,               // knowledgeBaseEnabled
                null,               // sarvamEnabled
                null,               // e2bEnabled
                null,               // codeWorkspaceEnabled
                null,               // knowledgeWriteEnabled
                null,               // shareMemoryWithTenant
                null,               // biometricConsentEnabled
                null,               // responseCachingEnabled
                null,               // semanticResponseCachingEnabled
                null,               // costRoutingEnabled
                null,               // economyProviderName
                null,               // economyModel
                null,               // costRoutingJevThreshold
                null,               // voiceModelProvider
                null,               // voiceModel
                null,               // videoModelProvider
                null,               // videoModel
                null,               // textModelProvider
                null,               // textModel
                null,               // ttsProvider
                null,               // ttsModel
                null,               // resultSchemaJson
                null,               // allowedToolNames
                null,               // allowedKnowledgeCollectionIds
                null,               // allowedCodeIntelRepoIds
                null,               // allowedKnowledgeWriteCollectionIds
                null,               // collaboratorAgentIds
                null,               // enabledGuardrails
                null,              // xaiServerToolsEnabled
                null, null, null, null, null);   // sampling group
        when(codec.fromJson("{}")).thenReturn(emptySnapshot);

        assertThrows(IllegalArgumentException.class, () ->
            service.create(AGENT_ID, "no provider", versionId, 10, null, "tester"));
    }

    /** JAVA-023: a replica that loses the advisory job lock skips the auto-stop sweep. */
    @Test
    void autoStopSweepIsSkippedWhenAnotherReplicaHoldsTheJobLock() {
        org.mockito.Mockito.reset(jobLock);
        when(jobLock.tryRunExclusive(org.mockito.ArgumentMatchers.anyString(), any())).thenReturn(false);

        service.sweepAutoStops();

        org.mockito.Mockito.verify(jobLock).tryRunExclusive(eq("agent-experiment-auto-stop"), any());
        org.mockito.Mockito.verifyNoInteractions(tenantSchemaIterator);
    }
}
