package com.agenticai.core.agents.tools;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * Pentest L4: prefixes an MCP server's tool names with {@code mcp__<server>__} before they ever
 * reach the model or {@code AgentToolProvider}'s name-keyed resolution ({@code
 * mergeFavoringBuiltins}, {@code filterByAllowedTools}). Without this, a hostile or simply
 * misconfigured MCP server can advertise a tool literally named like a trusted platform built-in
 * (e.g. {@code search_knowledge_base}) - {@code mergeFavoringBuiltins} already makes the built-in
 * win that exact collision (the MCP tool is dropped for the turn, never allowed to masquerade under
 * the trusted name), but until this class the MCP tool's bare name still had no structural,
 * always-on distinction from a first-party capability anywhere it was logged, allow-listed, or
 * reasoned about by the model itself - a name that merely resembled (rather than exactly matched) a
 * trusted tool was invisible to that defense.
 *
 * <p>Sits OUTSIDE {@link SanitizedMcpToolCallback} in the wrap order (that class still owns the
 * discovery-time poisoning clamp + control-char strip on the name this class then prefixes) and,
 * like it, only renames what the MODEL/platform sees: {@link #call} and {@link #call(String,
 * ToolContext)} delegate unchanged, so the real MCP invocation - which Spring AI's own {@code
 * SyncMcpToolCallback} keeps keyed to the tool's ORIGINAL, unprefixed name internally (confirmed by
 * decompiling {@code spring-ai-mcp} 1.1.7: {@code call()} builds its {@code CallToolRequest} from
 * the wrapped {@code McpSchema.Tool.name()}, never from {@code getToolDefinition().name()}) - is
 * completely unaffected by this renaming.
 *
 * <p>Originally applied only to dynamically-registered (tenant/admin-configured via the Tools UI,
 * #424) servers (see {@code DynamicMcpConnectionManager#connect}) on the theory that YAML-wired
 * static servers are operator-configured, first-party-reviewed infrastructure, so namespacing them
 * would be needless churn on names an operator already controls. That framing covered spoofing (an
 * untrusted server impersonating a trusted name) but missed a second, purely operational failure
 * mode: two TRUSTED static servers can independently ship a same-named tool (e.g. both bundle a
 * {@code search}) with no malice involved, and before this widened scope {@code
 * resolveStaticMcpTools} (see {@code AgentToolProvider}) had no way to tell those two collisions
 * apart from "an MCP tool collided with a built-in" - {@code mergeFavoringBuiltins} silently
 * dropped the second one and mis-blamed a built-in that was never involved. Now applied to both
 * static and dynamic resolution paths for that reason - see {@code
 * AgentToolProvider#resolveStaticMcpTools}'s javadoc.
 *
 * <p><b>Compatibility note:</b> this changes the resolved tool name for every MCP tool (dynamic
 * from the start, static as of the widening above) going forward. Two existing features key off
 * that exact string: {@code Agent.allowedToolNames} (an opt-in per-agent allow-list filter) and any
 * workflow node that invokes a tool by its exact name. Both are opt-in, low-blast-radius features -
 * an agent/workflow with no such reference is unaffected - but a pre-existing reference to a bare
 * MCP tool name will stop matching post deploy and needs to be re-saved against the new prefixed
 * name; see the MCP pentest doc's L4 entry.
 */
final class NamespacedMcpToolCallback implements ToolCallback {

    /** Keeps the server-name segment from eating the whole name budget on a long operator label. */
    private static final int MAX_SERVER_SEGMENT_CHARS = 40;

    private final ToolCallback delegate;
    private final ToolDefinition namespacedDefinition;

    NamespacedMcpToolCallback(ToolCallback delegate, String serverName) {
        this.delegate = delegate;
        ToolDefinition original = delegate.getToolDefinition();
        String prefix = serverPrefix(serverName);
        String combined = prefix + original.name();
        String name =
                combined.length() > McpToolOutputSanitizer.MAX_NAME_CHARS
                        ? combined.substring(0, McpToolOutputSanitizer.MAX_NAME_CHARS)
                        : combined;
        this.namespacedDefinition =
                new DefaultToolDefinition(name, original.description(), original.inputSchema());
    }

    /** The {@code mcp__<server>__} prefix every tool of one server carries. */
    static String serverPrefix(String serverName) {
        return "mcp__" + sanitizeSegment(serverName) + "__";
    }

    /**
     * The final platform-visible name of a tool an upstream server calls {@code rawToolName}: the
     * {@link SanitizedMcpToolCallback} name clamp followed by this class's namespacing, i.e.
     * exactly what {@code getToolDefinition().name()} returns after both wrappers. Lets MCP
     * annotation capture key a discovered {@code McpSchema.Tool} by the same string the
     * classification gate later asks about.
     */
    static String finalToolName(String serverName, String rawToolName) {
        String raw = rawToolName == null ? "" : rawToolName;
        String clamped = McpToolOutputSanitizer.sanitizeName(raw);
        String name = clamped == null || clamped.isBlank() ? raw : clamped;
        String combined = serverPrefix(serverName) + name;
        return combined.length() > McpToolOutputSanitizer.MAX_NAME_CHARS
                ? combined.substring(0, McpToolOutputSanitizer.MAX_NAME_CHARS)
                : combined;
    }

    /**
     * Reduces an operator-typed, freeform server label (the registration's {@code name} field - no
     * charset constraints at save time) to a safe tool-name identifier segment: control/ANSI/
     * invisible-Unicode stripped (same {@link McpToolOutputSanitizer} pass as the tool name/
     * description themselves), then collapsed to {@code [A-Za-z0-9_-]}, with runs of any other
     * character folded to a single underscore. Never blank - falls back to {@code "server"} for a
     * label that sanitizes to nothing (e.g. all emoji/control chars).
     */
    private static String sanitizeSegment(String raw) {
        String cleaned = McpToolOutputSanitizer.stripControlAndAnsi(raw == null ? "" : raw);
        String safe =
                cleaned.replaceAll("[^A-Za-z0-9_-]+", "_")
                        .replaceAll("_{2,}", "_")
                        .replaceAll("^_+|_+$", "");
        if (safe.isBlank()) {
            safe = "server";
        }
        return safe.length() > MAX_SERVER_SEGMENT_CHARS
                ? safe.substring(0, MAX_SERVER_SEGMENT_CHARS)
                : safe;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return namespacedDefinition;
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return delegate.call(toolInput);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return delegate.call(toolInput, toolContext);
    }
}
