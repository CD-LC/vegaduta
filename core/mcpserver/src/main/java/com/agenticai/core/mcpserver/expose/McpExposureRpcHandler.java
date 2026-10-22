package com.agenticai.core.mcpserver.expose;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.mcpserver.McpCallerContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * JSON-RPC level hook that lets ONE authenticated caller see - and call - its own exposed agents /
 * workflows as MCP tools, on top of the five static tools.
 *
 * <p><b>Why this is a hook in the auth filter and not a tool registration.</b> Spring AI's MCP
 * server keeps a single, application-wide tool registry (see {@code McpServerToolConfig}'s
 * javadoc): a tool registered there is advertised to EVERY tenant. Per-tenant tools therefore
 * cannot be registered with it. {@link com.agenticai.core.mcpserver.McpApiKeyAuthFilter} - which
 * has by then authenticated the key, set the tenant context and the caller's grant sets, and
 * applied the per-key rate limit - hands the already-read request body to {@link #tryHandle}; this
 * class answers only three things and passes everything else (initialize, ping, resources, the five
 * static tools' calls, notifications, batches) through untouched:
 *
 * <ul>
 *   <li>{@code tools/list} - the static tools plus this caller's dynamic ones, but ONLY when the
 *       caller has at least one dynamic tool; otherwise the SDK answers exactly as it always did;
 *   <li>{@code tools/call} of a dynamic name ({@code agent_*}, {@code workflow_*}, {@code get_run})
 *       - executed by {@link McpExposedToolInvoker}; a name that is not in THIS caller's catalog
 *       gets an "Unknown tool" error (identical for "does not exist", "not exposed" and "not
 *       granted", so nothing is revealed) and an audit row.
 * </ul>
 *
 * <p>Answered as a plain {@code application/json} response, which the Streamable HTTP transport
 * allows for a POSTed request. Tool arguments and results are never logged here.
 */
public class McpExposureRpcHandler {

    private static final Logger log = LoggerFactory.getLogger(McpExposureRpcHandler.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();
    private static final int INVALID_PARAMS = -32602;

    private final Supplier<List<ToolCallback>> staticTools;
    private final McpExposedToolCatalog catalog;
    private final McpExposedToolInvoker invoker;

    public McpExposureRpcHandler(
            Supplier<List<ToolCallback>> staticTools,
            McpExposedToolCatalog catalog,
            McpExposedToolInvoker invoker) {
        this.staticTools = staticTools;
        this.catalog = catalog;
        this.invoker = invoker;
    }

    /**
     * @param requestBody the raw JSON-RPC request body
     * @return the complete JSON-RPC response text if this class handled the request, empty to let
     *     the normal MCP transport handle it. Never throws.
     */
    public Optional<String> tryHandle(byte[] requestBody) {
        JsonNode root;
        try {
            root = MAPPER.readTree(requestBody);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        if (root == null || !root.isObject()) {
            return Optional.empty();
        }
        JsonNode id = root.get("id");
        if (id == null || id.isNull()) {
            return Optional.empty(); // a notification - nothing to answer
        }
        Optional<McpCallerContext> caller = McpCallerContext.current();
        if (caller.isEmpty()) {
            return Optional.empty();
        }
        String method = root.path("method").asString("");
        try {
            return switch (method) {
                case "tools/list" -> handleList(id, caller.get());
                case "tools/call" -> handleCall(id, root.path("params"), caller.get());
                default -> Optional.empty();
            };
        } catch (RuntimeException e) {
            log.warn("Exposed-tool JSON-RPC handling failed for method {}", method, e);
            return Optional.of(toolError(id, "The request could not be processed."));
        }
    }

    private Optional<String> handleList(JsonNode id, McpCallerContext caller) {
        List<McpExposedTool> dynamic = catalog.forCaller(caller);
        if (dynamic.isEmpty()) {
            return Optional.empty();
        }
        ArrayNode tools = MAPPER.createArrayNode();
        for (ToolCallback callback : staticTools.get()) {
            ToolDefinition definition = callback.getToolDefinition();
            tools.add(
                    toolNode(
                            definition.name(), definition.description(), definition.inputSchema()));
        }
        for (McpExposedTool tool : dynamic) {
            tools.add(toolNode(tool.name(), tool.description(), tool.inputSchemaJson()));
        }
        ObjectNode response = envelope(id);
        response.putObject("result").set("tools", tools);
        return Optional.of(response.toString());
    }

    private Optional<String> handleCall(JsonNode id, JsonNode params, McpCallerContext caller) {
        String name = params.path("name").asString("");
        if (!McpToolSlugs.isDynamicToolName(name)
                && !McpExposedToolCatalog.GET_RUN_TOOL.equals(name)) {
            return Optional.empty(); // one of the static tools - the normal transport handles it
        }
        Optional<McpExposedTool> tool = catalog.find(caller, name);
        if (tool.isEmpty()) {
            invoker.recordDenied(caller, name);
            ObjectNode response = envelope(id);
            ObjectNode error = response.putObject("error");
            error.put("code", INVALID_PARAMS);
            error.put("message", "Unknown tool");
            return Optional.of(response.toString());
        }
        JsonNode arguments = params.get("arguments");
        if (arguments != null && !arguments.isNull() && !arguments.isObject()) {
            return Optional.of(toolError(id, "arguments must be a JSON object."));
        }
        McpToolResult result = invoker.invoke(caller, tool.get(), arguments);
        ObjectNode response = envelope(id);
        ObjectNode body = response.putObject("result");
        ArrayNode content = body.putArray("content");
        for (String text : result.texts()) {
            content.addObject().put("type", "text").put("text", text);
        }
        body.put("isError", result.error());
        return Optional.of(response.toString());
    }

    private static ObjectNode toolNode(String name, String description, String inputSchemaJson) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("name", name);
        node.put("description", description);
        JsonNode schema;
        try {
            schema = MAPPER.readTree(inputSchemaJson);
        } catch (RuntimeException e) {
            schema = null;
        }
        if (schema == null || !schema.isObject()) {
            ObjectNode fallback = MAPPER.createObjectNode();
            fallback.put("type", "object");
            schema = fallback;
        }
        node.set("inputSchema", schema);
        return node;
    }

    private static ObjectNode envelope(JsonNode id) {
        ObjectNode response = MAPPER.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        return response;
    }

    private static String toolError(JsonNode id, String message) {
        ObjectNode response = envelope(id);
        ObjectNode body = response.putObject("result");
        body.putArray("content").addObject().put("type", "text").put("text", message);
        body.put("isError", true);
        return response.toString();
    }
}
