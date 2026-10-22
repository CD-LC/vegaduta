package com.agenticai.core.agents.tools.mcpapps;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.tools.AgentToolProvider;
import com.agenticai.core.sharing.PermissionLevel;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Tenant-scoped surface for MCP Apps ({@code app.mcp.apps.enabled}, default off - with the flag off
 * this controller is not registered and the paths 404 like any unknown route). Authenticated like
 * every other {@code /api/**} route (the default {@code anyRequest().authenticated()}); the
 * app-session id additionally binds tenant and user (see {@link McpAppsService}).
 *
 * <ul>
 *   <li>{@code GET /api/mcp-apps/{appId}} - the app HTML + CSP + tool input/result for the browser
 *       to render in a sandboxed, origin-less iframe.
 *   <li>{@code POST /api/mcp-apps/{appId}/tools/call} - a tool call the app asked the host to make.
 *       The app is untrusted, so this is NOT a shortcut to the MCP client: it re-resolves the
 *       agent's tool set through {@link AgentToolProvider#resolveToolCandidatesFor} (the same
 *       merge, allow-list, {@code GuardedToolCallback} approval gate, argument policies,
 *       dispatch/classification gate and {@code LoggingToolCallback} audit a model-issued call gets
 *       - the precedent is {@code EdgeToolExecutionController}) and calls the guarded callback. A
 *       gated call comes back as the usual "awaiting approval" tool result; it is never executed
 *       behind the approver's back.
 * </ul>
 */
@RestController
@RequestMapping("/api/mcp-apps")
@ConditionalOnProperty(name = "app.mcp.apps.enabled", havingValue = "true")
public class McpAppsController {

    private static final Logger log = LoggerFactory.getLogger(McpAppsController.class);

    private final McpAppsService apps;
    private final AgentService agentService;
    private final AgentToolProvider agentToolProvider;

    public McpAppsController(
            McpAppsService apps, AgentService agentService, AgentToolProvider agentToolProvider) {
        this.apps = apps;
        this.agentService = agentService;
        this.agentToolProvider = agentToolProvider;
    }

    public record ToolCallRequest(String name, JsonNode arguments) {}

    /**
     * {@code text} is the (already guard-sanitised) tool result; {@code isError} marks a
     * failed/refused call.
     */
    public record ToolCallResponse(boolean isError, String text) {}

    @GetMapping("/{appId}")
    public ResponseEntity<McpAppsService.AppView> view(@PathVariable String appId) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(apps.view(appId));
    }

    @PostMapping("/{appId}/tools/call")
    public ResponseEntity<ToolCallResponse> callTool(
            @PathVariable String appId, @RequestBody ToolCallRequest request) {
        if (request == null) {
            throw new McpAppsException(
                    McpAppsException.Code.BAD_REQUEST, "A tool name is required.");
        }
        String args = request.arguments() == null ? "{}" : request.arguments().toString();
        McpAppsService.ToolCallAuth auth = apps.authorizeToolCall(appId, request.name(), args);

        Agent agent = agentService.requireAccess(auth.agentId(), PermissionLevel.RUNNER);
        Set<String> allowed = agent.getAllowedToolNames();
        if (allowed != null && !allowed.isEmpty() && !allowed.contains(auth.finalToolName())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(
                            new ToolCallResponse(
                                    true, "This agent does not have access to that tool."));
        }
        List<ToolCallback> tools =
                agentToolProvider.resolveToolCandidatesFor(
                        auth.agentId(), auth.logSessionId(), auth.subject());
        ToolCallback callback =
                tools.stream()
                        .filter(t -> auth.finalToolName().equals(t.getToolDefinition().name()))
                        .findFirst()
                        .orElse(null);
        if (callback == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(
                            new ToolCallResponse(
                                    true, "That tool is not available for this agent right now."));
        }
        try {
            String result = callback.call(auth.argumentsJson());
            String text = result == null ? "" : result;
            if (text.length() > apps.config().maxResultChars()) {
                text = text.substring(0, apps.config().maxResultChars());
            }
            return ResponseEntity.ok()
                    .header("Cache-Control", "no-store")
                    .body(new ToolCallResponse(false, text));
        } catch (RuntimeException e) {
            // The decorator stack already logged the failure; keep the app-facing message
            // detail-free.
            log.warn(
                    "MCP app tool call failed: app {} tool {}",
                    auth.appId(),
                    auth.finalToolName(),
                    e);
            return ResponseEntity.ok()
                    .body(new ToolCallResponse(true, "The tool could not complete this time."));
        }
    }

    @ExceptionHandler(McpAppsException.class)
    public ResponseEntity<Map<String, String>> refused(McpAppsException e) {
        HttpStatus status =
                switch (e.code()) {
                    case NOT_FOUND -> HttpStatus.NOT_FOUND;
                    case REFUSED -> HttpStatus.FORBIDDEN;
                    case TOO_LARGE -> HttpStatus.CONTENT_TOO_LARGE;
                    case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
                    case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
                    case UNAVAILABLE -> HttpStatus.BAD_GATEWAY;
                };
        return ResponseEntity.status(status)
                .body(Map.of("error", e.code().name(), "message", e.getMessage()));
    }
}
