package com.agenticai.core.agents.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The tool-discovery pass behind {@link AgentToolProvider#listAvailableToolsFor}: every resolver
 * group's tools for one agent, as name + description, without invoking anything. Extracted from
 * {@code AgentToolProvider} (extract-only, no behavior change) so that class stays under the
 * file-size ratchet. A Spring bean built from the same resolver beans the {@link ToolSource}s wrap.
 */
@Component
final class AgentToolDiscovery {

    private static final Logger log = LoggerFactory.getLogger(AgentToolDiscovery.class);

    private final AgentMcpToolResolver mcpToolResolver;
    private final AgentKnowledgeToolResolver knowledgeToolResolver;
    private final AgentIntegrationToolResolver integrationToolResolver;
    private final AgentChannelToolResolver channelToolResolver;
    private final AgentCrewBrowserToolResolver crewBrowserToolResolver;
    private final AgentMediaToolResolver mediaToolResolver;
    private final AgentCustomToolResolver customToolResolver;
    private final AgentDataTableToolResolver dataTableToolResolver;
    private final AgentScheduledTaskToolResolver scheduledTaskToolResolver;
    private final AgentWorkflowDraftToolResolver workflowDraftToolResolver;
    private final AgentCodingTaskToolResolver codingTaskToolResolver;
    private final int hardCapPerTurn;

    AgentToolDiscovery(
            AgentMcpToolResolver mcpToolResolver,
            AgentKnowledgeToolResolver knowledgeToolResolver,
            AgentIntegrationToolResolver integrationToolResolver,
            AgentChannelToolResolver channelToolResolver,
            AgentCrewBrowserToolResolver crewBrowserToolResolver,
            AgentMediaToolResolver mediaToolResolver,
            AgentCustomToolResolver customToolResolver,
            AgentDataTableToolResolver dataTableToolResolver,
            AgentScheduledTaskToolResolver scheduledTaskToolResolver,
            AgentWorkflowDraftToolResolver workflowDraftToolResolver,
            AgentCodingTaskToolResolver codingTaskToolResolver,
            @Value("${app.tools.hard-cap-per-turn:40}") int hardCapPerTurn) {
        this.mcpToolResolver = mcpToolResolver;
        this.knowledgeToolResolver = knowledgeToolResolver;
        this.integrationToolResolver = integrationToolResolver;
        this.channelToolResolver = channelToolResolver;
        this.crewBrowserToolResolver = crewBrowserToolResolver;
        this.mediaToolResolver = mediaToolResolver;
        this.customToolResolver = customToolResolver;
        this.dataTableToolResolver = dataTableToolResolver;
        this.scheduledTaskToolResolver = scheduledTaskToolResolver;
        this.workflowDraftToolResolver = workflowDraftToolResolver;
        this.codingTaskToolResolver = codingTaskToolResolver;
        this.hardCapPerTurn = hardCapPerTurn;
    }

    List<ToolDescriptor> listAvailableTools(UUID agentId) {
        AtomicInteger stepCounter = new AtomicInteger(0);
        String probeId = "tool-discovery-probe";
        // No real chat context and never actually invoked - null chatClient,
        // rigorous=false, no userMessage. See resolveToolsFor's chatClient
        // javadoc.
        List<ToolCallback> tools = new ArrayList<>();
        tools.addAll(
                mcpToolResolver.resolveMcpTools(agentId, probeId, stepCounter, null, null, false));
        tools.addAll(
                knowledgeToolResolver.resolveKnowledgeTools(
                        agentId, probeId, stepCounter, null, null, false));
        tools.addAll(
                knowledgeToolResolver.resolveCodeIntelTools(
                        agentId, probeId, stepCounter, null, null, false));
        tools.addAll(
                knowledgeToolResolver.resolveCodeWorkspaceTools(
                        agentId, probeId, stepCounter, null, null, false));
        tools.addAll(
                knowledgeToolResolver.resolveMemoryTools(
                        agentId, probeId, probeId, stepCounter, null, null, false));
        tools.addAll(
                integrationToolResolver.resolveWorkspaceTools(
                        agentId, probeId, stepCounter, null, null, false));
        tools.addAll(
                channelToolResolver.resolveTelegramTools(
                        agentId, probeId, stepCounter, null, null, false));
        tools.addAll(
                channelToolResolver.resolveChannelControlTools(
                        agentId, probeId, stepCounter, null, null, false));
        // BUG FIX 2026-09-17: resolveWebTools was missing from this list entirely,
        // so fetch_web_page and http_request have been invisible to every caller of
        // listAvailableToolsFor (tool discovery/listing) even though real turns
        // offer them via resolveToolsFor. web_search would have inherited the same
        // hole. Independent of web_search; it was already wrong.
        tools.addAll(
                integrationToolResolver.resolveWebToolsForDiscovery(
                        agentId, probeId, stepCounter, null, null, false));
        tools.addAll(
                crewBrowserToolResolver.resolveBrowserUseTools(
                        agentId, probeId, stepCounter, null, null, false));
        tools.addAll(
                crewBrowserToolResolver.resolveBrowserAutomationTools(
                        agentId, probeId, stepCounter, null, null, false));
        tools.addAll(
                channelToolResolver.resolveForwardAttachmentTools(
                        agentId, probeId, stepCounter, null, null, false));
        tools.addAll(
                channelToolResolver.resolveHumanHandoffTools(
                        agentId, probeId, stepCounter, null, null, false));
        tools.addAll(
                channelToolResolver.resolveDeviceTools(
                        agentId, probeId, stepCounter, null, null, false));
        tools.addAll(
                mediaToolResolver.resolveSarvamTools(
                        agentId, probeId, stepCounter, null, null, false));
        tools.addAll(
                integrationToolResolver.resolveE2bTools(
                        agentId, probeId, stepCounter, null, null, false));
        tools.addAll(
                mediaToolResolver.resolveImageGenTools(
                        agentId, probeId, stepCounter, null, null, false));
        tools.addAll(
                mediaToolResolver.resolveVideoGenTools(
                        agentId, probeId, stepCounter, null, null, false));
        tools.addAll(
                mediaToolResolver.resolveDocumentGenTools(
                        agentId, probeId, stepCounter, null, null, false));
        tools.addAll(
                mediaToolResolver.resolveAudioTools(
                        agentId, probeId, stepCounter, null, null, false));
        tools.addAll(
                mediaToolResolver.resolveMusicGenTools(
                        agentId, probeId, stepCounter, null, null, false));
        tools.addAll(
                customToolResolver.resolveCollaboratorTools(
                        agentId, probeId, stepCounter, null, null, false, true));
        tools.addAll(
                customToolResolver.resolveScriptTools(
                        agentId, probeId, stepCounter, null, null, false));
        tools.addAll(
                customToolResolver.resolveCustomCodeTools(
                        agentId, probeId, stepCounter, null, null, false));
        tools.addAll(
                customToolResolver.resolveToolAuthoringTools(
                        agentId, probeId, stepCounter, null, null, false));
        tools.addAll(
                customToolResolver.resolveOpenApiTools(
                        agentId, probeId, stepCounter, null, null, false));
        tools.addAll(
                integrationToolResolver.resolveCommerceTools(
                        agentId, probeId, stepCounter, null, null, false));
        if (dataTableToolResolver != null) {
            tools.addAll(
                    dataTableToolResolver.resolveDataTableToolsForDiscovery(
                            agentId, probeId, stepCounter, null, null, false));
        }
        if (scheduledTaskToolResolver != null) {
            tools.addAll(
                    scheduledTaskToolResolver.resolveScheduledTaskToolsForDiscovery(
                            agentId, probeId, stepCounter, null, null, false));
        }
        if (workflowDraftToolResolver != null) {
            tools.addAll(
                    workflowDraftToolResolver.resolveWorkflowDraftToolsForDiscovery(
                            agentId, probeId, stepCounter, null, null, false));
        }
        if (codingTaskToolResolver != null) {
            tools.addAll(
                    codingTaskToolResolver.resolveCodingTaskToolsForDiscovery(
                            agentId, probeId, stepCounter, null, null, false));
        }
        List<ToolDescriptor> descriptors =
                tools.stream()
                        .map(
                                tool ->
                                        new ToolDescriptor(
                                                tool.getToolDefinition().name(),
                                                tool.getToolDefinition().description()))
                        .distinct()
                        .toList();
        // This is an admin config surface (the allow-list picker's "everything you COULD add"
        // view) - unlike resolveToolsFor's LLM-facing path, silently truncating here would hide
        // real, currently-valid tool options from an admin rather than just degrade a chat
        // reply, so this deliberately does NOT cap the returned list. Still worth a loud signal
        // if a tenant's real catalog balloons this large - the same class of runaway growth
        // documented on ToolRelevanceSelector/capToHardLimit, just surfacing here as a slow
        // admin page load instead of a 400 to the LLM provider.
        if (descriptors.size() > hardCapPerTurn) {
            // INFO, not WARN: on a one-person install the Assistant alone resolves ~46 tools, and
            // this
            // listing is the admin picker, so the line would otherwise show on every settings open.
            log.info(
                    "Agent {}'s available-tools listing resolved {} distinct tools - well above the {} per-turn "
                            + "hard cap chat turns are held to. Not truncated here (this is the admin tool picker, not "
                            + "an LLM-bound list), but this tenant's MCP/custom-tool catalog is worth auditing.",
                    agentId,
                    descriptors.size(),
                    hardCapPerTurn);
        }
        return descriptors;
    }
}
