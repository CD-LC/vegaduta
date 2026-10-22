package com.agenticai.core.agents.tools.mcpapps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.tools.AgentToolProvider;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.sharing.PermissionLevel;
import io.modelcontextprotocol.client.McpSyncClient;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.ObjectMapper;

/**
 * An app's tools/call must reach a tool ONLY through AgentToolProvider's guarded resolution (the
 * chain that carries the approval gate, argument policies, classification dispatch gate and audit
 * log) - never the raw MCP client.
 */
class McpAppsControllerTest {

    private static final String TENANT = "t_acme";
    private static final String USER = "alice";
    private static final UUID REG = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    private static final UUID AGENT = UUID.randomUUID();
    private static final ObjectMapper JSON = com.agenticai.core.json.JsonMappers.plain();

    private McpSyncClient rawClient;
    private AgentService agentService;
    private AgentToolProvider provider;
    private Agent agent;
    private McpAppsService svc;
    private McpAppsController controller;
    private String appId;

    @BeforeEach
    void setUp() {
        rawClient = mock(McpSyncClient.class);
        agentService = mock(AgentService.class);
        provider = mock(AgentToolProvider.class);
        agent = mock(Agent.class);
        when(agent.getAllowedToolNames()).thenReturn(Set.of());
        when(agentService.requireAccess(AGENT, PermissionLevel.RUNNER)).thenReturn(agent);

        svc = new McpAppsService(McpAppsConfig.defaults(), (t, id) -> rawClient);
        svc.recordServer(
                TENANT,
                REG,
                "weather",
                List.of(
                        new McpAppsService.ToolEntry(
                                "forecast",
                                "mcp__weather__forecast",
                                new McpAppUiMeta.Declaration(
                                        "ui://weather/forecast", McpAppUiMeta.DeclaredCsp.NONE)),
                        new McpAppsService.ToolEntry("refresh", "mcp__weather__refresh", null)));
        svc.recordServer(
                TENANT,
                OTHER,
                "mail",
                List.of(new McpAppsService.ToolEntry("send", "mcp__mail__send", null)));
        controller = new McpAppsController(svc, agentService, provider);

        TenantContext.set(TenantContext.of(TENANT, USER, Set.of()));
        appId =
                (String)
                        svc.hookFor(TenantContext.of(TENANT, USER, Set.of()), AGENT, "sess")
                                .onToolResult("mcp__weather__forecast", "{}", "ok")
                                .get("appId");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static ToolCallback guarded(String name, String reply) {
        ToolCallback cb = mock(ToolCallback.class);
        when(cb.getToolDefinition()).thenReturn(new DefaultToolDefinition(name, "d", "{}"));
        when(cb.call(anyString())).thenReturn(reply);
        return cb;
    }

    private static McpAppsController.ToolCallRequest req(String name, String argsJson)
            throws Exception {
        return new McpAppsController.ToolCallRequest(name, JSON.readTree(argsJson));
    }

    @Test
    void theCallRunsThroughTheGuardedChainNotTheRawClient() throws Exception {
        ToolCallback refresh = guarded("mcp__weather__refresh", "{\"ok\":true}");
        when(provider.resolveToolCandidatesFor(eq(AGENT), eq("mcpapp:" + appId), eq(USER)))
                .thenReturn(List.of(refresh));

        ResponseEntity<McpAppsController.ToolCallResponse> res =
                controller.callTool(appId, req("refresh", "{\"city\":\"Pune\"}"));

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertFalse(res.getBody().isError());
        assertEquals("{\"ok\":true}", res.getBody().text());
        verify(agentService).requireAccess(AGENT, PermissionLevel.RUNNER);
        verify(provider).resolveToolCandidatesFor(AGENT, "mcpapp:" + appId, USER);
        verify(refresh).call("{\"city\":\"Pune\"}");
        verifyNoInteractions(rawClient);
    }

    @Test
    void aGatedCallComesBackAsTheUsualApprovalResultAndIsNotExecutedTwice() throws Exception {
        // GuardedToolCallback parks a gated call and returns a soft-stop string; the controller
        // must pass it through.
        ToolCallback refresh =
                guarded(
                        "mcp__weather__refresh",
                        "This action needs approval before it can run (request 42).");
        when(provider.resolveToolCandidatesFor(any(), anyString(), anyString()))
                .thenReturn(List.of(refresh));

        ResponseEntity<McpAppsController.ToolCallResponse> res =
                controller.callTool(appId, req("refresh", "{}"));

        assertTrue(res.getBody().text().contains("needs approval"));
        verify(refresh).call("{}");
        verifyNoInteractions(rawClient);
    }

    @Test
    void aToolOfAnotherServerIsRefusedBeforeAnyResolution() throws Exception {
        McpAppsException e =
                assertThrows(
                        McpAppsException.class,
                        () -> controller.callTool(appId, req("send", "{}")));
        assertEquals(McpAppsException.Code.REFUSED, e.code());
        McpAppsException e2 =
                assertThrows(
                        McpAppsException.class,
                        () -> controller.callTool(appId, req("mcp__mail__send", "{}")));
        assertEquals(McpAppsException.Code.REFUSED, e2.code());
        verify(provider, never()).resolveToolCandidatesFor(any(), anyString(), anyString());
        verifyNoInteractions(rawClient);
    }

    @Test
    void anAgentAllowListStillApplies() throws Exception {
        when(agent.getAllowedToolNames()).thenReturn(Set.of("some_other_tool"));
        ResponseEntity<McpAppsController.ToolCallResponse> res =
                controller.callTool(appId, req("refresh", "{}"));
        assertEquals(HttpStatus.FORBIDDEN, res.getStatusCode());
        assertTrue(res.getBody().isError());
        verify(provider, never()).resolveToolCandidatesFor(any(), anyString(), anyString());
    }

    @Test
    void aToolTheAgentCannotCurrentlyResolveIs409() throws Exception {
        when(provider.resolveToolCandidatesFor(any(), anyString(), anyString()))
                .thenReturn(List.of());
        assertEquals(
                HttpStatus.CONFLICT,
                controller.callTool(appId, req("refresh", "{}")).getStatusCode());
    }

    @Test
    void aFailingToolReturnsAnErrorWithoutLeakingDetail() throws Exception {
        ToolCallback boom = guarded("mcp__weather__refresh", "x");
        when(boom.call(anyString())).thenThrow(new IllegalStateException("db password=hunter2"));
        when(provider.resolveToolCandidatesFor(any(), anyString(), anyString()))
                .thenReturn(List.of(boom));
        ResponseEntity<McpAppsController.ToolCallResponse> res =
                controller.callTool(appId, req("refresh", "{}"));
        assertTrue(res.getBody().isError());
        assertFalse(res.getBody().text().contains("hunter2"));
    }

    @Test
    void anotherUsersAppIdIsNotFound() throws Exception {
        TenantContext.set(TenantContext.of(TENANT, "mallory", Set.of()));
        assertEquals(
                McpAppsException.Code.NOT_FOUND,
                assertThrows(
                                McpAppsException.class,
                                () -> controller.callTool(appId, req("refresh", "{}")))
                        .code());
        verify(agentService, never()).requireAccess(any(), any());
    }

    @Test
    void refusalsMapToHttpStatuses() {
        assertEquals(
                HttpStatus.FORBIDDEN,
                controller
                        .refused(new McpAppsException(McpAppsException.Code.REFUSED, "x"))
                        .getStatusCode());
        assertEquals(
                HttpStatus.NOT_FOUND,
                controller
                        .refused(new McpAppsException(McpAppsException.Code.NOT_FOUND, "x"))
                        .getStatusCode());
        assertEquals(
                HttpStatus.TOO_MANY_REQUESTS,
                controller
                        .refused(new McpAppsException(McpAppsException.Code.RATE_LIMITED, "x"))
                        .getStatusCode());
        assertEquals(
                HttpStatus.PAYLOAD_TOO_LARGE,
                controller
                        .refused(new McpAppsException(McpAppsException.Code.TOO_LARGE, "x"))
                        .getStatusCode());
        assertEquals(
                Map.of("error", "BAD_REQUEST", "message", "m"),
                controller
                        .refused(new McpAppsException(McpAppsException.Code.BAD_REQUEST, "m"))
                        .getBody());
    }
}
