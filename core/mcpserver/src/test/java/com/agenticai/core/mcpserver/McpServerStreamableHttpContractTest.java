package com.agenticai.core.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.workflow.WorkflowService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * P0 characterisation (ADR-0012, section 6 P0.5): the inbound MCP server contract that external
 * clients (Claude Desktop, Cursor, ...) depend on, driven over the Streamable HTTP transport with
 * the real Spring AI 1.1.7 server auto-configuration and the real {@link McpServerToolConfig}:
 * {@code POST /mcp} {@code initialize} handshake, {@code tools/list}, and {@code tools/call}.
 *
 * <p>The API-key filter ({@link McpApiKeyAuthFilter}) and the per-caller exposure rules are covered
 * by their own tests; this one pins the protocol surface that the Spring AI 2 / MCP SDK 2.0 upgrade
 * can change: transport wiring (auto-config class names move), the exact curated tool names, and
 * the shape of a {@code tools/call} result.
 *
 * <p>The transport answers a POST either as {@code application/json} or as a one-event SSE stream
 * depending on the SDK version; {@link #rpc} accepts both and returns the JSON-RPC message.
 */
@SpringJUnitWebConfig(McpServerStreamableHttpContractTest.Config.class)
@ActiveProfiles("mcpserver")
@TestPropertySource(
        properties = {
            "spring.ai.mcp.server.name=vegaduta-mcp-server",
            "spring.ai.mcp.server.version=1.0.0",
            "spring.ai.mcp.server.type=SYNC",
            "spring.ai.mcp.server.protocol=STREAMABLE",
            "spring.ai.mcp.server.streamable-http.mcp-endpoint=/mcp"
        })
class McpServerStreamableHttpContractTest {

    /**
     * The curated tools an API-key holder can see (search_knowledge is added only with a knowledge
     * service); anything else leaking here is a security bug.
     */
    private static final List<String> CURATED_TOOLS =
            List.of("list_my_agents", "call_agent", "list_my_workflows", "call_workflow");

    @Configuration
    @EnableWebMvc
    @ImportAutoConfiguration({
        JacksonAutoConfiguration.class,
        org.springframework.ai.mcp.server.common.autoconfigure.McpServerJsonMapperAutoConfiguration
                .class,
        org.springframework.ai.mcp.server.common.autoconfigure.McpServerAutoConfiguration.class,
        org.springframework.ai.mcp.server.webmvc.autoconfigure
                .McpServerStreamableHttpWebMvcAutoConfiguration.class
    })
    @Import(McpServerToolConfig.class)
    static class Config {
        @Bean
        AgentService agentService() {
            return mock(AgentService.class);
        }

        @Bean
        AgentChatService agentChatService() {
            return mock(AgentChatService.class);
        }

        @Bean
        WorkflowService workflowService() {
            return mock(WorkflowService.class);
        }

        @Bean
        AuditLogService auditLogService() {
            return mock(AuditLogService.class);
        }

        @Bean
        McpServerAccessGrantService grantService() {
            return mock(McpServerAccessGrantService.class);
        }

        @Bean
        McpServerRateLimiter rateLimiter() {
            return mock(McpServerRateLimiter.class);
        }
    }

    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper mapper;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    // ------------------------------------------------------------------ rpc

    private JsonNode rpc(String body, String sessionId) throws Exception {
        var request =
                post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        // Streamable HTTP clients must accept both; a server that answers only one
                        // is non-conformant.
                        .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                        .content(body);
        if (sessionId != null) {
            request.header("Mcp-Session-Id", sessionId);
            request.header("MCP-Protocol-Version", "2025-06-18");
        }
        MvcResult result = mvc.perform(request).andReturn();
        if (result.getRequest().isAsyncStarted()) {
            result.getAsyncResult(5_000);
            result = mvc.perform(asyncDispatch(result)).andReturn();
        }
        lastSessionId =
                result.getResponse().getHeader("Mcp-Session-Id") != null
                        ? result.getResponse().getHeader("Mcp-Session-Id")
                        : lastSessionId;
        lastStatus = result.getResponse().getStatus();
        lastContentType = result.getResponse().getContentType();
        return parseJsonRpc(result.getResponse().getContentAsString());
    }

    private int initializeStatus;
    private String lastSessionId;
    private int lastStatus;
    private String lastContentType;

    /**
     * Accepts a plain JSON body or an SSE body ("data: {...}") and returns the JSON-RPC message.
     */
    private JsonNode parseJsonRpc(String raw) throws Exception {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("{")) {
            return mapper.readTree(trimmed);
        }
        List<String> dataLines = new ArrayList<>();
        for (String line : trimmed.split("\\R")) {
            if (line.startsWith("data:")) {
                dataLines.add(line.substring(5).trim());
            }
        }
        assertThat(dataLines).as("SSE body must carry a data: event, got: " + raw).isNotEmpty();
        return mapper.readTree(String.join("", dataLines));
    }

    private JsonNode initialize() throws Exception {
        JsonNode response =
                rpc(
                        """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
              "protocolVersion":"2025-06-18","capabilities":{},
              "clientInfo":{"name":"contract-test","version":"1.0.0"}}}
            """,
                        null);
        assertThat(lastSessionId)
                .as("Streamable HTTP must hand out an Mcp-Session-Id on initialize")
                .isNotBlank();
        initializeStatus = lastStatus;
        rpc("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", lastSessionId);
        return response;
    }

    // ---------------------------------------------------------------- tests

    @Test
    void initializeHandshakeAdvertisesTheServerAndItsToolCapability() throws Exception {
        JsonNode response = initialize();

        assertThat(initializeStatus).isEqualTo(200);
        assertThat(response.path("jsonrpc").asString()).isEqualTo("2.0");
        assertThat(response.path("id").asInt()).isEqualTo(1);
        JsonNode result = response.path("result");
        assertThat(result.path("serverInfo").path("name").asString())
                .isEqualTo("vegaduta-mcp-server");
        assertThat(result.path("serverInfo").path("version").asString()).isEqualTo("1.0.0");
        assertThat(result.path("capabilities").has("tools"))
                .as("tools capability advertised")
                .isTrue();
        assertThat(result.path("protocolVersion").asString()).isNotBlank();
    }

    @Test
    void toolsListReturnsExactlyTheCuratedToolsWithObjectInputSchemas() throws Exception {
        initialize();

        JsonNode response =
                rpc(
                        "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}",
                        lastSessionId);

        JsonNode tools = response.path("result").path("tools");
        List<String> names = new ArrayList<>();
        tools.forEach(t -> names.add(t.path("name").asString()));
        assertThat(names).containsExactlyInAnyOrderElementsOf(CURATED_TOOLS);
        tools.forEach(
                tool -> {
                    assertThat(tool.path("description").asString()).isNotBlank();
                    assertThat(tool.path("inputSchema").path("type").asString())
                            .isEqualTo("object");
                });
        // The knowledge tool is registered only when a KnowledgeSearchService bean exists; none
        // here.
        assertThat(names).doesNotContain("search_knowledge");
    }

    @Test
    void toolsCallReturnsATextContentResultInTheMcpShape() throws Exception {
        initialize();

        JsonNode response =
                rpc(
                        """
            {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"list_my_agents","arguments":{}}}
            """,
                        lastSessionId);

        assertThat(response.path("id").asInt()).isEqualTo(3);
        JsonNode result = response.path("result");
        assertThat(result.path("isError").asBoolean(false)).isFalse();
        JsonNode content = result.path("content");
        assertThat(content.isArray()).isTrue();
        assertThat(content.get(0).path("type").asString()).isEqualTo("text");
        // With no caller context and no granted agents the curated tool answers an empty JSON
        // array.
        assertThat(mapper.readTree(content.get(0).path("text").asString()).isArray()).isTrue();
    }

    @Test
    void anUnknownToolIsAJsonRpcOrToolLevelErrorNeverASuccess() throws Exception {
        initialize();

        JsonNode response =
                rpc(
                        """
            {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"browser_run_code_unsafe","arguments":{}}}
            """,
                        lastSessionId);

        boolean rpcError = response.has("error");
        boolean toolError = response.path("result").path("isError").asBoolean(false);
        assertThat(rpcError || toolError)
                .as(
                        "a tool outside the curated set must not be callable through the inbound MCP surface: "
                                + response)
                .isTrue();
    }

    @Test
    void theContentTypeOfARpcResponseIsJsonOrEventStream() throws Exception {
        rpc(
                """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
              "protocolVersion":"2025-06-18","capabilities":{},
              "clientInfo":{"name":"contract-test","version":"1.0.0"}}}
            """,
                null);

        assertThat(lastContentType).isNotNull();
        assertThat(lastContentType).matches("(?i)(application/json|text/event-stream).*");
    }
}
