package com.agenticai.core.agents.collab;

import com.agenticai.core.agents.tools.StepJournal;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.security.TenantContext;
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
                chatService, agentId, capturedContext, runBudget, stepJournal, sessionId);
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
            String goal) {
        SwarmVerifier verifier =
                verifierModel == null || verifierConfig == null || !verifierConfig.enabled()
                        ? null
                        : new SwarmVerifier(verifierConfig, verifierModel::complete, meters);
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
                goal);
    }
}
