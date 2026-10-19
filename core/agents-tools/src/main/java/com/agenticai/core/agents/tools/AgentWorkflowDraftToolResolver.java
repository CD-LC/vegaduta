package com.agenticai.core.agents.tools;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.tools.workflowdraft.WorkflowDraftToolFactory;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Tool-resolution group for {@code create_workflow}. Not a Spring-scanned bean: built by {@link
 * ToolResolverConfiguration}.
 *
 * <p>Offer rule for a chat turn - ALL of: {@code app.tools.create-workflow.enabled} is on and the
 * workflow services exist; and the agent names the tool in its allow-list (the same per-agent
 * opt-in {@code schedule_task} and {@code data_table_write} use - an empty allow-list means
 * "unrestricted", which is deliberately NOT an opt-in). Tool DISCOVERY (the admin picker's
 * "everything you could add") lists the tool whenever the flag allows, otherwise nothing could ever
 * be opted in from the picker.
 *
 * <p>The tool itself is built by a {@link WorkflowDraftToolFactory}, a port implemented in {@code
 * core/workflow} (which depends on this module, not the other way round); a context without that
 * module offers no {@code create_workflow}.
 */
final class AgentWorkflowDraftToolResolver {

    private final AgentToolGuard toolGuard;
    private final AgentService agentService;
    private volatile ObjectProvider<WorkflowDraftToolFactory> factory;

    AgentWorkflowDraftToolResolver(AgentToolGuard toolGuard, AgentService agentService) {
        this.toolGuard = toolGuard;
        this.agentService = agentService;
    }

    void setFactory(ObjectProvider<WorkflowDraftToolFactory> factory) {
        this.factory = factory;
    }

    /** Tools for a real turn (or an approval-replay lookup): flag on and the agent opted in. */
    List<ToolCallback> resolveWorkflowDraftTools(
            UUID agentId,
            String sessionId,
            AtomicInteger stepCounter,
            String userMessage,
            ChatClient chatClient,
            boolean rigorous) {
        if (agentId == null || !allowList(agentId).contains(WorkflowDraftToolFactory.TOOL_NAME)) {
            return List.of();
        }
        return resolveWorkflowDraftToolsForDiscovery(
                agentId, sessionId, stepCounter, userMessage, chatClient, rigorous);
    }

    /** Tools for the admin tool picker: offered whenever the flag is on. */
    List<ToolCallback> resolveWorkflowDraftToolsForDiscovery(
            UUID agentId,
            String sessionId,
            AtomicInteger stepCounter,
            String userMessage,
            ChatClient chatClient,
            boolean rigorous) {
        ObjectProvider<WorkflowDraftToolFactory> provider = factory;
        WorkflowDraftToolFactory f = provider == null ? null : provider.getIfAvailable();
        ToolCallback tool = f == null ? null : f.createIfEnabled();
        if (tool == null) {
            return List.of();
        }
        return List.of(
                toolGuard.guardAndLog(
                        tool, agentId, sessionId, stepCounter, userMessage, chatClient, rigorous));
    }

    private Set<String> allowList(UUID agentId) {
        if (agentService == null) {
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
