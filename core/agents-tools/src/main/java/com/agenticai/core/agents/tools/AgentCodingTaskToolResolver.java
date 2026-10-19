package com.agenticai.core.agents.tools;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.job.AgentJobSession;
import com.agenticai.core.agents.tools.coding.CodingTaskToolFactory;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Tool-resolution group for {@code coding_task}. Not a Spring bean: constructed by {@link
 * AgentToolProvider}, which hands over the optional tool factory by setter so the provider's
 * (test-constructed) constructor signature stays untouched. The factory is the {@link
 * CodingTaskToolFactory} port, implemented in {@code core/agents-studio} (ADR-0023).
 *
 * <p>Offer rule for a chat turn - ALL of: {@code app.coding-harness.enabled} is on and the service
 * bean exists; the agent names the tool in its allow-list (the per-agent opt-in {@code
 * schedule_task} and {@code a2a_call_agent} use - an empty allow-list means "unrestricted", which
 * is deliberately NOT an opt-in); and the turn is NOT an unattended job (a task needs a person to
 * approve its diff, and a job has none). No match = the tool is not built and costs zero prompt
 * tokens, so with the flag off (the default) this class is an identity function over the tool list.
 * Tool DISCOVERY (the admin picker) lists the tool whenever the flag allows, otherwise nothing
 * could ever be opted in from the picker.
 */
final class AgentCodingTaskToolResolver {

    private final AgentToolGuard toolGuard;
    private final AgentService agentService;
    private volatile ObjectProvider<CodingTaskToolFactory> factory;

    AgentCodingTaskToolResolver(AgentToolGuard toolGuard, AgentService agentService) {
        this.toolGuard = toolGuard;
        this.agentService = agentService;
    }

    void setFactory(ObjectProvider<CodingTaskToolFactory> factory) {
        this.factory = factory;
    }

    /**
     * Tools for a real turn (or an approval-replay lookup): flag on, agent opted in, not inside a
     * job.
     */
    List<ToolCallback> resolveCodingTaskTools(
            UUID agentId,
            String sessionId,
            AtomicInteger stepCounter,
            String userMessage,
            ChatClient chatClient,
            boolean rigorous) {
        if (agentId == null || AgentJobSession.isJobSession(sessionId)) {
            return List.of();
        }
        ToolCallback tool = buildIfEnabled(agentId);
        if (tool == null || !allowList(agentId).contains(CodingTaskToolFactory.TOOL_NAME)) {
            return List.of();
        }
        return List.of(
                toolGuard.guardAndLog(
                        tool, agentId, sessionId, stepCounter, userMessage, chatClient, rigorous));
    }

    /** Tools for the admin tool picker: offered whenever the flag is on. */
    List<ToolCallback> resolveCodingTaskToolsForDiscovery(
            UUID agentId,
            String sessionId,
            AtomicInteger stepCounter,
            String userMessage,
            ChatClient chatClient,
            boolean rigorous) {
        ToolCallback tool = buildIfEnabled(agentId);
        if (tool == null) {
            return List.of();
        }
        return List.of(
                toolGuard.guardAndLog(
                        tool, agentId, sessionId, stepCounter, userMessage, chatClient, rigorous));
    }

    private ToolCallback buildIfEnabled(UUID agentId) {
        ObjectProvider<CodingTaskToolFactory> provider = factory;
        if (provider == null) {
            return null;
        }
        CodingTaskToolFactory f = provider.getIfAvailable();
        return f == null ? null : f.createIfEnabled(agentId);
    }

    private Set<String> allowList(UUID agentId) {
        if (agentService == null || agentId == null) {
            return Set.of();
        }
        try {
            Set<String> allowed = agentService.getAllowedToolNames(agentId);
            return allowed == null ? Set.of() : allowed;
        } catch (RuntimeException e) {
            return Set.of();
        }
    }
}
