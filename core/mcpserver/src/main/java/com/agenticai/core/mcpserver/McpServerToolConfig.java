package com.agenticai.core.mcpserver;

import com.agenticai.core.agents.a2a.A2aTaskService;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.knowledge.KnowledgeSearchService;
import com.agenticai.core.mcpserver.expose.McpExposureRpcHandler;
import com.agenticai.core.mcpserver.tools.McpAgentTaskToolCallbacks;
import com.agenticai.core.mcpserver.tools.McpCallAgentToolCallback;
import com.agenticai.core.mcpserver.tools.McpCallWorkflowToolCallback;
import com.agenticai.core.mcpserver.tools.McpListAgentsToolCallback;
import com.agenticai.core.mcpserver.tools.McpListMyWorkflowsToolCallback;
import com.agenticai.core.mcpserver.tools.McpSearchKnowledgeToolCallback;
import com.agenticai.core.workflow.WorkflowService;
import io.modelcontextprotocol.server.McpServerFeatures;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Wires this module's two pieces onto Spring AI's MCP server hosting
 * starter ({@code spring-ai-starter-mcp-server-webmvc}, pinned via the
 * parent {@code spring-ai-bom} at {@code spring-ai.version} 1.1.7 - see
 * this module's pom for the dependency).
 *
 * <p><b>Honesty note on the Spring AI 1.1.x MCP server API surface:</b> the
 * {@code ToolCallbackProvider.from(List<ToolCallback>)} bean pattern below
 * was CONFIRMED by fetching Spring AI's own reference docs for the 1.1
 * streamable-HTTP server boot starter during this build (not general
 * knowledge/guessed) - that page states the auto-configuration
 * "automatically detect[s] and register[s] all tool callbacks from... a
 * {@code ToolCallbackProvider}" bean, converting each {@code ToolCallback}
 * to a {@code McpServerFeatures.SyncToolSpecification}/{@code
 * AsyncToolSpecification} depending on {@code spring.ai.mcp.server.type}.
 * The exact default HTTP path this starter serves the MCP endpoint on
 * ({@code /mcp} is assumed below via explicit config, not left to
 * whatever the unconfirmed default is) and the exact request-per-tool-call
 * threading model (assumed synchronous-on-the-request-thread for
 * {@code type: SYNC} - see {@code McpCallAgentToolCallback}'s javadoc for
 * why that matters) were NOT independently verified against 1.1.7's actual
 * source and should be treated as reasoned-from-docs, not proven, until a
 * real build/run confirms them.
 *
 * <p><b>Why exactly two static "meta-tools" instead of one real MCP tool
 * per exposed agent:</b> the fetched docs also state tool registration here
 * is "application-scoped" - a single JVM-wide bean set built once at
 * startup, with no mention of any per-session/per-caller tool filtering.
 * If this bean instead dynamically emitted one {@code ToolCallback} per
 * {@code (grant, exposedAgentId)} pair, EVERY tenant's exposed agents would
 * be advertised to EVERY caller who can reach this one shared MCP endpoint
 * - a cross-tenant capability leak. Keeping the advertised tool list fixed
 * and small ({@code list_my_agents}, {@code call_agent}), and enforcing the
 * actual per-grant agent allow-list INSIDE each tool's own execution (via
 * {@link McpCallerContext}, populated per-request by {@link
 * McpApiKeyAuthFilter}), is the safe way to get real per-tenant curation
 * out of a starter whose tool registration itself is not per-caller.
 *
 * <p><b>Profile-gated, deliberately, per this build's own "unconditional
 * dependency on a conditionally-present bean broke the whole app's startup"
 * lesson:</b> {@code spring-ai-starter-mcp-server-webmvc} is an unconditional
 * Maven dependency of this module (same posture core/agents already takes
 * with {@code spring-ai-starter-mcp-client} - always on the classpath, only
 * ever DOING anything once real config/tool beans exist), but this class's
 * own beans - the ones that actually register a live auth filter and a real,
 * callable tool list - only exist when the new {@code "mcpserver"} Spring
 * profile is active, mirroring how {@code application-mcp.yml} (the
 * OUTBOUND MCP client side) already isolates its own subprocess-launching
 * risk behind the existing {@code "mcp"} profile. NOT independently
 * verified: whether Spring AI's own server-webmvc autoconfiguration does
 * anything observable (e.g. registers an empty MCP endpoint) purely from
 * classpath presence even with zero tool beans and this profile inactive -
 * reasoned to be low-risk (no tools, no subprocess, no external I/O) but not
 * proven by an actual run, same honesty caveat as this class's other note.
 */
@Configuration
@Profile("mcpserver")
public class McpServerToolConfig {

    private List<ToolCallback> taskTools;

    /**
     * The 5 curated tools this surface exposes, built once and shared by both
     * beans below - {@link #mcpServerSyncToolSpecifications} (what the MCP
     * transport actually serves) and {@link #mcpServerToolCallbackProvider}
     * (kept for anything else that wants the raw {@code ToolCallback}s).
     *
     * <p>WorkflowService/AuditLogService are both unconditional @Service beans
     * (core/workflow, core/audit respectively - AuditLogService's own class
     * javadoc: its constructor depends only on its own JpaRepository, no
     * @ConditionalOnProperty/@ConditionalOnBean anywhere in that chain) - safe
     * to depend on directly here, unlike the CodeIntelClient lesson this
     * build already learned the hard way (see class javadoc), and unlike
     * KnowledgeSearchService just below, which IS profile-gated and stays
     * behind ObjectProvider.
     */
    private List<ToolCallback> mcpServerTools(
        AgentService agentService,
        AgentChatService agentChatService,
        WorkflowService workflowService,
        ObjectProvider<KnowledgeSearchService> knowledgeSearchService,
        AuditLogService auditLogService,
        ObjectProvider<A2aTaskService> a2aTaskService,
        boolean asyncTasksEnabled,
        int asyncTasksMaxPerTenant
    ) {
        List<ToolCallback> tools = new ArrayList<>(List.of(
            new McpListAgentsToolCallback(agentService),
            new McpCallAgentToolCallback(agentChatService, auditLogService),
            new McpListMyWorkflowsToolCallback(workflowService),
            new McpCallWorkflowToolCallback(workflowService, auditLogService)
        ));
        // core/knowledge's KnowledgeSearchService IS @Profile("knowledge")-gated (unlike
        // WorkflowService above) - exactly the "unconditional dependency on a conditionally-
        // present bean" shape that took the whole app down once already (see class javadoc).
        // ObjectProvider + getIfAvailable() mirrors the exact posture core/agents'
        // AgentToolProvider already uses for this same bean: "mcpserver" can run with
        // "knowledge" inactive and this bean still starts fine, just without search_knowledge
        // advertised.
        KnowledgeSearchService searchService = knowledgeSearchService.getIfAvailable();
        if (searchService != null) {
            tools.add(new McpSearchKnowledgeToolCallback(searchService, auditLogService));
        }
        // Task-style long-running tools (start_agent_task/get_task/cancel_task) - opt-in via
        // app.mcpserver.async-tasks.enabled (default false: tools/list is unchanged). Backed by the
        // A2A task store; ObjectProvider keeps the profile bootable if A2A is ever absent.
        A2aTaskService taskService = asyncTasksEnabled ? a2aTaskService.getIfAvailable() : null;
        if (taskService != null) {
            // Built once: both beans below call this method, and the per-tenant in-flight cap
            // lives inside these callbacks, so two instances would double the effective cap.
            synchronized (this) {
                if (taskTools == null) {
                    taskTools = McpAgentTaskToolCallbacks.create(taskService, auditLogService, asyncTasksMaxPerTenant);
                }
                tools.addAll(taskTools);
            }
        }
        return tools;
    }

    @Bean
    public ToolCallbackProvider mcpServerToolCallbackProvider(
        AgentService agentService,
        AgentChatService agentChatService,
        WorkflowService workflowService,
        ObjectProvider<KnowledgeSearchService> knowledgeSearchService,
        AuditLogService auditLogService,
        ObjectProvider<A2aTaskService> a2aTaskService,
        @Value("${app.mcpserver.async-tasks.enabled:false}") boolean asyncTasksEnabled,
        @Value("${app.mcpserver.async-tasks.max-concurrent-per-tenant:4}") int asyncTasksMaxPerTenant
    ) {
        return ToolCallbackProvider.from(mcpServerTools(
            agentService, agentChatService, workflowService, knowledgeSearchService, auditLogService,
            a2aTaskService, asyncTasksEnabled, asyncTasksMaxPerTenant));
    }

    /**
     * 2026-08-15 fix - the actual wiring path from this module's own curated
     * {@link #mcpServerTools} to a live {@code tools/list} result.
     *
     * <p>{@code ToolCallbackConverterAutoConfiguration} (excluded - see
     * {@code CoreApplication}'s javadoc for the full, corrected finding) is
     * normally how a {@code ToolCallbackProvider} bean becomes the
     * {@code List<McpServerFeatures.SyncToolSpecification>} that {@code
     * McpServerAutoConfiguration#mcpSyncServer} actually consumes to build
     * the live tool registry - but that autoconfiguration aggregates
     * indiscriminately from EVERY {@code ToolCallback}/{@code
     * ToolCallbackProvider} bean in the whole Spring context, which on this
     * deployment also includes the outbound "mcp" profile's client-tool-use
     * wiring (filesystem/puppeteer/browser/memory, meant for agents to call
     * during chat, not for external MCP callers to invoke directly) -
     * confirmed live to leak that entire toolset to any grant holder.
     *
     * <p>This bean is the narrow replacement: it depends ONLY on {@link
     * #mcpServerTools}, so it can never pick up anything the outbound "mcp"
     * profile registers, however that profile's own tool-exposure wiring
     * changes in the future. {@link
     * org.springframework.ai.mcp.McpToolUtils#toSyncToolSpecification(List)}
     * is the same public conversion utility {@code
     * ToolCallbackConverterAutoConfiguration} itself calls internally - same
     * conversion, just scoped to a known, reviewed tool list instead of
     * "everything in the context."
     */
    @Bean
    public List<McpServerFeatures.SyncToolSpecification> mcpServerSyncToolSpecifications(
        AgentService agentService,
        AgentChatService agentChatService,
        WorkflowService workflowService,
        ObjectProvider<KnowledgeSearchService> knowledgeSearchService,
        AuditLogService auditLogService,
        ObjectProvider<A2aTaskService> a2aTaskService,
        @Value("${app.mcpserver.async-tasks.enabled:false}") boolean asyncTasksEnabled,
        @Value("${app.mcpserver.async-tasks.max-concurrent-per-tenant:4}") int asyncTasksMaxPerTenant
    ) {
        return McpToolUtils.toSyncToolSpecification(mcpServerTools(
            agentService, agentChatService, workflowService, knowledgeSearchService, auditLogService,
            a2aTaskService, asyncTasksEnabled, asyncTasksMaxPerTenant));
    }

    /**
     * Registers {@link McpApiKeyAuthFilter} scoped ONLY to the MCP endpoint
     * path - not a Spring Security filter chain member (see that class's
     * javadoc for why), so it needs its own explicit URL pattern here
     * rather than relying on {@code SecurityConfig}'s request matchers for
     * anything beyond the one {@code permitAll()} line that lets requests
     * reach this filter at all.
     *
     * <p>{@code order} is set well after Spring Security's own filter
     * chain (Spring Boot's default {@code springSecurityFilterChain}
     * registration order is a small negative number, i.e. very early) so
     * this filter runs only once Spring Security has already permitted the
     * request through - the exact numeric default was not independently
     * re-verified for this Spring Boot version, see class javadoc's honesty
     * note.
     */
    @Bean
    public FilterRegistrationBean<McpApiKeyAuthFilter> mcpApiKeyAuthFilterRegistration(
        McpServerAccessGrantService grantService, McpServerRateLimiter rateLimiter,
        @Value("${app.mcpserver.async-tasks.enabled:false}") boolean asyncTasksEnabled,
        // Present only when app.mcp-server.expose-agents.enabled=true (see McpExposureConfig); absent =
        // null handler = the filter behaves exactly as it did before agents-as-MCP-tools existed.
        ObjectProvider<McpExposureRpcHandler> exposureHandler,
        @Value("${app.mcp-server.expose-agents.max-body-bytes:262144}") int exposureMaxBodyBytes,
        // Present only when app.mcpserver.oauth.enabled=true (see McpOAuthConfig).
        ObjectProvider<McpOAuthAuthenticator> oauthAuthenticator,
        @Value("${app.mcpserver.oauth.resource:}") String oauthResource
    ) {
        McpApiKeyAuthFilter filter = new McpApiKeyAuthFilter(grantService, rateLimiter, false, asyncTasksEnabled,
            exposureHandler.getIfAvailable(), exposureMaxBodyBytes);
        McpOAuthAuthenticator authenticator = oauthAuthenticator.getIfAvailable();
        if (authenticator != null) {
            filter.enableOAuth(authenticator, McpProtectedResourceMetadataFilter.metadataUrl(oauthResource));
        }
        FilterRegistrationBean<McpApiKeyAuthFilter> registration = new FilterRegistrationBean<>(filter);
        // /api/dev/* (DeveloperApiController) added alongside /mcp/* - same
        // API key, same grant, same McpCallerContext/exposed-id enforcement,
        // just a plain-REST calling surface instead of MCP JSON-RPC. See
        // that controller's class javadoc for why this reuses the key
        // system rather than a second, parallel one.
        registration.addUrlPatterns("/mcp/*", "/api/dev/*");
        registration.setOrder(100);
        registration.setName("mcpApiKeyAuthFilter");
        return registration;
    }

    /**
     * Origin validation for {@code /mcp} (MCP Streamable HTTP "Security & Endpoint": validate Origin,
     * 403 when present and invalid). Runs before the key filter (order 50 < 100) so a browser-originated
     * cross-site request is refused before any credential lookup. {@code /api/dev/*} is a plain REST
     * surface the spec does not cover and is deliberately not included.
     */
    @Bean
    public FilterRegistrationBean<McpOriginValidationFilter> mcpOriginValidationFilterRegistration(
        @Value("${app.mcpserver.origin.mode:enforce}") String mode,
        @Value("${app.mcpserver.origin.allowed:}") String allowed
    ) {
        FilterRegistrationBean<McpOriginValidationFilter> registration = new FilterRegistrationBean<>(
            new McpOriginValidationFilter(McpOriginValidationFilter.Mode.parse(mode), allowed));
        registration.addUrlPatterns("/mcp/*");
        registration.setOrder(50);
        registration.setName("mcpOriginValidationFilter");
        return registration;
    }
}
