package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.tools.mcpapps.McpAppsConfig;
import com.agenticai.core.agents.tools.mcpapps.McpAppsService;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Flag-off is a no-op and flag-on adds one additive key to the tool_result step event, nothing
 * else.
 */
class McpAppsStepEventTest {

    private static final ObjectMapper JSON = com.agenticai.core.json.JsonMappers.plain();
    private static final String MARKER = AgentStepEventEmitter.STEP_EVENT_MARKER;

    private static ToolCallback tool(String name, String reply) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return new DefaultToolDefinition(name, "d", "{}");
            }

            @Override
            public String call(String toolInput) {
                return reply;
            }
        };
    }

    private static List<String> run(McpAppStepHook hook) {
        AgentStepEventEmitter emitter = AgentStepEventEmitter.active();
        List<String> chunks = new CopyOnWriteArrayList<>();
        emitter.asFlux().subscribe(chunks::add);
        new StepEventToolCallback(tool("mcp__weather__forecast", "{\"temp\":31}"), emitter, hook)
                .call("{\"city\":\"Pune\"}");
        return chunks;
    }

    @Test
    void withNoHookTheEventIsByteIdenticalToTheLegacyShape() throws Exception {
        List<String> withNull = run(null);
        AgentStepEventEmitter emitter = AgentStepEventEmitter.active();
        List<String> legacy = new CopyOnWriteArrayList<>();
        emitter.asFlux().subscribe(legacy::add);
        new StepEventToolCallback(tool("mcp__weather__forecast", "{\"temp\":31}"), emitter)
                .call("{\"city\":\"Pune\"}");

        assertEquals(strip(legacy), strip(withNull));
        JsonNode result = JSON.readTree(withNull.get(1).substring(MARKER.length()));
        assertEquals("tool_result", result.get("type").asString());
        assertFalse(result.has("app"));
    }

    @Test
    void aHookThatReturnsNothingAddsNothing() throws Exception {
        List<String> chunks = run((t, a, o) -> null);
        assertFalse(JSON.readTree(chunks.get(1).substring(MARKER.length())).has("app"));
    }

    @Test
    void aThrowingHookNeverBreaksTheToolCall() throws Exception {
        List<String> chunks =
                run(
                        (t, a, o) -> {
                            throw new IllegalStateException("boom");
                        });
        assertEquals(2, chunks.size());
        assertFalse(JSON.readTree(chunks.get(1).substring(MARKER.length())).has("app"));
    }

    @Test
    void anAppPointerIsAdditiveAndSingleLine() throws Exception {
        List<String> chunks =
                run(
                        (t, a, o) ->
                                Map.of(
                                        "appId",
                                        "abc",
                                        "server",
                                        "weather",
                                        "tool",
                                        "forecast",
                                        "uri",
                                        "ui://weather/forecast"));
        String raw = chunks.get(1);
        assertTrue(raw.indexOf('\n') < 0);
        JsonNode result = JSON.readTree(raw.substring(MARKER.length()));
        assertEquals("abc", result.get("app").get("appId").asString());
        assertEquals("tool_result", result.get("type").asString());
        assertNotNull(result.get("preview"));
    }

    @Test
    void captureIsANoOpWithoutAServiceAndNeverThrows() {
        McpAppsCapture.capture(null, "t", UUID.randomUUID(), "srv", List.of());
        McpAppsCapture.forget(null, "t", UUID.randomUUID());
        McpAppsCapture.capture(
                new McpAppsService(McpAppsConfig.defaults(), (t, i) -> null),
                null,
                null,
                "srv",
                null);
    }

    @Test
    void captureKeysTheDeclarationByTheFinalNamespacedToolName() {
        McpAppsService svc = new McpAppsService(McpAppsConfig.defaults(), (t, i) -> null);
        UUID reg = UUID.randomUUID();
        List<McpSchema.Tool> tools = new ArrayList<>();
        tools.add(
                new McpSchema.Tool(
                        "forecast",
                        null,
                        "d",
                        Map.of("type", "object"),
                        null,
                        null,
                        Map.of("ui", Map.of("resourceUri", "ui://weather/forecast"))));
        tools.add(
                new McpSchema.Tool("plain", null, "d", Map.of("type", "object"), null, null, null));
        tools.add(
                new McpSchema.Tool(
                        "evil",
                        null,
                        "d",
                        Map.of("type", "object"),
                        null,
                        null,
                        Map.of("ui", Map.of("resourceUri", "https://evil.example/x"))));
        McpAppsCapture.capture(svc, "t", reg, "My Weather", tools);

        // the exact string the model/step events carry for that tool
        String finalName = NamespacedMcpToolCallback.finalToolName("My Weather", "forecast");
        var hook =
                svc.hookFor(
                        com.agenticai.core.security.TenantContext.of("t", "u", java.util.Set.of()),
                        UUID.randomUUID(),
                        "s");
        assertNotNull(hook.onToolResult(finalName, "{}", "ok"));
        assertEquals(
                null,
                hook.onToolResult(
                        NamespacedMcpToolCallback.finalToolName("My Weather", "plain"),
                        "{}",
                        "ok"));
        assertEquals(
                null,
                hook.onToolResult(
                        NamespacedMcpToolCallback.finalToolName("My Weather", "evil"), "{}", "ok"));
    }

    @Test
    void theSdkExposesToolMetaFromTheWireFormat() throws Exception {
        // 0.18.2 Tool.meta() must be populated from "_meta" in a real tools/list payload.
        String wire =
                "{\"name\":\"forecast\",\"description\":\"d\",\"inputSchema\":{\"type\":\"object\"},"
                        + "\"_meta\":{\"ui\":{\"resourceUri\":\"ui://weather/forecast\",\"csp\":{\"connectDomains\":[\"https://api.x.example\"]}}}}";
        McpSchema.Tool t =
                com.agenticai.core.json.JsonMappers.plain().readValue(wire, McpSchema.Tool.class);
        var decl =
                com.agenticai.core.agents.tools.mcpapps.McpAppUiMeta.declarationOf(t.meta())
                        .orElseThrow();
        assertEquals("ui://weather/forecast", decl.resourceUri());
        assertEquals(List.of("https://api.x.example"), decl.csp().connectDomains());
    }

    private static List<String> strip(List<String> chunks) {
        // "ms" is wall-clock; compare everything else
        List<String> out = new ArrayList<>();
        for (String c : chunks) {
            out.add(c.replaceAll("\"ms\":\\d+", "\"ms\":0"));
        }
        return out;
    }
}
