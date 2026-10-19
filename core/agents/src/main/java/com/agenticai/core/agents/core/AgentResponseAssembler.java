package com.agenticai.core.agents.core;

import com.agenticai.core.agents.dto.AgentModalitiesView;
import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.agents.modality.ModelModalities;
import com.agenticai.core.agents.modality.ModelModalitiesService;
import com.agenticai.core.agents.tools.e2b.SandboxDataAvailability;
import com.agenticai.core.agents.tools.e2b.SandboxFileRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Builds the {@link AgentResponse} returned by every agent read/write: the entity, whether the
 * caller may still edit it, and the multimodal {@code modalities} view. Behavior moved verbatim
 * from {@link AgentService}.
 */
@Service
public class AgentResponseAssembler {

    private static final Logger log = LoggerFactory.getLogger(AgentResponseAssembler.class);

    private final AgentAccessService access;
    private final ModelModalitiesService modelModalitiesService;
    private final AgentModelResolver modelResolver;

    private volatile ObjectProvider<SandboxFileRegistry> sandboxFileRegistry;

    public AgentResponseAssembler(
            AgentAccessService access,
            ModelModalitiesService modelModalitiesService,
            AgentModelResolver modelResolver) {
        this.access = access;
        this.modelModalitiesService = modelModalitiesService;
        this.modelResolver = modelResolver;
    }

    /** Setter-injected (the constructors are test-built); absent = data-analysis sandbox off. */
    @Autowired
    public void setSandboxFileRegistry(ObjectProvider<SandboxFileRegistry> provider) {
        this.sandboxFileRegistry = provider;
    }

    public AgentResponse toResponse(Agent agent) {
        return AgentResponse.from(
                agent, access.isEditable(agent), modalitiesView(agent), access.accessLevelOf(agent));
    }

    /** For the grant-based MCP path, which has no share-level context to report. */
    public AgentResponse toResponseWithoutAccessLevel(Agent agent) {
        return AgentResponse.from(agent, access.isEditable(agent), modalitiesView(agent), null);
    }

    /**
     * The {@code modalities} object on every agent response. Best-effort: a modality-resolution
     * hiccup degrades to text-only (attach controls withheld, never wrongly offered).
     */
    private AgentModalitiesView modalitiesView(Agent agent) {
        var sandbox = sandboxDataAvailability(agent.isE2bEnabled());
        boolean sandboxData = sandbox.available();
        try {
            return AgentModalitiesView.of(
                    modelModalitiesService.resolve(
                            agent.getProviderName(), modelResolver.resolveEffectiveModel(agent)),
                    sandboxData,
                    sandbox.unavailableReason());
        } catch (RuntimeException e) {
            log.debug(
                    "Modality resolution failed for agent {} - reporting text-only",
                    agent.getId(),
                    e);
            return AgentModalitiesView.of(
                    ModelModalities.NONE, sandboxData, sandbox.unavailableReason());
        }
    }

    /**
     * Same predicate as the upload gate and the chat turn: flag + agent attachment + tenant E2B
     * key/template.
     */
    private SandboxDataAvailability sandboxDataAvailability(boolean agentCodeExecution) {
        var provider = sandboxFileRegistry;
        var registry = provider == null ? null : provider.getIfAvailable();
        return SandboxDataAvailability.of(registry, agentCodeExecution);
    }
}
