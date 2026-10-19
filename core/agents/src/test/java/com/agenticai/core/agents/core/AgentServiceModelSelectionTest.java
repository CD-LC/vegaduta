package com.agenticai.core.agents.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
 * Covers {@link AgentService#updateModel}'s model-selection posture after the
 * curated catalog was relaxed from a hard allowlist into a suggestion list for
 * BYOK (PAID, non-platform-LLM) tenants, while staying a hard allowlist for
 * platform-LLM tenants (who route through the shared pool and never pick their
 * own model - see {@code AgentChatService#buildChatClientFor}). FREE tenants are
 * rejected earlier still, at the plan gate ("Premium feature"), before the
 * catalog is ever consulted.
 *
 * <p>Scoped deliberately to the model-selection branch only; the broader
 * FREE-tier create()/edit-lock behavior is exercised elsewhere.
 */
class AgentServiceModelSelectionTest {

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
        PromptVersionRepository promptVersionRepository = mock(PromptVersionRepository.class);
        service = AgentServiceTestFactory.create(
            repository,
            tenantLimits,
            generationParamsInferenceService,
            providerModelCatalogService,
            mock(ModelModalitiesService.class),
            promptVersionRepository,
            mock(VoiceProviderValidator.class),
            mock(VideoProviderValidator.class),
            mock(TextModelProviderValidator.class),
            mock(TextToSpeechProviderValidator.class),
            // Cross-user resource sharing retrofit (Round 2) - a bare mock never
            // throws unless stubbed, so every test here (none exercise sharing)
            // behaves exactly as before - access is implicitly always granted.
            mock(ResourceAccessService.class),
            mock(AgentSchemaService.class),
            "claude-sonnet-5",
            1024,
            0.7,
            1.0
        );
        // save() round-trips its argument, matching a real JPA save() of an
        // already-persisted (non-new) entity.
        when(repository.save(any(Agent.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    private static Agent newAgent() {
        // 24h edit-lock grace window is measured from createdAt (= now for a
        // freshly-constructed Agent), so every agent here is editable regardless
        // of plan - keeps these tests about model validation, not the edit-lock.
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

    // --- PAID / BYOK: catalog is a suggestion list, off-catalog models accepted ---

    @Test
    void paidTenantMayUseAnOffCatalogModel() {
        TenantContext.set(TenantContext.of("acme", "user-1", Set.of()));
        when(tenantLimits.plan("acme")).thenReturn(TenantPlan.PREMIUM);
        when(tenantLimits.usesPlatformPool("acme")).thenReturn(false);
        UUID agentId = stubExistingAgent(newAgent());

        AgentResponse response = service.updateModel(agentId, ProviderName.OPENAI, "gpt-6-experimental-0731");

        assertEquals(ProviderName.OPENAI, response.providerName());
        assertEquals("gpt-6-experimental-0731", response.model());
        verify(repository).save(any(Agent.class));
        // The catalog is never consulted for a BYOK tenant - it is not an allowlist.
        verify(providerModelCatalogService, never()).findModel(any(), any());
    }

    @Test
    void unlimitedTenantMayUseAnOffCatalogModel() {
        TenantContext.set(TenantContext.of("acme", "user-1", Set.of()));
        when(tenantLimits.plan("acme")).thenReturn(TenantPlan.UNLIMITED);
        when(tenantLimits.usesPlatformPool("acme")).thenReturn(false);
        UUID agentId = stubExistingAgent(newAgent());

        AgentResponse response = service.updateModel(agentId, ProviderName.ANTHROPIC, "claude-opus-9-preview");

        assertEquals("claude-opus-9-preview", response.model());
        verify(providerModelCatalogService, never()).findModel(any(), any());
    }

    @Test
    void noTenantContextIsTreatedAsUnrestrictedByok() {
        // No TenantContext.set(...) - matches AgentService's documented "callers
        // with no HTTP request context aren't capped/locked" guard.
        UUID agentId = stubExistingAgent(newAgent());

        AgentResponse response = service.updateModel(agentId, ProviderName.OPENAI, "some-brand-new-model");

        assertEquals("some-brand-new-model", response.model());
        // The null-tenant guard itself now lives in TenantLimits#usesPlatformPool
        // (it answers false with no lookup), so the observable claim here is the
        // one that matters: the catalog was never treated as an allowlist.
        verify(providerModelCatalogService, never()).findModel(any(), any());
    }

    // --- PAID / BYOK: blank model is still a mistake, not "arbitrary" ---

    @Test
    void paidTenantBlankModelIsStillRejected() {
        TenantContext.set(TenantContext.of("acme", "user-1", Set.of()));
        when(tenantLimits.plan("acme")).thenReturn(TenantPlan.PREMIUM);
        when(tenantLimits.usesPlatformPool("acme")).thenReturn(false);
        UUID agentId = stubExistingAgent(newAgent());

        assertThrows(
            AgentModelNotSupportedException.class,
            () -> service.updateModel(agentId, ProviderName.OPENAI, "   ")
        );
        verify(repository, never()).save(any(Agent.class));
    }

    // --- FREE: rejected at the plan gate, before the catalog is consulted ---

    @Test
    void freeTenantIsRejectedBeforeAnyCatalogCheck() {
        // Merged posture ("disable model selection for free users"): a FREE tenant
        // is locked out of updateModel at the plan gate with a "Premium feature"
        // TenantLimitExceededException - the catalog is never even reached, so it
        // makes no difference whether the requested model is on- or off-catalog.
        TenantContext.set(TenantContext.of("acme", "user-1", Set.of()));
        when(tenantLimits.plan("acme")).thenReturn(TenantPlan.FREE);
        UUID agentId = stubExistingAgent(newAgent());

        assertThrows(
            com.agenticai.core.security.TenantLimitExceededException.class,
            () -> service.updateModel(agentId, ProviderName.OPENAI, "gpt-6-experimental-0731")
        );
        verify(providerModelCatalogService, never()).findModel(any(), any());
        verify(repository, never()).save(any(Agent.class));
    }

    // --- resolveEffectiveModel(): vendor-retired model runtime self-heal ---

    @Test
    void retiredModelIsSubstitutedAtRuntimeWithoutRewritingThePersistedRow() {
        // An agent persisted on grok-3 before xAI's 2026-05-15 retirement:
        // the chat turn must run on the mapped replacement, but the stored
        // row stays untouched (runtime substitution + WARN, not a silent
        // rewrite - see resolveEffectiveModel's comment).
        Agent agent = new Agent(
            "Legacy Grok Agent", "You are helpful.", ProviderName.GROK, "grok-3",
            1024, 0.7, 1.0, false, ExperienceMode.ADVANCED
        );
        when(providerModelCatalogService.replacementForRetiredModel("grok-3"))
            .thenReturn(Optional.of("grok-4.3"));

        String effective = service.resolveEffectiveModel(agent);

        assertEquals("grok-4.3", effective);
        assertEquals("grok-3", agent.getModel());
        verify(repository, never()).save(any(Agent.class));
    }

    @Test
    void currentModelIsReturnedAsIsWithNoSubstitution() {
        Agent agent = newAgent();
        when(providerModelCatalogService.replacementForRetiredModel("claude-sonnet-5"))
            .thenReturn(Optional.empty());

        assertEquals("claude-sonnet-5", service.resolveEffectiveModel(agent));
        verify(repository, never()).save(any(Agent.class));
    }

    // --- platform-LLM (opted-in PAID): catalog stays a hard allowlist ---

    @Test
    void paidTenantOptedIntoPlatformLlmOnCatalogModelIsAccepted() {
        // The positive counterpart to paidTenantOptedIntoPlatformLlmIsStillCatalogGated:
        // an on-catalog model is accepted for a tenant routing through the shared pool.
        TenantContext.set(TenantContext.of("acme", "user-1", Set.of()));
        when(tenantLimits.plan("acme")).thenReturn(TenantPlan.PREMIUM);
        when(tenantLimits.usesPlatformPool("acme")).thenReturn(true);
        when(providerModelCatalogService.findModel(ProviderName.ANTHROPIC, "claude-sonnet-5"))
            .thenReturn(Optional.of(mock(ProviderModelResponse.class)));
        UUID agentId = stubExistingAgent(newAgent());

        AgentResponse response = service.updateModel(agentId, ProviderName.ANTHROPIC, "claude-sonnet-5");

        assertEquals("claude-sonnet-5", response.model());
        verify(repository).save(any(Agent.class));
    }

    @Test
    void paidTenantOptedIntoPlatformLlmIsStillCatalogGated() {
        // "every non free tenant should get an option to select use platform llm":
        // a PAID tenant that opted in routes through the hosted pool exactly like
        // FREE, so it doesn't pick its own model - the catalog stays an allowlist.
        // Same answer for a tenant inside a live complimentary grant, which is
        // what TenantLimits#usesPlatformPool now folds in.
        TenantContext.set(TenantContext.of("acme", "user-1", Set.of()));
        when(tenantLimits.plan("acme")).thenReturn(TenantPlan.PREMIUM);
        when(tenantLimits.usesPlatformPool("acme")).thenReturn(true);
        when(providerModelCatalogService.findModel(ProviderName.OPENAI, "gpt-6-experimental-0731"))
            .thenReturn(Optional.empty());
        UUID agentId = stubExistingAgent(newAgent());

        assertThrows(
            AgentModelNotSupportedException.class,
            () -> service.updateModel(agentId, ProviderName.OPENAI, "gpt-6-experimental-0731")
        );
        verify(repository, never()).save(any(Agent.class));
    }
}
