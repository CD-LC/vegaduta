package com.agenticai.core.agents.collab;

import com.agenticai.core.agents.tools.StepJournal;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.settings.SettingsResolver;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

/**
 * Implements the {@link CollaboratorToolFactory} port from {@code core/agents} (ADR-0023): builds
 * exactly the delegation tools the tool resolver used to construct inline.
 */
@Component
class DefaultCollaboratorToolFactory implements CollaboratorToolFactory {

    /** Bounds of the fan-out tools ({@code app.agents.collaboration.*}); the shipped defaults until injected. */
    private volatile CollaborationProperties limits = new CollaborationProperties();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void useCollaborationProperties(CollaborationProperties properties) {
        this.limits = properties;
    }

    /** The settings catalog ({@code delegation.*}); null = the platform values of {@link #limits} only. */
    private volatile SettingsResolver settings;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void useSettings(SettingsResolver resolver) {
        this.settings = resolver;
    }

    /** The prompt registry the swarm verifier reads its system prompt from; null = the shipped text. */
    private volatile PromptResolver promptResolver;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void usePromptResolver(PromptResolver resolver) {
        this.promptResolver = resolver;
    }

    @Override
    public ToolCallback delegateToAgent(
            AgentChatService chatService,
            UUID collaboratorAgentId,
            String toolName,
            String collaboratorName,
            String collaboratorDescription) {
        return new DelegateToAgentToolCallback(
                chatService, collaboratorAgentId, toolName, collaboratorName, collaboratorDescription);
    }

    @Override
    public ToolCallback runSubtasks(
            AgentChatService chatService,
            UUID agentId,
            TenantContext capturedContext,
            RunBudget runBudget,
            StepJournal stepJournal,
            String sessionId) {
        return new RunSubtasksToolCallback(
                        chatService, agentId, capturedContext, runBudget, stepJournal, sessionId)
                .withLimits(limits)
                .withSettings(settings)
                .withPromptResolver(promptResolver);
    }

    @Override
    public ToolCallback delegateToTeam(
            AgentChatService chatService,
            List<TeamMember> members,
            TenantContext capturedContext,
            RunBudget runBudget,
            StepJournal stepJournal,
            String sessionId,
            SwarmVerifierConfig verifierConfig,
            VerifierModel verifierModel,
            MeterRegistry meters,
            String goal,
            UUID callerAgentId) {
        SwarmVerifier verifier =
                verifierModel == null || verifierConfig == null || !verifierConfig.enabled()
                        ? null
                        : new SwarmVerifier(verifierConfig, verifierModel::complete, meters)
                                .withMaxRepairBranches(
                                        new ResolvedCollaborationLimits(limits, settings, callerAgentId, capturedContext)
                                                .maxBranches())
                                .withPromptResolver(promptResolver);
        List<DelegateToTeamToolCallback.Member> teamMembers =
                members.stream()
                        .map(m -> new DelegateToTeamToolCallback.Member(m.agentId(), m.name(), m.slug()))
                        .toList();
        return new DelegateToTeamToolCallback(
                        chatService,
                        teamMembers,
                        capturedContext,
                        runBudget,
                        stepJournal,
                        sessionId,
                        verifier,
                        goal)
                .withLimits(limits)
                .withSettings(settings, callerAgentId);
    }
}
