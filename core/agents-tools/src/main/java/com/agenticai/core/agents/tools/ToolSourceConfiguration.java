package com.agenticai.core.agents.tools;

import com.agenticai.core.agents.context.ChatContextCompaction;
import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/**
 * The {@link ToolSource} beans {@link ToolResolver} collects. {@code @Order} is the resolution
 * order and is the exact order the former monolithic {@code AgentToolProvider.resolveToolsFor}
 * appended its candidates in (it decides relevance ties and which tools survive the hard cap), so
 * keep new sources in the gaps rather than renumbering. Every source wraps one resolver helper that
 * returns guard-wrapped tools and self-gates on its own flag / collaborator / agent allow-list.
 */
@Configuration(proxyBeanMethods = false)
class ToolSourceConfiguration {

    @Bean
    @Order(10)
    ToolSource mcpToolSource(AgentMcpToolResolver mcp) {
        return ToolSource.ofResolver("mcp", ToolSource.Kind.MCP, mcp::resolveMcpTools);
    }

    @Bean
    @Order(20)
    ToolSource knowledgeToolSource(AgentKnowledgeToolResolver knowledge) {
        return ToolSource.ofResolver(
                "knowledge", ToolSource.Kind.BUILT_IN, knowledge::resolveKnowledgeTools);
    }

    @Bean
    @Order(30)
    ToolSource codeIntelToolSource(AgentKnowledgeToolResolver knowledge) {
        return ToolSource.ofResolver(
                "codeIntel", ToolSource.Kind.BUILT_IN, knowledge::resolveCodeIntelTools);
    }

    @Bean
    @Order(40)
    ToolSource codeWorkspaceToolSource(AgentKnowledgeToolResolver knowledge) {
        return ToolSource.ofResolver(
                "codeWorkspace", ToolSource.Kind.BUILT_IN, knowledge::resolveCodeWorkspaceTools);
    }

    @Bean
    @Order(50)
    ToolSource memoryToolSource(AgentKnowledgeToolResolver knowledge) {
        return ToolSource.of(
                "memory",
                ToolSource.Kind.BUILT_IN,
                ctx ->
                        knowledge.resolveMemoryTools(
                                ctx.agentId(),
                                ctx.sessionId(),
                                ctx.subjectKey(),
                                ctx.stepCounter(),
                                ctx.userMessage(),
                                ctx.chatClient(),
                                ctx.rigorous()));
    }

    @Bean
    @Order(60)
    ToolSource workspaceToolSource(AgentIntegrationToolResolver integration) {
        return ToolSource.ofResolver(
                "workspace", ToolSource.Kind.BUILT_IN, integration::resolveWorkspaceTools);
    }

    @Bean
    @Order(70)
    ToolSource telegramToolSource(AgentChannelToolResolver channel) {
        return ToolSource.ofResolver(
                "telegram", ToolSource.Kind.BUILT_IN, channel::resolveTelegramTools);
    }

    @Bean
    @Order(80)
    ToolSource channelControlToolSource(AgentChannelToolResolver channel) {
        return ToolSource.ofResolver(
                "channelControl", ToolSource.Kind.BUILT_IN, channel::resolveChannelControlTools);
    }

    @Bean
    @Order(90)
    ToolSource forwardAttachmentToolSource(AgentChannelToolResolver channel) {
        return ToolSource.ofResolver(
                "forwardAttachment",
                ToolSource.Kind.BUILT_IN,
                channel::resolveForwardAttachmentTools);
    }

    @Bean
    @Order(100)
    ToolSource humanHandoffToolSource(AgentChannelToolResolver channel) {
        return ToolSource.ofResolver(
                "humanHandoff", ToolSource.Kind.BUILT_IN, channel::resolveHumanHandoffTools);
    }

    @Bean
    @Order(110)
    ToolSource deviceToolSource(AgentChannelToolResolver channel) {
        return ToolSource.ofResolver(
                "device", ToolSource.Kind.BUILT_IN, channel::resolveDeviceTools);
    }

    @Bean
    @Order(120)
    ToolSource sarvamToolSource(AgentMediaToolResolver media) {
        return ToolSource.ofResolver("sarvam", ToolSource.Kind.BUILT_IN, media::resolveSarvamTools);
    }

    @Bean
    @Order(130)
    ToolSource sandboxToolSource(AgentIntegrationToolResolver integration) {
        return ToolSource.ofResolver(
                "sandbox", ToolSource.Kind.BUILT_IN, integration::resolveE2bTools);
    }

    @Bean
    @Order(140)
    ToolSource imageGenToolSource(AgentMediaToolResolver media) {
        return ToolSource.ofResolver(
                "imageGen", ToolSource.Kind.BUILT_IN, media::resolveImageGenTools);
    }

    @Bean
    @Order(150)
    ToolSource videoGenToolSource(AgentMediaToolResolver media) {
        return ToolSource.ofResolver(
                "videoGen", ToolSource.Kind.BUILT_IN, media::resolveVideoGenTools);
    }

    @Bean
    @Order(160)
    ToolSource documentGenToolSource(AgentMediaToolResolver media) {
        return ToolSource.ofResolver(
                "documentGen", ToolSource.Kind.BUILT_IN, media::resolveDocumentGenTools);
    }

    @Bean
    @Order(170)
    ToolSource webToolSource(AgentIntegrationToolResolver integration) {
        return ToolSource.ofResolver("web", ToolSource.Kind.BUILT_IN, integration::resolveWebTools);
    }

    @Bean
    @Order(180)
    ToolSource browserUseToolSource(AgentCrewBrowserToolResolver crew) {
        return ToolSource.ofResolver(
                "browserUse", ToolSource.Kind.BUILT_IN, crew::resolveBrowserUseTools);
    }

    @Bean
    @Order(190)
    ToolSource browserAutomationToolSource(AgentCrewBrowserToolResolver crew) {
        return ToolSource.ofResolver(
                "browserAutomation", ToolSource.Kind.BUILT_IN, crew::resolveBrowserAutomationTools);
    }

    @Bean
    @Order(200)
    ToolSource audioToolSource(AgentMediaToolResolver media) {
        return ToolSource.ofResolver("audio", ToolSource.Kind.BUILT_IN, media::resolveAudioTools);
    }

    @Bean
    @Order(210)
    ToolSource musicGenToolSource(AgentMediaToolResolver media) {
        return ToolSource.ofResolver(
                "musicGen", ToolSource.Kind.BUILT_IN, media::resolveMusicGenTools);
    }

    @Bean
    @Order(220)
    ToolSource collaboratorToolSource(AgentCustomToolResolver custom) {
        return ToolSource.of(
                "collaborator",
                ToolSource.Kind.BUILT_IN,
                ctx ->
                        custom.resolveCollaboratorTools(
                                ctx.agentId(),
                                ctx.sessionId(),
                                ctx.stepCounter(),
                                ctx.userMessage(),
                                ctx.chatClient(),
                                ctx.rigorous(),
                                ctx.allowDelegation()));
    }

    @Bean
    @Order(230)
    ToolSource specialistDelegationToolSource(AgentCrewBrowserToolResolver crew) {
        return ToolSource.of(
                "specialistDelegation",
                ToolSource.Kind.BUILT_IN,
                ctx ->
                        crew.resolveSpecialistDelegationTools(
                                ctx.agentId(),
                                ctx.sessionId(),
                                ctx.stepCounter(),
                                ctx.userMessage(),
                                ctx.chatClient(),
                                ctx.rigorous(),
                                ctx.allowDelegation()));
    }

    @Bean
    @Order(240)
    ToolSource userScriptToolSource(AgentCustomToolResolver custom) {
        return ToolSource.ofResolver(
                "userScript", ToolSource.Kind.BUILT_IN, custom::resolveScriptTools);
    }

    @Bean
    @Order(250)
    ToolSource customCodeToolSource(AgentCustomToolResolver custom) {
        return ToolSource.ofResolver(
                "customCode", ToolSource.Kind.BUILT_IN, custom::resolveCustomCodeTools);
    }

    @Bean
    @Order(255)
    ToolSource toolAuthoringToolSource(AgentCustomToolResolver custom) {
        return ToolSource.ofResolver(
                "toolAuthoring", ToolSource.Kind.BUILT_IN, custom::resolveToolAuthoringTools);

    }

    @Bean
    @Order(260)
    ToolSource openApiToolSource(AgentCustomToolResolver custom) {
        return ToolSource.ofResolver(
                "openApi", ToolSource.Kind.BUILT_IN, custom::resolveOpenApiTools);
    }

    @Bean
    @Order(270)
    ToolSource commerceToolSource(AgentIntegrationToolResolver integration) {
        return ToolSource.ofResolver(
                "commerce", ToolSource.Kind.BUILT_IN, integration::resolveCommerceTools);
    }

    @Bean
    @Order(280)
    ToolSource dataTableToolSource(AgentDataTableToolResolver tables) {
        return ToolSource.ofResolver(
                "dataTable", ToolSource.Kind.BUILT_IN, tables::resolveDataTableTools);
    }

    @Bean
    @Order(290)
    ToolSource scheduledTaskToolSource(AgentScheduledTaskToolResolver scheduled) {
        return ToolSource.ofResolver(
                "scheduledTask", ToolSource.Kind.BUILT_IN, scheduled::resolveScheduledTaskTools);
    }

    @Bean
    @Order(292)
    ToolSource backgroundJobToolSource(AgentBackgroundJobToolResolver backgroundJobs) {
        return ToolSource.ofResolver(
                "backgroundJob",
                ToolSource.Kind.BUILT_IN,
                backgroundJobs::resolveBackgroundJobTools);

    }

    @Bean
    @Order(295)
    ToolSource workflowDraftToolSource(AgentWorkflowDraftToolResolver workflowDrafts) {
        return ToolSource.ofResolver(
                "workflowDraft",
                ToolSource.Kind.BUILT_IN,
                workflowDrafts::resolveWorkflowDraftTools);
    }

    @Bean
    @Order(300)
    ToolSource codingTaskToolSource(AgentCodingTaskToolResolver coding) {
        return ToolSource.ofResolver(
                "codingTask", ToolSource.Kind.BUILT_IN, coding::resolveCodingTaskTools);
    }

    /**
     * Agent Skills: joins AFTER the allow-list filter on purpose - see {@code
     * AgentCustomToolResolver#resolveSkillTools}.
     */
    @Bean
    @Order(310)
    ToolSource skillToolSource(AgentCustomToolResolver custom) {
        return ToolSource.of(
                "skills",
                ToolSource.Kind.POST_ALLOW_LIST,
                ctx ->
                        custom.resolveSkillTools(
                                ctx.agentId(),
                                ctx.sessionId(),
                                ctx.stepCounter(),
                                ctx.userMessage(),
                                ctx.chatClient(),
                                ctx.rigorous(),
                                ctx.resolvedSoFar()));
    }

    /**
     * {@code restore_tool_result} for a MAIN chat turn only (the one path that opens a compaction
     * run), guard-wrapped like every other first-party tool; empty while compaction ({@code
     * app.context.compaction.*}, default OFF) is off or its bean is absent. Not subject to the
     * agent allow-list, so deliberately absent from tool discovery.
     */
    @Bean
    @Order(320)
    ToolSource contextRestoreToolSource(
            AgentToolGuard toolGuard, ObjectProvider<ChatContextCompaction> contextCompaction) {
        return ToolSource.of(
                "contextRestore",
                ToolSource.Kind.POST_ALLOW_LIST,
                ctx -> ctx.mainChatTurn() && ctx.chatClient() != null,
                ctx -> {
                    ChatContextCompaction compaction = contextCompaction.getIfAvailable();
                    if (compaction == null) {
                        return List.<ToolCallback>of();
                    }
                    ChatClient chatClient = ctx.chatClient();
                    return compaction.offeredRestoreTools(
                            ctx.agentId(),
                            ctx.sessionId(),
                            ctx.resolvedSoFar(),
                            tool ->
                                    toolGuard.guardAndLog(
                                            tool,
                                            ctx.agentId(),
                                            ctx.sessionId(),
                                            ctx.stepCounter(),
                                            ctx.userMessage(),
                                            chatClient,
                                            ctx.rigorous()));
                });
    }
}
