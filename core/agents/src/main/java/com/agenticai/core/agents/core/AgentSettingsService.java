package com.agenticai.core.agents.core;

import com.agenticai.core.agents.core.AgentSettingsPatch.Facet;
import com.agenticai.core.agents.dto.AgentGenerationParamsUpdateRequest;
import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.sharing.PermissionLevel;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies an {@link AgentSettingsPatch} to an agent: the one place that runs "require EDITOR
 * access, check the edit-lock, mutate through the facet's applier, save a versioned snapshot".
 * Each {@link Facet} has exactly one {@link FacetApplier} (see {@link AgentSettingsFacets}).
 * {@link AgentService} keeps its public {@code setX} methods as one-line delegates onto {@link
 * #apply}.
 */
@Service
public class AgentSettingsService {

    /** Mutates the agent for one facet and returns the change summary for the version history. */
    public interface FacetApplier {
        Facet facet();

        String apply(Agent agent, AgentSettingsPatch patch);
    }

    private final AgentAccessService access;
    private final AgentVersioningService versioning;
    private final Map<Facet, FacetApplier> appliers = new EnumMap<>(Facet.class);

    public AgentSettingsService(
            AgentAccessService access,
            AgentVersioningService versioning,
            GenerationParamsInferenceService generationParamsInferenceService,
            AgentModelResolver modelResolver,
            AgentSchemaService agentSchemaService) {
        this.access = access;
        this.versioning = versioning;
        List<FacetApplier> all =
                AgentSettingsFacets.all(
                        generationParamsInferenceService, modelResolver, agentSchemaService);
        for (FacetApplier applier : all) {
            appliers.put(applier.facet(), applier);
        }
    }

    @Transactional
    public AgentResponse apply(UUID agentId, AgentSettingsPatch patch) {
        Agent agent = access.requireAccess(agentId, PermissionLevel.EDITOR);
        access.assertEditable(agent);
        FacetApplier applier = appliers.get(patch.facet());
        if (applier == null) {
            throw new IllegalStateException("No applier registered for facet " + patch.facet());
        }
        String summary = applier.apply(agent, patch);
        return versioning.saveVersioned(agent, summary);
    }

    /** See {@link AgentSettingsFacets#applySamplingUpdate}. */
    static void applySamplingUpdate(Agent agent, AgentGenerationParamsUpdateRequest request) {
        AgentSettingsFacets.applySamplingUpdate(agent, request);
    }
}
