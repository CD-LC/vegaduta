package com.agenticai.core.mcp.dto;

import com.agenticai.core.mcp.McpServerType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

/**
 * Registers or updates an MCP server for this tenant.
 *
 * <p>Two shapes, both valid:
 * <ul>
 *   <li><b>Static</b> (unchanged, Stage 4 MVP): {@code command} is null.
 *       {@code name} should match a connection name already wired in
 *       {@code application-mcp.yml} for {@code enabled} to mean anything.</li>
 *   <li><b>Dynamic</b> (#424): {@code command} is set (e.g. {@code "npx"}),
 *       with {@code args} (e.g. {@code ["-y",
 *       "@modelcontextprotocol/server-brave-search"]}) and {@code env} (e.g.
 *       {@code {"BRAVE_API_KEY": "..."}}). {@code env} values are encrypted
 *       at rest ({@link com.agenticai.core.mcp.McpRegistryService}) and
 *       never echoed back in any response - see {@code
 *       McpServerRegistrationResponse}. Submitting this immediately attempts
 *       a real connection (see {@code DynamicMcpConnectionManager} in
 *       core/agents) - no YAML edit, no restart.</li>
 * </ul>
 */
public record McpServerRegistrationRequest(
    @NotBlank(message = "name is required") String name,
    @NotNull(message = "type is required") McpServerType type,
    String description,
    boolean enabled,
    String command,
    List<String> args,
    Map<String, String> env,
    String url
) {
    /**
     * Full non-remote shape (static, or dynamic STDIO). {@code url} defaults to
     * null - retained so every existing caller/test that builds the 7-arg
     * command/args/env request keeps compiling unchanged.
     */
    public McpServerRegistrationRequest(
        String name, McpServerType type, String description, boolean enabled,
        String command, List<String> args, Map<String, String> env
    ) {
        this(name, type, description, enabled, command, args, env, null);
    }

    /** Convenience for the common static-registration case (no dynamic connection config). */
    public McpServerRegistrationRequest(String name, McpServerType type, String description, boolean enabled) {
        this(name, type, description, enabled, null, null, null, null);
    }
}
