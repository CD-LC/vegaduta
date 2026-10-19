package com.agenticai.core.agents.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.dto.AgentCreateRequest;
import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.agents.modality.ModelModalitiesService;
import com.agenticai.core.providers.ProviderModelCatalogService;
import com.agenticai.core.providers.ProviderModelResponse;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.providers.modality.TextModelProviderValidator;
import com.agenticai.core.providers.modality.TextToSpeechProviderValidator;
import com.agenticai.core.providers.modality.VideoProviderValidator;
import com.agenticai.core.providers.modality.VoiceProviderValidator;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.sharing.ResourceAccessService;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * No test previously existed for {@link AgentService} at all. This class is
 * deliberately scoped to {@link AgentService#updateModel} only - covering
 * the whole 45KB class is a separate, much larger effort; this is the
 * direct-feedback FREE-tier fix ("disable model selection for free users")
 * that was verified against live source and needed real test coverage.
 *
 * <p>Standing caveat matching this repo's own convention (see
 * AGENT-COORDINATION-2026-07-18.md / .claude-handoffs/TIER2_HANDOFF.md):
 * written and hand-verified line-by-line against the live
 * {@code AgentService.java}/{@code Agent.java}/{@code TenantContext.java}/
 * {@code EditLockPolicy.java} sources, but this Cowork sandbox has no
 * Maven/JDK-17 toolchain to actually compile or run it (JDK 11 present, no
 * {@code mvn} binary, no network access on the device bridge - checked
 * directly). Needs a real {@code mvn test -pl core/agents -am} run to
 * confirm it actually compiles and passes.
 */
class AgentServiceTest {

    private AgentRepository repository;
    private TenantLimits tenantLimits;
    private ProviderModelCatalogService providerModelCatalogService;
    private AgentService service;

    @BeforeEach
    void setUp() {
        repository = mock(AgentRepository.class);
        tenantLimits = mock(TenantLimits.class);
        GenerationParamsInferenceService generationParamsInferenceService = mock(GenerationParamsInferenceService.class);
        providerModelCatalogService = mock(ProviderModelCatalogService.class);
        ModelModalitiesService modelModalitiesService = mock(ModelModalitiesService.class);
        PromptVersionRepository promptVersionRepository = mock(PromptVersionRepository.class);
        VoiceProviderValidator voiceProviderValidator = mock(VoiceProviderValidator.class);
        VideoProviderValidator videoProviderValidator = mock(VideoProviderValidator.class);
        TextModelProviderValidator textModelProviderValidator = mock(TextModelProviderValidator.class);
        TextToSpeechProviderValidator ttsProviderValidator = mock(TextToSpeechProviderValidator.class);
        // Cross-user resource sharing retrofit (Round 2) - a bare mock is
        // sufficient here: AgentService#requireAccess discards this call's
        // return value, and a Mockito mock never throws unless explicitly
        // stubbed to, so every existing test in this class (none of which
        // exercise the sharing feature) behaves exactly as before - access is
        // implicitly always granted.
        ResourceAccessService resourceAccessService = mock(ResourceAccessService.class);
        service = AgentServiceTestFactory.create(
            repository,
            tenantLimits,
            generationParamsInferenceService,
            providerModelCatalogService,
            modelModalitiesService,
            promptVersionRepository,
            voiceProviderValidator,
            videoProviderValidator,
            textModelProviderValidator,
            ttsProviderValidator,
            resourceAccessService,
            mock(AgentSchemaService.class),
            "claude-sonnet-5",
            1024,
            0.7,
            1.0
        );
        // save() round-trips its argument, matching what a real JPA save() of an
        // already-persisted (non-new) entity does.
        when(repository.save(any(Agent.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    private static Agent newAgent() {
        return new Agent(
            "Test Agent", "You are helpful.", ProviderName.ANTHROPIC, "claude-sonnet-5",
            1024, 0.7, 1.0, false, ExperienceMode.ADVANCED
        );
    }

    private UUID stubExistingAgent(Agent agent) {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(agent));
        return id;
    }

    @Test
    void updateModelRejectsFreeTierTenantWithTenantLimitExceededException() {
        TenantContext.set(TenantContext.of("acme", "user-1", Set.of()));
        when(tenantLimits.plan("acme")).thenReturn(TenantPlan.FREE);
        UUID agentId = stubExistingAgent(newAgent());

        TenantLimitExceededException thrown = assertThrows(
            TenantLimitExceededException.class,
            () -> service.updateModel(agentId, ProviderName.OPENAI, "gpt-4o")
        );

        assertTrue(thrown.getMessage().contains("Premium feature"));
        // Rejected before ever touching the catalog or persisting anything.
        verify(providerModelCatalogService, never()).findModel(any(), any());
        verify(repository, never()).save(any(Agent.class));
    }

    @Test
    void updateModelSucceedsForPremiumTenantWithARecognizedModel() {
        TenantContext.set(TenantContext.of("acme", "user-1", Set.of()));
        when(tenantLimits.plan("acme")).thenReturn(TenantPlan.PREMIUM);
        when(providerModelCatalogService.findModel(ProviderName.OPENAI, "gpt-4o"))
            .thenReturn(Optional.of(mock(ProviderModelResponse.class)));
        UUID agentId = stubExistingAgent(newAgent());

        AgentResponse response = service.updateModel(agentId, ProviderName.OPENAI, "gpt-4o");

        assertEquals(ProviderName.OPENAI, response.providerName());
        assertEquals("gpt-4o", response.model());
        verify(repository).save(any(Agent.class));
    }

    @Test
    void updateModelAcceptsAnOffCatalogModelForAPayingByokTenant() {
        // BYOK-any-model (2026-07): for a paying BYOK tenant (not on the shared
        // pool - usePlatformLlm defaults false here) the curated catalog is only
        // a suggestion list, not a hard allowlist, so any non-blank model id is
        // accepted - "activate any provider/LLM, no restrictions". (Contrast the
        // FREE tenant, still locked to the platform pool, and a usePlatformLlm
        // tenant, still catalog-gated - both covered in AgentServiceModelSelectionTest.)
        TenantContext.set(TenantContext.of("acme", "user-1", Set.of()));
        when(tenantLimits.plan("acme")).thenReturn(TenantPlan.PREMIUM);
        when(providerModelCatalogService.findModel(ProviderName.GEMINI, "some-new-model"))
            .thenReturn(Optional.empty());
        UUID agentId = stubExistingAgent(newAgent());

        AgentResponse response = service.updateModel(agentId, ProviderName.GEMINI, "some-new-model");

        assertEquals(ProviderName.GEMINI, response.providerName());
        assertEquals("some-new-model", response.model());
        verify(repository).save(any(Agent.class));
    }

    @Test
    void createCoercesFreeTierTenantToPlatformHostedRegardlessOfRequestedProvider() {
        // Direct feedback: "free users are able to select the model, billing
        // functionality is not restricting the user" - create() had no
        // equivalent gate to updateModel's above; a FREE tenant's request
        // could still specify a real BYOK provider/model directly (bypassing
        // the now-hidden-for-FREE web picker) and have it persisted.
        TenantContext.set(TenantContext.of("acme", "user-1", Set.of()));
        when(tenantLimits.plan("acme")).thenReturn(TenantPlan.FREE);
        AgentCreateRequest request = new AgentCreateRequest(
            "Test Agent", "You are helpful.", ProviderName.ANTHROPIC, "claude-sonnet-5",
            null, null, null, null, null, null, null, null, null, null, null, null, null, null
        );

        AgentResponse response = service.create(request);

        assertEquals(ProviderName.PLATFORM_HOSTED, response.providerName());
        // Never touches the BYOK catalog for a FREE tenant's request -
        // neither the raw listing nor the default-model pick over it.
        verify(providerModelCatalogService, never()).listModels(any());
        verify(providerModelCatalogService, never()).defaultModelFor(any());
    }

    @Test
    void createHonorsRequestedProviderModelForAPayingTenant() {
        TenantContext.set(TenantContext.of("acme", "user-1", Set.of()));
        when(tenantLimits.plan("acme")).thenReturn(TenantPlan.PREMIUM);
        AgentCreateRequest request = new AgentCreateRequest(
            "Test Agent", "You are helpful.", ProviderName.OPENAI, "gpt-4o",
            null, null, null, null, null, null, null, null, null, null, null, null, null, null
        );

        AgentResponse response = service.create(request);

        assertEquals(ProviderName.OPENAI, response.providerName());
        assertEquals("gpt-4o", response.model());
    }

    @Test
    void updateModelTreatsNoTenantContextAsUnlimitedAndAllowsTheChange() {
        // No TenantContext.set(...) call - matches AgentService's own
        // documented "callers with no HTTP request context... aren't
        // capped" guard (tenantId == null -> TenantPlan.UNLIMITED).
        when(providerModelCatalogService.findModel(ProviderName.OPENAI, "gpt-4o"))
            .thenReturn(Optional.of(mock(ProviderModelResponse.class)));
        UUID agentId = stubExistingAgent(newAgent());

        AgentResponse response = service.updateModel(agentId, ProviderName.OPENAI, "gpt-4o");

        assertEquals("gpt-4o", response.model());
        verify(tenantLimits, never()).plan(any());
    }

    // Same-tenant agent duplication (P0 gap-radar item, 2026-09-23) - #duplicate
    // was refactored to route through AgentConfigSnapshotCodec (the same
    // capture/apply pair AgentExperimentService#buildTreatmentClone uses)
    // instead of its original hand-rolled field-by-field copy, which had
    // silently omitted several Agent fields (fallback provider, autonomy tier,
    // among others - see AgentService#duplicate's own javadoc). These two tests
    // would have failed against that original implementation.

    @Test
    void duplicateCopiesTheFullConfigIntoANewAgentRow() {
        TenantContext.set(TenantContext.of("acme", "user-1", Set.of()));
        when(tenantLimits.plan("acme")).thenReturn(TenantPlan.UNLIMITED);
        when(tenantLimits.maxAgents("acme")).thenReturn(Optional.empty());

        Agent source = newAgent();
        source.setFallbackProvider(ProviderName.OPENAI, "gpt-4o");
        source.setAutonomyTier(AgentAutonomyTier.FULLY_AUTONOMOUS);
        source.setAllowedToolNames(Set.of("search_knowledge_base"));
        UUID agentId = stubExistingAgent(source);

        AgentResponse response = service.duplicate(agentId);

        assertEquals("Test Agent (copy)", response.name());
        assertEquals(ProviderName.ANTHROPIC, response.providerName());
        assertEquals("claude-sonnet-5", response.model());
        // Fields the original hand-rolled copy in #duplicate used to silently
        // drop - now covered because capture()/apply() is the single field
        // inventory shared with config-version restore.
        assertEquals(ProviderName.OPENAI, response.fallbackProviderName());
        assertEquals("gpt-4o", response.fallbackModel());
        assertEquals(AgentAutonomyTier.FULLY_AUTONOMOUS, response.autonomyTier());
        assertEquals(Set.of("search_knowledge_base"), response.allowedToolNames());
        verify(repository).save(any(Agent.class));
    }

    // Per-agent run cap (V421) lives outside AgentConfigSnapshot, so copies need it carried explicitly.

    @Test
    void duplicateCarriesThePerAgentRunCap() {
        TenantContext.set(TenantContext.of("acme", "user-1", Set.of()));
        when(tenantLimits.plan("acme")).thenReturn(TenantPlan.UNLIMITED);
        when(tenantLimits.maxAgents("acme")).thenReturn(Optional.empty());
        Agent source = newAgent();
        source.setRunBudgetUsd(new java.math.BigDecimal("0.5000"));
        UUID agentId = stubExistingAgent(source);

        service.duplicate(agentId);

        org.mockito.ArgumentCaptor<Agent> saved = org.mockito.ArgumentCaptor.forClass(Agent.class);
        verify(repository).save(saved.capture());
        assertEquals(new java.math.BigDecimal("0.5000"), saved.getValue().getRunBudgetUsd());
    }

    @Test
    void runCapOfAnExperimentTreatmentCloneIsTheControlAgentsCap() {
        Agent control = newAgent();
        control.setRunBudgetUsd(new java.math.BigDecimal("0.2500"));
        UUID controlId = stubExistingAgent(control);
        Agent clone = new Agent(
            "Test Agent [experiment treatment]", "You are helpful.", ProviderName.ANTHROPIC, "claude-sonnet-5",
            1024, 0.7, 1.0, false, ExperienceMode.ADVANCED, ReasoningDepth.FAST, TuningMode.MANUAL, controlId);
        UUID cloneId = stubExistingAgent(clone);

        assertEquals(new java.math.BigDecimal("0.2500"), service.getRunBudgetUsd(cloneId));
        assertEquals(new java.math.BigDecimal("0.2500"), service.getRunBudgetUsd(controlId));
        assertEquals(null, service.getRunBudgetUsd(stubExistingAgent(newAgent())));
    }

    @Test
    void duplicateRejectsAtTheTenantsAgentCountCeilingSameAsCreate() {
        TenantContext.set(TenantContext.of("acme", "user-1", Set.of()));
        when(tenantLimits.plan("acme")).thenReturn(TenantPlan.PREMIUM);
        when(tenantLimits.maxAgents("acme")).thenReturn(Optional.of(5));
        when(tenantLimits.accountType("acme")).thenReturn(com.agenticai.core.security.AccountType.B2B);
        when(repository.count()).thenReturn(5L);
        UUID agentId = stubExistingAgent(newAgent());

        TenantLimitExceededException thrown = assertThrows(
            TenantLimitExceededException.class,
            () -> service.duplicate(agentId)
        );

        assertTrue(thrown.getMessage().contains("allows up to 5"));
        verify(repository, never()).save(any(Agent.class));
    }
}
