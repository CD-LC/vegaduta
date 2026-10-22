package com.agenticai.core.agents.core;

import com.agenticai.core.agents.capability.AgentCapabilityRegistry;
import com.agenticai.core.agents.modality.ModelModalitiesService;
import com.agenticai.core.agents.version.AgentConfigSnapshotCodec;
import com.agenticai.core.agents.version.AgentVersionSnapshotService;
import com.agenticai.core.providers.ProviderModelCatalogService;
import com.agenticai.core.providers.modality.TextModelProviderValidator;
import com.agenticai.core.providers.modality.TextToSpeechProviderValidator;
import com.agenticai.core.providers.modality.VideoProviderValidator;
import com.agenticai.core.providers.modality.VoiceProviderValidator;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.sharing.ResourceAccessService;
import java.util.List;

/**
 * Hand-wires an {@link AgentService} and the focused services it delegates to, for unit tests that
 * do not start Spring. Mirrors the shape of the old long {@code AgentService} constructors so those
 * tests keep their collaborator mocks; the production wiring is plain constructor injection.
 */
final class AgentServiceTestFactory {

    private AgentServiceTestFactory() {}

    /** Versioning off, empty capability catalog, born-unguarded default (no default guardrails). */
    static AgentService create(
            AgentRepository repository,
            TenantLimits tenantLimits,
            GenerationParamsInferenceService generationParamsInferenceService,
            ProviderModelCatalogService providerModelCatalogService,
            ModelModalitiesService modelModalitiesService,
            PromptVersionRepository promptVersionRepository,
            VoiceProviderValidator voiceProviderValidator,
            VideoProviderValidator videoProviderValidator,
            TextModelProviderValidator textModelProviderValidator,
            TextToSpeechProviderValidator ttsProviderValidator,
            ResourceAccessService resourceAccessService,
            AgentSchemaService agentSchemaService,
            String defaultModel,
            int defaultMaxTokens,
            double defaultTemperature,
            double defaultTopP) {
        return create(
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
                agentSchemaService,
                AgentVersionSnapshotService.disabled(),
                new AgentConfigSnapshotCodec(new tools.jackson.databind.ObjectMapper()),
                new AgentCapabilityRegistry(List.of()),
                defaultModel,
                defaultMaxTokens,
                defaultTemperature,
                defaultTopP,
                "");
    }

    static AgentService create(
            AgentRepository repository,
            TenantLimits tenantLimits,
            GenerationParamsInferenceService generationParamsInferenceService,
            ProviderModelCatalogService providerModelCatalogService,
            ModelModalitiesService modelModalitiesService,
            PromptVersionRepository promptVersionRepository,
            VoiceProviderValidator voiceProviderValidator,
            VideoProviderValidator videoProviderValidator,
            TextModelProviderValidator textModelProviderValidator,
            TextToSpeechProviderValidator ttsProviderValidator,
            ResourceAccessService resourceAccessService,
            AgentSchemaService agentSchemaService,
            AgentVersionSnapshotService agentVersionSnapshotService,
            AgentConfigSnapshotCodec agentConfigSnapshotCodec,
            AgentCapabilityRegistry capabilityRegistry,
            String defaultModel,
            int defaultMaxTokens,
            double defaultTemperature,
            double defaultTopP,
            String defaultGuardrails) {
        AgentDefaults defaults =
                new AgentDefaults(
                        defaultModel,
                        defaultMaxTokens,
                        defaultTemperature,
                        defaultTopP,
                        defaultGuardrails);
        AgentProviderValidators validators =
                new AgentProviderValidators(
                        voiceProviderValidator,
                        videoProviderValidator,
                        textModelProviderValidator,
                        ttsProviderValidator);
        AgentAccessService access =
                new AgentAccessService(repository, tenantLimits, resourceAccessService);
        AgentModelResolver modelResolver =
                new AgentModelResolver(providerModelCatalogService, repository, defaults);
        AgentResponseAssembler assembler =
                new AgentResponseAssembler(access, modelModalitiesService, modelResolver);
        AgentVersioningService versioning =
                new AgentVersioningService(repository, agentVersionSnapshotService, assembler);
        AgentCapabilityService capabilityService =
                new AgentCapabilityService(capabilityRegistry, access, versioning);
        AgentSettingsService settings =
                new AgentSettingsService(
                        access,
                        versioning,
                        generationParamsInferenceService,
                        modelResolver,
                        agentSchemaService);
        AgentModelSelectionService modelSelection =
                new AgentModelSelectionService(
                        access,
                        versioning,
                        tenantLimits,
                        providerModelCatalogService,
                        validators,
                        agentSchemaService);
        AgentCreationService creation =
                new AgentCreationService(
                        repository,
                        tenantLimits,
                        generationParamsInferenceService,
                        providerModelCatalogService,
                        validators,
                        agentSchemaService,
                        agentConfigSnapshotCodec,
                        access,
                        versioning,
                        modelResolver,
                        defaults);
        return new AgentService(
                repository,
                promptVersionRepository,
                agentSchemaService,
                access,
                assembler,
                versioning,
                modelResolver,
                capabilityService,
                settings,
                modelSelection,
                creation);
    }
}
