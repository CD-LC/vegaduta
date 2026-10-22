package com.agenticai.core.agents.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.capability.AgentCapabilityRegistry;
import com.agenticai.core.agents.dto.AgentGenerationParamsUpdateRequest;
import com.agenticai.core.agents.modality.ModelModalitiesService;
import com.agenticai.core.agents.version.AgentConfigSnapshotCodec;
import com.agenticai.core.agents.version.AgentVersionSnapshotService;
import com.agenticai.core.providers.ProviderModelCatalogService;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.providers.modality.TextModelProviderValidator;
import com.agenticai.core.providers.modality.TextToSpeechProviderValidator;
import com.agenticai.core.providers.modality.VideoProviderValidator;
import com.agenticai.core.providers.modality.VoiceProviderValidator;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.sharing.ResourceAccessDeniedException;
import com.agenticai.core.sharing.ResourceAccessService;
import com.agenticai.core.sharing.ResourceType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Characterisation of the per-field configuration setters on {@link AgentService}: for each one the
 * mutation applied to the entity, the exact version-history summary written, and the shared "EDITOR
 * access first, nothing saved on denial" sequence. Written against the public facade API only, so
 * it pins the same behavior whether the setters are inline methods (the old shape) or delegate to
 * {@link AgentSettingsService} (the current shape).
 */
class AgentSettingsCharacterisationTest {

    private AgentRepository repository;
    private ResourceAccessService resourceAccess;
    private AgentVersionSnapshotService snapshots;
    private GenerationParamsInferenceService inference;
    private AgentSchemaService schema;
    private AgentService service;
    private Agent agent;
    private UUID id;

    @BeforeEach
    void setUp() {
        repository = mock(AgentRepository.class);
        resourceAccess = mock(ResourceAccessService.class);
        snapshots = mock(AgentVersionSnapshotService.class);
        inference = mock(GenerationParamsInferenceService.class);
        schema = mock(AgentSchemaService.class);
        ProviderModelCatalogService catalog = mock(ProviderModelCatalogService.class);
        when(catalog.replacementForRetiredModel(any())).thenReturn(Optional.empty());
        service =
                AgentServiceTestFactory.create(
                        repository,
                        mock(TenantLimits.class),
                        inference,
                        catalog,
                        mock(ModelModalitiesService.class),
                        mock(PromptVersionRepository.class),
                        mock(VoiceProviderValidator.class),
                        mock(VideoProviderValidator.class),
                        mock(TextModelProviderValidator.class),
                        mock(TextToSpeechProviderValidator.class),
                        resourceAccess,
                        schema,
                        snapshots,
                        new AgentConfigSnapshotCodec(
                                new com.fasterxml.jackson.databind.ObjectMapper()),
                        new AgentCapabilityRegistry(List.of()),
                        "claude-sonnet-5",
                        1024,
                        0.7,
                        1.0,
                        "");
        agent =
                new Agent(
                        "A",
                        "prompt",
                        ProviderName.ANTHROPIC,
                        "claude-sonnet-5",
                        1024,
                        0.7,
                        1.0,
                        false,
                        null,
                        ReasoningDepth.FAST,
                        TuningMode.MANUAL);
        id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(agent));
        when(repository.save(any(Agent.class))).thenAnswer(i -> i.getArgument(0));
    }

    private String lastSummary() {
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(snapshots).snapshot(eq(agent), summary.capture());
        return summary.getValue();
    }

    @Test
    void reasoningDepthAndAutonomyAndRunCap() {
        service.setReasoningDepth(id, ReasoningDepth.PLANNED);
        assertThat(agent.getReasoningDepth()).isEqualTo(ReasoningDepth.PLANNED);
        assertThat(lastSummary()).isEqualTo("Changed reasoning depth to PLANNED");
    }

    @Test
    void autonomyTier() {
        service.setAutonomyTier(id, AgentAutonomyTier.SUPERVISED);
        assertThat(agent.getAutonomyTier()).isEqualTo(AgentAutonomyTier.SUPERVISED);
        assertThat(lastSummary()).isEqualTo("Changed autonomy tier to SUPERVISED");
    }

    @Test
    void runBudget() {
        service.setRunBudgetUsd(id, new BigDecimal("2.50"));
        assertThat(agent.getRunBudgetUsd()).isEqualByComparingTo("2.50");
        assertThat(lastSummary()).isEqualTo("Changed run cap");
    }

    @Test
    void cachingToggles() {
        service.setResponseCachingEnabled(id, true);
        assertThat(agent.isResponseCachingEnabled()).isTrue();
        service.setSemanticResponseCachingEnabled(id, true);
        assertThat(agent.isSemanticResponseCachingEnabled()).isTrue();
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(snapshots, org.mockito.Mockito.times(2)).snapshot(eq(agent), summary.capture());
        assertThat(summary.getAllValues())
                .containsExactly(
                        "Enabled exact-match response caching",
                        "Enabled semantic response caching");
    }

    @Test
    void capabilityFlagsWithTheirSummaries() {
        service.setKnowledgeBaseEnabled(id, true);
        service.setKnowledgeWriteEnabled(id, true);
        service.setShareMemoryWithTenant(id, true);
        service.setBiometricConsentEnabled(id, false);
        assertThat(agent.isKnowledgeBaseEnabled()).isTrue();
        assertThat(agent.isKnowledgeWriteEnabled()).isTrue();
        assertThat(agent.isShareMemoryWithTenant()).isTrue();
        assertThat(agent.isBiometricConsentEnabled()).isFalse();
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(snapshots, org.mockito.Mockito.times(4)).snapshot(eq(agent), summary.capture());
        assertThat(summary.getAllValues())
                .containsExactly(
                        "Enabled knowledge base",
                        "Enabled knowledge write tool",
                        "Enabled tenant-shared memory",
                        "Withdrew biometric consent");
        // the knowledge-base switch changes the derived tool/knowledge schema; the others do not
        verify(schema).refresh(agent);
    }

    @Test
    void generationParamsAppliesSamplingAndRefreshesSchema() {
        service.updateGenerationParams(
                id,
                new AgentGenerationParamsUpdateRequest(
                        2048, 0.2, 0.9, 40, List.of("END"), 0.5, 0.25, 9, null));
        assertThat(agent.getMaxTokens()).isEqualTo(2048);
        assertThat(agent.getTemperature()).isEqualTo(0.2);
        assertThat(agent.getTopP()).isEqualTo(0.9);
        assertThat(agent.getTopK()).isEqualTo(40);
        assertThat(agent.getStopSequences()).containsExactly("END");
        assertThat(agent.getSeed()).isEqualTo(9);
        verify(schema).refresh(agent);
        assertThat(lastSummary())
                .isEqualTo("Changed generation params (maxTokens/temperature/topP/sampling)");
    }

    @Test
    void tuningModeSimpleReinfersParamsManualOnlyFlipsFlag() {
        when(inference.infer(any(), any(), any()))
                .thenReturn(new GenerationParamsInferenceService.InferredParams(333, 0.1, 0.5));
        service.setTuningMode(id, TuningMode.SIMPLE);
        assertThat(agent.getTuningMode()).isEqualTo(TuningMode.SIMPLE);
        assertThat(agent.getMaxTokens()).isEqualTo(333);
        assertThat(agent.getTemperature()).isEqualTo(0.1);

        service.setTuningMode(id, null);
        assertThat(agent.getTuningMode()).isEqualTo(TuningMode.MANUAL);
        assertThat(agent.getMaxTokens()).isEqualTo(333);
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(snapshots, org.mockito.Mockito.times(2)).snapshot(eq(agent), summary.capture());
        assertThat(summary.getAllValues())
                .containsExactly("Changed tuning mode to SIMPLE", "Changed tuning mode to MANUAL");
    }

    @Test
    void edgeRoutingNullClears() {
        service.setEdgeRoutingPolicy(id, null);
        assertThat(agent.getEdgeRoutingPolicy()).isNull();
        assertThat(lastSummary())
                .isEqualTo("Cleared edge routing policy (device preferences decide)");
    }

    @Test
    void scopesAndTheirSummaries() {
        UUID c = UUID.randomUUID();
        service.setAllowedKnowledgeCollectionIds(id, Set.of(c));
        service.setAllowedKnowledgeWriteCollectionIds(id, Set.of());
        service.setAllowedCodeIntelRepoIds(id, Set.of("r1", "r2"));
        assertThat(agent.getAllowedKnowledgeCollectionIds()).containsExactly(c);
        assertThat(agent.getAllowedCodeIntelRepoIds()).containsExactlyInAnyOrder("r1", "r2");
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(snapshots, org.mockito.Mockito.times(3)).snapshot(eq(agent), summary.capture());
        assertThat(summary.getAllValues())
                .containsExactly(
                        "Changed knowledge collection scope (1 collections)",
                        "Revoked all knowledge-write collection access",
                        "Changed code-intel repo access (2 repos)");
    }

    @Test
    void settersRequireEditorAccessAndSaveNothingOnDenial() {
        doThrow(new ResourceAccessDeniedException())
                .when(resourceAccess)
                .requireAccess(
                        eq(ResourceType.AGENT), eq(id), isNull(), eq(PermissionLevel.EDITOR));
        assertThatThrownBy(() -> service.setReasoningDepth(id, ReasoningDepth.PLANNED))
                .isInstanceOf(ResourceAccessDeniedException.class);
        assertThatThrownBy(() -> service.setKnowledgeBaseEnabled(id, true))
                .isInstanceOf(ResourceAccessDeniedException.class);
        verify(repository, never()).save(any(Agent.class));
        verify(snapshots, never()).snapshot(any(), any());
        assertThat(agent.getReasoningDepth()).isEqualTo(ReasoningDepth.FAST);
    }

    @Test
    void unknownAgentIs404BeforeAnyAccessCheck() {
        UUID missing = UUID.randomUUID();
        when(repository.findById(missing)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.setAutonomyTier(missing, AgentAutonomyTier.SUPERVISED))
                .isInstanceOf(AgentNotFoundException.class);
        verify(resourceAccess, never()).requireAccess(any(), any(), any(), any());
    }
}
