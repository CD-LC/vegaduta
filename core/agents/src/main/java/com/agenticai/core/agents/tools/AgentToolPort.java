package com.agenticai.core.agents.tools;

import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.agents.crew.HandoffChain;
import com.agenticai.core.agents.turn.TurnContext;
import java.util.List;
import java.util.UUID;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;

/**
 * Port (ADR-0023): the tool list an agent's chat call gets. The kernel and the turn path (the
 * agent controller, the edge tool endpoints, {@code AgentChatService} and its turn collaborators,
 * {@link TrifectaEnforcementService}) depend on this interface; the implementation is {@code
 * AgentToolProvider} in core/agents-tools, which owns the tool resolvers, the MCP connection
 * manager and every built-in tool. Each method behaves exactly as the implementation documents it;
 * see that class for the details.
 */
public interface AgentToolPort {

    /** Evicts the cached MCP tool lists for the current tenant (call with the tenant set). */
    void evictMcpToolCache();

    /** The guarded, logged tool list for one chat turn. */
    List<ToolCallback> resolveToolsFor(
            UUID agentId,
            String sessionId,
            String subjectKey,
            String userMessage,
            ChatClient chatClient,
            ReasoningDepth effectiveDepth);

    /** As above, with the turn's step-event emitter. */
    List<ToolCallback> resolveToolsFor(
            UUID agentId,
            String sessionId,
            String subjectKey,
            String userMessage,
            ChatClient chatClient,
            AgentStepEventEmitter emitter,
            ReasoningDepth effectiveDepth);

    /** As above, recording the resolution on the turn's context. */
    List<ToolCallback> resolveToolsFor(
            TurnContext context,
            UUID agentId,
            String sessionId,
            String subjectKey,
            String userMessage,
            ChatClient chatClient,
            AgentStepEventEmitter emitter,
            ReasoningDepth effectiveDepth);

    /** Every tool the agent could be offered, before relevance narrowing (edge, MCP Apps). */
    List<ToolCallback> resolveToolCandidatesFor(UUID agentId, String sessionId, String subjectKey);

    /** The tool list for a delegated (agent-to-agent) call. */
    List<ToolCallback> resolveToolsForDelegatedCall(
            UUID agentId, String sessionId, String userMessage, ChatClient chatClient);

    /** The tool list for a specialist hand-off target. */
    List<ToolCallback> resolveToolsForSpecialistHandoff(
            UUID agentId,
            String sessionId,
            String userMessage,
            ChatClient chatClient,
            HandoffChain chain);

    /** The tools the agent can use, as descriptors (the agent tools endpoint, allow-list checks). */
    List<ToolDescriptor> listAvailableToolsFor(UUID agentId);

    /** Whether any MCP tool is offered to this agent for this turn. */
    boolean anyMcpToolsOfferedFor(
            UUID agentId, String sessionId, String userMessage, ChatClient chatClient);

    /** The skills block of the system prompt. */
    String skillsPromptBlock(UUID agentId, List<ToolCallback> tools);

    /** The skills block of the system prompt, compact for small models when asked. */
    String skillsPromptBlock(UUID agentId, List<ToolCallback> tools, boolean compact);

    /** The procedures block of the system prompt. */
    String proceduresPromptBlock(UUID agentId);
}
