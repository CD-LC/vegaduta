package com.agenticai.core.mcpserver.tools;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.mcpserver.McpCallerContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.ObjectMapper;

/**
 * One of exactly two MCP tools this server statically exposes (the other is {@link
 * McpCallAgentToolCallback}) - see {@code McpServerToolConfig}'s javadoc for why this is
 * deliberately a small, generic "meta-tool" surface rather than one real MCP tool per exposed
 * agent: Spring AI's MCP server starter registers its tool list as a single JVM-wide bean set at
 * startup (no built-in per-caller/per-session tool filtering - see that class's javadoc for the
 * exact citation), so per-tenant/per-grant curation has to happen INSIDE a tool's own execution
 * instead of in which tools are advertised. This tool lets an external MCP client discover which
 * agent ids/names {@link McpCallAgentToolCallback} will actually accept for the key it
 * authenticated with, before it guesses.
 */
public class McpListAgentsToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(McpListAgentsToolCallback.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final AgentService agentService;

    public McpListAgentsToolCallback(AgentService agentService) {
        this.agentService = agentService;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("list_my_agents")
                .description(
                        "Lists the agents this API key is allowed to call, each with its id and name. Call this first, "
                                + "then pass one of the returned ids as call_agent's agent_id parameter.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {}
                }
                """)
                .build();
    }

    @Override
    public String call(String toolInput) {
        try {
            java.util.Set<UUID> exposed =
                    McpCallerContext.current()
                            .map(McpCallerContext::exposedAgentIds)
                            .orElse(java.util.Set.of());
            List<Map<String, String>> agents =
                    resolveExposedAgents(agentService, exposed).stream()
                            .map(agent -> Map.of("id", agent.id().toString(), "name", agent.name()))
                            .toList();
            return MAPPER.writeValueAsString(agents);
        } catch (Exception e) {
            log.warn("list_my_agents tool call failed", e);
            return "{\"error\": \"Could not list agents: " + e.getMessage() + "\"}";
        }
    }

    /**
     * The single "which of this grant's exposed agent ids still resolve, and to what" lookup -
     * shared with {@code DeveloperApiController}'s {@code GET /api/dev/v1/agents} so the MCP tool
     * and the REST surface can never drift on the deleted-agent-degrades-to-omission posture below.
     *
     * <p>Uses {@link AgentService#getGrantedAgent} (tenant-scoped, no per-user ownership check),
     * not {@link AgentService#get} - see that method's javadoc for the live-confirmed bug this
     * fixes: {@code get}'s ownership check rejected every exposed agent for the MCP synthetic
     * subject, and the catch block below silently swallowed that into an empty list.
     */
    public static List<AgentResponse> resolveExposedAgents(
            AgentService agentService, java.util.Set<UUID> exposedIds) {
        return exposedIds.stream()
                .flatMap(
                        id -> {
                            try {
                                return java.util.stream.Stream.of(agentService.getGrantedAgent(id));
                            } catch (RuntimeException e) {
                                // Exposed id no longer resolves (agent deleted since the grant was
                                // configured) - skip it rather than fail the whole listing.
                                log.warn(
                                        "MCP grant references agent {} which no longer exists - omitting from listing",
                                        id);
                                return java.util.stream.Stream.<AgentResponse>empty();
                            }
                        })
                .toList();
    }
}
