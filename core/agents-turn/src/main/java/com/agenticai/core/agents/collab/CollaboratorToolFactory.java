package com.agenticai.core.agents.collab;

import com.agenticai.core.agents.tools.StepJournal;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.security.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import org.springframework.ai.tool.ToolCallback;

/**
 * Port (ADR-0023): builds the collaborator delegation tools ({@code ask_agent_<slug>}, the
 * parallel team tool and {@code run_subtasks}) for the tool resolver in {@code core/agents}. The tool classes ({@code
 * DelegateToAgentToolCallback}, {@code DelegateToTeamToolCallback}, {@code SwarmVerifier}) live
 * in {@code core/agents-interop}, which implements this. When no implementation is on the
 * classpath, no collaborator tools are offered.
 */
public interface CollaboratorToolFactory {

    /** One addressable member of a team tool. */
    record TeamMember(UUID agentId, String name, String slug) {}

    /** The model call the swarm goal verifier makes: system prompt and user prompt in, text out. */
    @FunctionalInterface
    interface VerifierModel {
        String complete(String system, String user);
    }

    /** The {@code ask_agent_<slug>} tool for one collaborator. */
    ToolCallback delegateToAgent(
            AgentChatService chatService,
            UUID collaboratorAgentId,
            String toolName,
            String collaboratorName,
            String collaboratorDescription);

    /**
     * The {@code run_subtasks} tool: parallel ad hoc workers that are the calling agent itself, so
     * no collaborators are needed. Same durability and budget arguments as the team tool.
     */
    ToolCallback runSubtasks(
            AgentChatService chatService,
            UUID agentId,
            TenantContext capturedContext,
            RunBudget runBudget,
            StepJournal stepJournal,
            String sessionId);

    /**
     * The parallel team tool over two or more members.
     *
     * @param verifierModel null = no swarm goal verifier (also when {@code verifierConfig} is
     *     disabled)
     * @param callerAgentId the agent whose turn offers the tool: its {@code delegation.*} settings (and its
     *     workspace's) bound the fan-out; null = workspace level
     */
    ToolCallback delegateToTeam(
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
            UUID callerAgentId);
}
