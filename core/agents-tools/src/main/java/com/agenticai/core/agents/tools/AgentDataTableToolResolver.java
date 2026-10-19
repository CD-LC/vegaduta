package com.agenticai.core.agents.tools;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.tools.tables.DataTableQueryToolCallback;
import com.agenticai.core.agents.tools.tables.DataTableWriteToolCallback;
import com.agenticai.core.tables.DataTableService;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Tool-resolution group for the Data Tables pair ({@code data_table_query}, {@code
 * data_table_write}). Not a Spring bean: constructed by {@link AgentToolProvider}, which hands over
 * the optional service by setter so the provider's (test-constructed) constructor signature stays
 * untouched.
 *
 * <p>Offer rule for a chat turn: BOTH of (1) {@code app.data-tables.enabled} is on and the service
 * bean exists, and (2) the agent names the tool in its allow-list - the same per-agent opt-in
 * {@code a2a_call_agent} and {@code deep_research} use (reusing {@code Agent.allowedToolNames}, no
 * schema change; an empty allow-list means "unrestricted", which is deliberately NOT an opt-in).
 * Read and write opt in separately, so an agent can be given read access to a table without the
 * ability to change it. No match = the tools are not built and cost zero prompt tokens. Tool
 * DISCOVERY (the admin picker's "everything you could add") lists whatever the flag allows without
 * the opt-in, otherwise nothing could ever be opted in from the picker.
 */
final class AgentDataTableToolResolver {

    private final AgentToolGuard toolGuard;
    private final AgentService agentService;
    private volatile ObjectProvider<DataTableService> dataTables;

    AgentDataTableToolResolver(AgentToolGuard toolGuard, AgentService agentService) {
        this.toolGuard = toolGuard;
        this.agentService = agentService;
    }

    void setDataTables(ObjectProvider<DataTableService> dataTables) {
        this.dataTables = dataTables;
    }

    /**
     * Tools for a real turn (or an approval-replay lookup): flag on AND the agent opted in per
     * tool.
     */
    List<ToolCallback> resolveDataTableTools(
            UUID agentId,
            String sessionId,
            AtomicInteger stepCounter,
            String userMessage,
            ChatClient chatClient,
            boolean rigorous) {
        DataTableService service = enabledService();
        if (service == null) {
            return List.of();
        }
        Set<String> allowed = allowList(agentId);
        List<ToolCallback> out = new ArrayList<>();
        if (allowed.contains(DataTableQueryToolCallback.TOOL_NAME)) {
            out.add(
                    toolGuard.guardAndLog(
                            new DataTableQueryToolCallback(service),
                            agentId,
                            sessionId,
                            stepCounter,
                            userMessage,
                            chatClient,
                            rigorous));
        }
        if (allowed.contains(DataTableWriteToolCallback.TOOL_NAME)) {
            out.add(
                    toolGuard.guardAndLog(
                            new DataTableWriteToolCallback(service),
                            agentId,
                            sessionId,
                            stepCounter,
                            userMessage,
                            chatClient,
                            rigorous));
        }
        return out;
    }

    /** Tools for the admin tool picker: both, whenever the flag is on. */
    List<ToolCallback> resolveDataTableToolsForDiscovery(
            UUID agentId,
            String sessionId,
            AtomicInteger stepCounter,
            String userMessage,
            ChatClient chatClient,
            boolean rigorous) {
        DataTableService service = enabledService();
        if (service == null) {
            return List.of();
        }
        return List.of(
                toolGuard.guardAndLog(
                        new DataTableQueryToolCallback(service),
                        agentId,
                        sessionId,
                        stepCounter,
                        userMessage,
                        chatClient,
                        rigorous),
                toolGuard.guardAndLog(
                        new DataTableWriteToolCallback(service),
                        agentId,
                        sessionId,
                        stepCounter,
                        userMessage,
                        chatClient,
                        rigorous));
    }

    private DataTableService enabledService() {
        ObjectProvider<DataTableService> provider = dataTables;
        DataTableService service = provider == null ? null : provider.getIfAvailable();
        return service != null && service.isEnabled() ? service : null;
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
