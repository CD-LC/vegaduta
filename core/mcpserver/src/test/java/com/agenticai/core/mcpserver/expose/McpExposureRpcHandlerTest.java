package com.agenticai.core.mcpserver.expose;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.mcpserver.McpCallerContext;
import com.agenticai.core.mcpserver.McpCallerContextTestAccess;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * The JSON-RPC hook that serves the per-caller dynamic tools. The properties that matter: with
 * nothing to add it answers NOTHING (so tools/list is exactly what the MCP SDK always served - the
 * flag-off identity), static tools are never intercepted, and a dynamic name that is not in this
 * caller's own catalog is "Unknown tool" and never executed.
 */
class McpExposureRpcHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private McpExposedToolCatalog catalog;
    private McpExposedToolInvoker invoker;
    private McpExposureRpcHandler handler;
    private McpCallerContext caller;

    private final UUID grantId = UUID.randomUUID();
    private final UUID agentId = UUID.randomUUID();
    private McpExposedTool agentTool;

    @BeforeEach
    void setUp() {
        catalog = mock(McpExposedToolCatalog.class);
        invoker = mock(McpExposedToolInvoker.class);
        ToolCallback staticTool = mock(ToolCallback.class);
        when(staticTool.getToolDefinition()).thenReturn(ToolDefinition.builder()
            .name("call_agent").description("static one").inputSchema("{\"type\":\"object\"}").build());
        handler = new McpExposureRpcHandler(() -> List.of(staticTool), catalog, invoker);
        McpCallerContextTestAccess.set(grantId, Set.of(agentId), Set.of(), Set.of());
        caller = McpCallerContext.current().orElseThrow();
        agentTool = new McpExposedTool("agent_helper", "Helper tool", "{\"type\":\"object\",\"properties\":{}}",
            McpExposedTool.Target.AGENT, agentId, "Helper");
    }

    @AfterEach
    void tearDown() {
        McpCallerContextTestAccess.clear();
    }

    private Optional<String> send(String json) {
        return handler.tryHandle(json.getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode parse(Optional<String> response) throws Exception {
        assertTrue(response.isPresent());
        return MAPPER.readTree(response.get());
    }

    @Test
    void toolsListWithNothingToAddIsLeftToTheNormalTransport() {
        when(catalog.forCaller(caller)).thenReturn(List.of());

        assertTrue(send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}").isEmpty());
    }

    @Test
    void toolsListAddsTheCallersDynamicToolsAfterTheStaticOnesAndEchoesTheId() throws Exception {
        when(catalog.forCaller(caller)).thenReturn(List.of(agentTool));

        JsonNode response = parse(send("{\"jsonrpc\":\"2.0\",\"id\":\"req-7\",\"method\":\"tools/list\",\"params\":{}}"));

        assertEquals("2.0", response.get("jsonrpc").asText());
        assertEquals("req-7", response.get("id").asText());
        JsonNode tools = response.get("result").get("tools");
        assertEquals(2, tools.size());
        assertEquals("call_agent", tools.get(0).get("name").asText());
        assertEquals("agent_helper", tools.get(1).get("name").asText());
        assertEquals("Helper tool", tools.get(1).get("description").asText());
        assertEquals("object", tools.get(1).get("inputSchema").get("type").asText());
    }

    @Test
    void aBrokenToolSchemaFallsBackToAnEmptyObjectSchema() throws Exception {
        McpExposedTool broken = new McpExposedTool("agent_broken", "d", "not json", McpExposedTool.Target.AGENT, agentId, "B");
        when(catalog.forCaller(caller)).thenReturn(List.of(broken));

        JsonNode tools = parse(send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")).get("result").get("tools");

        assertEquals("object", tools.get(1).get("inputSchema").get("type").asText());
    }

    @Test
    void callsToStaticToolsAreNeverIntercepted() {
        assertTrue(send("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"call_agent\",\"arguments\":{}}}").isEmpty());
        assertTrue(send("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"search_knowledge\"}}").isEmpty());
        verifyNoInteractions(catalog, invoker);
    }

    @Test
    void aDynamicCallRunsThroughTheInvokerAndReturnsMcpContent() throws Exception {
        when(catalog.find(caller, "agent_helper")).thenReturn(Optional.of(agentTool));
        when(invoker.invoke(eq(caller), eq(agentTool), any())).thenReturn(McpToolResult.ok("the reply", "meta line"));

        JsonNode response = parse(send(
            "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"agent_helper\",\"arguments\":{\"message\":\"hi\"}}}"));

        assertEquals(5, response.get("id").asInt());
        JsonNode result = response.get("result");
        assertFalse(result.get("isError").asBoolean());
        assertEquals("text", result.get("content").get(0).get("type").asText());
        assertEquals("the reply", result.get("content").get(0).get("text").asText());
        assertEquals("meta line", result.get("content").get(1).get("text").asText());
    }

    @Test
    void aToolLevelFailureIsAnIsErrorResultNotAJsonRpcError() throws Exception {
        when(catalog.find(caller, "agent_helper")).thenReturn(Optional.of(agentTool));
        when(invoker.invoke(eq(caller), eq(agentTool), any())).thenReturn(McpToolResult.failure("budget gone"));

        JsonNode response = parse(send(
            "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\",\"params\":{\"name\":\"agent_helper\",\"arguments\":{\"message\":\"hi\"}}}"));

        assertFalse(response.has("error"));
        assertTrue(response.get("result").get("isError").asBoolean());
        assertEquals("budget gone", response.get("result").get("content").get(0).get("text").asText());
    }

    @Test
    void aDynamicNameNotInThisCallersCatalogIsUnknownAuditedAndNeverExecuted() throws Exception {
        when(catalog.find(caller, "agent_someone_elses")).thenReturn(Optional.empty());

        JsonNode response = parse(send(
            "{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"tools/call\",\"params\":{\"name\":\"agent_someone_elses\",\"arguments\":{\"message\":\"hi\"}}}"));

        assertEquals(-32602, response.get("error").get("code").asInt());
        assertEquals("Unknown tool", response.get("error").get("message").asText());
        verify(invoker).recordDenied(caller, "agent_someone_elses");
        verify(invoker, never()).invoke(any(), any(), any());
    }

    @Test
    void getRunIsTreatedAsADynamicName() throws Exception {
        when(catalog.find(caller, "get_run")).thenReturn(Optional.empty());

        JsonNode response = parse(send(
            "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\",\"params\":{\"name\":\"get_run\",\"arguments\":{\"run_id\":\"x\"}}}"));

        assertEquals(-32602, response.get("error").get("code").asInt());
        verify(invoker, never()).invoke(any(), any(), any());
    }

    @Test
    void nonObjectArgumentsAreAToolErrorAndNothingRuns() throws Exception {
        when(catalog.find(caller, "agent_helper")).thenReturn(Optional.of(agentTool));

        JsonNode response = parse(send(
            "{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"tools/call\",\"params\":{\"name\":\"agent_helper\",\"arguments\":\"hi\"}}"));

        assertTrue(response.get("result").get("isError").asBoolean());
        verify(invoker, never()).invoke(any(), any(), any());
    }

    @Test
    void notificationsOtherMethodsAndGarbageAreLeftAlone() {
        assertTrue(send("{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\"}").isEmpty());
        assertTrue(send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}").isEmpty());
        assertTrue(send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}").isEmpty());
        assertTrue(send("[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}]").isEmpty());
        assertTrue(send("not json at all").isEmpty());
        assertTrue(handler.tryHandle(new byte[0]).isEmpty());
        verifyNoInteractions(catalog, invoker);
    }

    @Test
    void withoutACallerContextNothingIsHandled() {
        McpCallerContextTestAccess.clear();

        assertTrue(send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}").isEmpty());
        verifyNoInteractions(catalog, invoker);
    }

    @Test
    void anUnexpectedFailureBecomesAToolErrorNeverAnException() throws Exception {
        when(catalog.forCaller(caller)).thenThrow(new IllegalStateException("db down"));

        JsonNode response = parse(send("{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"tools/list\"}"));

        assertTrue(response.get("result").get("isError").asBoolean());
        assertFalse(response.toString().contains("db down"), "internal error text must not reach the client");
    }
}
