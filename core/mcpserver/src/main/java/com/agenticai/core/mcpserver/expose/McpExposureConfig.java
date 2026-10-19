package com.agenticai.core.mcpserver.expose;

import com.agenticai.core.agents.budget.PerUserBudgetService;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.approval.ApprovalRequestRepository;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.mcpserver.McpServerRateLimiter;
import com.agenticai.core.workflow.WorkflowBackgroundRunService;
import com.agenticai.core.workflow.WorkflowService;
import java.util.List;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Wires the "agents and workflows as MCP tools" hook. Absent entirely - no bean exists, so {@code
 * McpServerToolConfig} hands the auth filter a null handler and {@code tools/list} / {@code
 * tools/call} behave byte-for-byte as before - unless BOTH the {@code mcpserver} profile is active
 * (same gate as the rest of the inbound MCP surface) AND {@code app.mcp-server.expose-agents.enabled}
 * is {@code true} (default {@code false}).
 *
 * <p>The key lives under {@code app.mcp-server} (kebab-case, as named in the feature brief) in
 * {@code application-mcpserver.yml}, next to - not inside - the older {@code app.mcpserver} block.
 */
@Configuration
@Profile("mcpserver")
@ConditionalOnProperty(name = "app.mcp-server.expose-agents.enabled", havingValue = "true")
public class McpExposureConfig {

    @Bean
    public McpExposedToolCatalog mcpExposedToolCatalog(
        McpToolExposureService exposureService, AgentService agentService, WorkflowService workflowService
    ) {
        return new McpExposedToolCatalog(exposureService, agentService, workflowService);
    }

    @Bean
    public McpExposedToolInvoker mcpExposedToolInvoker(
        AgentChatService agentChatService,
        WorkflowService workflowService,
        WorkflowBackgroundRunService backgroundRuns,
        AuditLogService auditLogService,
        McpServerRateLimiter rateLimiter,
        ObjectProvider<PerUserBudgetService> budgetService,
        ObjectProvider<ApprovalRequestRepository> approvals,
        McpExposedToolCatalog catalog,
        @Value("${app.mcp-server.expose-agents.agent-timeout-seconds:90}") long agentTimeoutSeconds,
        @Value("${app.mcp-server.expose-agents.workflow-wait-seconds:45}") long workflowWaitSeconds,
        @Value("${app.mcp-server.expose-agents.poll-interval-ms:1000}") long pollIntervalMillis,
        @Value("${app.mcp-server.expose-agents.max-invocations-per-minute:30}") long maxInvocationsPerMinute,
        @Value("${app.mcp-server.expose-agents.max-input-chars:16000}") int maxInputChars,
        @Value("${app.mcp-server.expose-agents.max-output-chars:20000}") int maxOutputChars
    ) {
        return new McpExposedToolInvoker(
            agentChatService, workflowService, backgroundRuns, auditLogService, rateLimiter, budgetService, approvals,
            catalog,
            new McpExposedToolInvoker.Settings(
                agentTimeoutSeconds, workflowWaitSeconds, pollIntervalMillis, maxInvocationsPerMinute,
                maxInputChars, maxOutputChars));
    }

    @Bean
    public McpExposureRpcHandler mcpExposureRpcHandler(
        @Qualifier("mcpServerToolCallbackProvider") ToolCallbackProvider staticToolProvider,
        McpExposedToolCatalog catalog,
        McpExposedToolInvoker invoker
    ) {
        return new McpExposureRpcHandler(() -> List.<ToolCallback>of(staticToolProvider.getToolCallbacks()), catalog, invoker);
    }
}
