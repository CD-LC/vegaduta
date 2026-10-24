package com.agenticai.core.agents.tools;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.job.AgentJobSession;
import com.agenticai.core.agents.tools.scheduling.BackgroundJobToolFactory;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Tool-resolution group for {@code continue_in_background}. Not a Spring bean: constructed by
 * {@code ToolResolverConfiguration}, which hands over the optional collaborator by setter so the
 * provider's (test-constructed) constructor signature stays untouched.
 *
 * <p>Offer rule for a chat turn - ALL of: an {@code AgentJobService} bean exists; the agent names
 * the tool in its allow-list (the same per-agent opt-in {@code schedule_task} uses - an empty
 * allow-list means "unrestricted", which is deliberately NOT an opt-in); and the turn is NOT itself
 * a background job. That last rule is the runaway guard: a job must never spawn another job, or a
 * single turn ceiling stops meaning anything. No match = the tool is not built and costs zero
 * prompt tokens. Tool DISCOVERY (the admin picker's "everything you could add") lists it whenever
 * the service exists, otherwise nothing could ever be opted in from the picker.
 *
 * <p>The tool itself is built by a {@link BackgroundJobToolFactory}, a port implemented in {@code
 * core/agents-jobs} where agent jobs live (ADR-0023); a context without that module offers no
 * {@code continue_in_background}.
 */
final class AgentBackgroundJobToolResolver {

    private final AgentToolGuard toolGuard;
    private final AgentService agentService;
    private volatile ObjectProvider<BackgroundJobToolFactory> factory;

    AgentBackgroundJobToolResolver(AgentToolGuard toolGuard, AgentService agentService) {
        this.toolGuard = toolGuard;
        this.agentService = agentService;
    }

    void setFactory(ObjectProvider<BackgroundJobToolFactory> factory) {
        this.factory = factory;
    }

    /**
     * Tools for a real turn (or an approval-replay lookup): job service present, agent opted in,
     * not inside a job.
     */
    List<ToolCallback> resolveBackgroundJobTools(
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
        if (tool == null || !allowList(agentId).contains(BackgroundJobToolFactory.TOOL_NAME)) {
            return List.of();
        }
        return List.of(
                toolGuard.guardAndLog(
                        tool, agentId, sessionId, stepCounter, userMessage, chatClient, rigorous));
    }

    /** Tools for the admin tool picker: offered whenever the job service exists. */
    List<ToolCallback> resolveBackgroundJobToolsForDiscovery(
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
        ObjectProvider<BackgroundJobToolFactory> provider = factory;
        if (provider == null) {
            return null;
        }
        BackgroundJobToolFactory f = provider.getIfAvailable();
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
