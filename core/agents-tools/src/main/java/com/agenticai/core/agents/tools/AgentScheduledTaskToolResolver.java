package com.agenticai.core.agents.tools;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.job.AgentJobSession;
import com.agenticai.core.agents.tools.scheduling.ScheduleTaskToolFactory;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Tool-resolution group for {@code schedule_task}. Not a Spring bean: constructed by {@link
 * AgentToolProvider}, which hands over the optional collaborators by setter so the provider's
 * (test-constructed) constructor signature stays untouched.
 *
 * <p>Offer rule for a chat turn - ALL of: {@code app.scheduled-tasks.enabled} is on and the service
 * bean exists; the agent names the tool in its allow-list (the same per-agent opt-in {@code
 * data_table_write}, {@code a2a_call_agent} and {@code deep_research} use - an empty allow-list
 * means "unrestricted", which is deliberately NOT an opt-in); and the turn is NOT an unattended
 * job. That last rule is a runaway guard: a scheduled run must never be able to schedule more
 * scheduled runs. No match = the tool is not built and costs zero prompt tokens, so with the flag
 * off (the default) this class is an identity function over the tool list. Tool DISCOVERY (the
 * admin picker's "everything you could add") lists the tool whenever the flag allows, otherwise
 * nothing could ever be opted in from the picker.
 *
 * <p>The tool itself is built by a {@link ScheduleTaskToolFactory}, a port implemented in {@code
 * core/agents-jobs} where scheduled tasks live (ADR-0023); a context without that module offers no
 * {@code schedule_task}.
 */
final class AgentScheduledTaskToolResolver {

    private final AgentToolGuard toolGuard;
    private final AgentService agentService;
    private volatile ObjectProvider<ScheduleTaskToolFactory> factory;

    AgentScheduledTaskToolResolver(AgentToolGuard toolGuard, AgentService agentService) {
        this.toolGuard = toolGuard;
        this.agentService = agentService;
    }

    void setFactory(ObjectProvider<ScheduleTaskToolFactory> factory) {
        this.factory = factory;
    }

    /**
     * Tools for a real turn (or an approval-replay lookup): flag on, agent opted in, not inside a
     * job.
     */
    List<ToolCallback> resolveScheduledTaskTools(
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
        if (tool == null || !allowList(agentId).contains(ScheduleTaskToolFactory.TOOL_NAME)) {
            return List.of();
        }
        return List.of(
                toolGuard.guardAndLog(
                        tool, agentId, sessionId, stepCounter, userMessage, chatClient, rigorous));
    }

    /** Tools for the admin tool picker: offered whenever the flag is on. */
    List<ToolCallback> resolveScheduledTaskToolsForDiscovery(
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
        ObjectProvider<ScheduleTaskToolFactory> provider = factory;
        if (provider == null) {
            return null;
        }
        ScheduleTaskToolFactory f = provider.getIfAvailable();
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
