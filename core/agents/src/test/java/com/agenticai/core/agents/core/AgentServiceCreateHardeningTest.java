package com.agenticai.core.agents.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.capability.AgentCapabilityRegistry;
import com.agenticai.core.agents.dto.AgentCreateRequest;
import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.agents.modality.ModelModalitiesService;
import com.agenticai.core.agents.version.AgentConfigSnapshotCodec;
import com.agenticai.core.agents.version.AgentVersionSnapshotService;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Covers the 2026-10-02 creation-path hardening (see the audit notes on {@link
 * AgentService#create}): the model-catalog allowlist {@code updateModel} already
 * enforced is now applied at create time for platform-pool tenants, every new
 * agent is seeded with the operator-configured default guardrail set instead of
 * being born unguarded, and {@code updatedBy} is stamped on the first save.
 */
class AgentServiceCreateHardeningTest {

    private AgentRepository repository;
    private TenantLimits tenantLimits;
    private ProviderModelCatalogService providerModelCatalogService;
    private AgentService service;

    @BeforeEach
    void setUp() {
        repository = mock(AgentRepository.class);
        tenantLimits = mock(TenantLimits.class);
        providerModelCatalogService = mock(ProviderModelCatalogService.class);
        service = newAgentService("jailbreak");
        when(repository.save(any(Agent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        TenantContext.set(TenantContext.of("acme", "user-1", Set.of()));
        when(tenantLimits.maxAgents("acme")).thenReturn(Optional.empty());
        when(tenantLimits.plan("acme")).thenReturn(TenantPlan.PREMIUM);
        when(tenantLimits.experienceMode("acme")).thenReturn(ExperienceMode.ADVANCED);
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    /** The full (Spring-shaped) constructor, so {@code defaultGuardrails} is exercisable. */
    private AgentService newAgentService(String defaultGuardrails) {
        return AgentServiceTestFactory.create(
            repository,
            tenantLimits,
            mock(GenerationParamsInferenceService.class),
            providerModelCatalogService,
            mock(ModelModalitiesService.class),
            mock(PromptVersionRepository.class),
            mock(VoiceProviderValidator.class),
            mock(VideoProviderValidator.class),
            mock(TextModelProviderValidator.class),
            mock(TextToSpeechProviderValidator.class),
            mock(ResourceAccessService.class),
            mock(AgentSchemaService.class),
            AgentVersionSnapshotService.disabled(),
            new AgentConfigSnapshotCodec(new com.fasterxml.jackson.databind.ObjectMapper()),
            new AgentCapabilityRegistry(List.of()),
            "claude-sonnet-5",
            1024,
            0.7,
            1.0,
            defaultGuardrails
        );
    }

    private static AgentCreateRequest createRequest(String model) {
        return new AgentCreateRequest(
            "Test Agent", "You are helpful.", ProviderName.ANTHROPIC, model,
            null, null, null, null, null, null, null, null,
            null, null, null, null, null, null
        );
    }

    @Test
    void platformPoolTenantOffCatalogModelIsRejectedAtCreate() {
        when(tenantLimits.usesPlatformPool("acme")).thenReturn(true);
        when(providerModelCatalogService.findModel(ProviderName.ANTHROPIC, "claude-made-up-9"))
            .thenReturn(Optional.empty());

        assertThrows(
            AgentModelNotSupportedException.class,
            () -> service.create(createRequest("claude-made-up-9"))
        );
        verify(repository, never()).save(any(Agent.class));
    }

    @Test
    void platformPoolTenantOnCatalogModelIsAcceptedAtCreate() {
        when(tenantLimits.usesPlatformPool("acme")).thenReturn(true);
        when(providerModelCatalogService.findModel(ProviderName.ANTHROPIC, "claude-sonnet-5"))
            .thenReturn(Optional.of(mock(ProviderModelResponse.class)));

        AgentResponse response = service.create(createRequest("claude-sonnet-5"));

        assertEquals("claude-sonnet-5", response.model());
        verify(repository).save(any(Agent.class));
    }

    @Test
    void byokTenantMayCreateWithAnOffCatalogModel() {
        // Same posture as updateModel: for a tenant driving its own key the
        // catalog is a suggestion list, not an allowlist.
        when(tenantLimits.usesPlatformPool("acme")).thenReturn(false);

        AgentResponse response = service.create(createRequest("claude-opus-9-preview"));

        assertEquals("claude-opus-9-preview", response.model());
        verify(providerModelCatalogService, never()).findModel(any(), any());
    }

    @Test
    void newAgentIsSeededWithTheConfiguredDefaultGuardrails() {
        when(tenantLimits.usesPlatformPool("acme")).thenReturn(false);

        service.create(createRequest("claude-sonnet-5"));

        ArgumentCaptor<Agent> captor = ArgumentCaptor.forClass(Agent.class);
        verify(repository).save(captor.capture());
        assertEquals(Set.of("jailbreak"), captor.getValue().getEnabledGuardrails());
    }

    @Test
    void emptyDefaultGuardrailsPropertyRestoresBornUnguarded() {
        AgentService unseeded = newAgentService("  ");
        when(tenantLimits.usesPlatformPool("acme")).thenReturn(false);

        unseeded.create(createRequest("claude-sonnet-5"));

        ArgumentCaptor<Agent> captor = ArgumentCaptor.forClass(Agent.class);
        verify(repository).save(captor.capture());
        assertTrue(captor.getValue().getEnabledGuardrails().isEmpty());
    }

    @Test
    void createStampsUpdatedByWithTheCallersSubject() {
        when(tenantLimits.usesPlatformPool("acme")).thenReturn(false);

        AgentResponse response = service.create(createRequest("claude-sonnet-5"));

        assertEquals("user-1", response.updatedBy());
        assertEquals("user-1", response.createdBy());
    }
}
