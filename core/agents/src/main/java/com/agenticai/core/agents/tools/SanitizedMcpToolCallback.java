package com.agenticai.core.agents.tools;

import java.util.Optional;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * Wraps a dynamic MCP server's advertised {@link ToolCallback} with {@link
 * McpToolOutputSanitizer}'s caps (MCP-DOCTOR hardening, 2026-07-25): the tool's name/description
 * are clamped ONCE at discovery (tool-description poisoning defense) and every result is
 * size-capped + control/ANSI-stripped before it re-enters the agent loop. Sits INSIDE the existing
 * {@code guardAndLog} chain ({@code AgentToolProvider} wraps whatever {@code
 * resolveDynamicToolCallbacks} returns), so guardrail content sanitization and logging still see
 * the already-bounded output.
 *
 * <p>The delegate keeps its own untouched tool object internally - the MCP server is still called
 * with its real tool name; only what the MODEL sees is clamped. Input schema is passed through
 * untouched: truncating JSON schema would corrupt it, and schema size is bounded far more
 * effectively by the description/name caps that dominate poisoning attacks.
 *
 * <p><b>Defensive MCP hardening follow-up (2026-08-05).</b> This class is also the single choke
 * point for BOTH MCP paths - {@code AgentToolProvider#resolveStaticMcpTools} wraps every YAML-wired
 * static callback in one of these directly, and {@code DynamicMcpConnectionManager#connect} wraps
 * every dynamic (#424) callback in one before {@link NamespacedMcpToolCallback} namespaces it - so
 * it is also the correct, structurally-scoped place to validate an LLM-constructed argument VALUE
 * before it ever reaches an MCP server's real implementation: {@link
 * McpToolArgumentGuard#checkArguments} runs on {@code toolInput} BEFORE {@link #delegate}{@code
 * .call(...)}, and a call whose arguments look like path traversal or an SSRF-shaped URL is
 * answered with a plain-text explanation instead of ever reaching the delegate - see that class's
 * javadoc for exactly what is/isn't flagged and why. A platform built-in tool never passes through
 * this class at all, so this check is automatically scoped to MCP-sourced tools only, with no extra
 * origin check needed here.
 *
 * <p><b>{@code delegate.call(...)} is deliberately NOT caught here (2026-08-13 correction).</b> An
 * earlier version of this class caught every delegate exception and converted it into a normal
 * returned string, reasoning that a failed MCP tool call shouldn't be able to kill the whole chat
 * turn - true, but the fix was in the wrong place: {@code AgentToolProvider#guardAndLog} wraps this
 * class INSIDE {@code LoggingToolCallback}, whose entire contract is catch-log-{@code rethrow}
 * (persists {@code ToolCallLog.errorMessage}, records the platform issue, trips the circuit
 * breaker, THEN rethrows) - see that class's own {@code call()}. Swallowing the exception here
 * meant {@code LoggingToolCallback} never saw a failure at all: every MCP error quietly logged as a
 * clean success, silently breaking three real downstream mechanisms that depend on that signal -
 * {@code AgentChatService #firstToolFailureSince}'s automatic re-plan/retry, the tool circuit
 * breaker's failure streak, and the trace UI's own error indicator (all confirmed live: an agent
 * that said "let me try again" never actually did, because the platform had no idea anything had
 * failed). The real fix for "a failed tool call must never crash a Gemini-backed turn" belongs
 * OUTSIDE {@code LoggingToolCallback}, not before it - see {@link
 * TenantContextPropagatingToolCallback}, the outermost wrapper in {@code guardAndLog}'s chain,
 * which is where that catch now lives instead.
 */
final class SanitizedMcpToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final ToolDefinition sanitizedDefinition;
    private final boolean guardArguments;

    SanitizedMcpToolCallback(ToolCallback delegate) {
        this(delegate, true);
    }

    /**
     * {@code guardArguments=false} keeps the output sanitisation and drops the argument tripwire.
     * For a first-party tool whose own executor already enforces SSRF/path rules and whose
     * arguments legitimately contain URLs and paths (an HTTP request body: a webhook payload, a
     * callback_url field).
     */
    SanitizedMcpToolCallback(ToolCallback delegate, boolean guardArguments) {
        this.guardArguments = guardArguments;
        this.delegate = delegate;
        ToolDefinition original = delegate.getToolDefinition();
        // DefaultToolDefinition asserts non-blank name/description/schema, and
        // a pathological definition that is ALL control chars would sanitize
        // to blank - fall back rather than blow up discovery for the whole
        // server (the original values already passed the same assertions).
        String name =
                orFallback(McpToolOutputSanitizer.sanitizeName(original.name()), original.name());
        String description =
                orFallback(
                        McpToolOutputSanitizer.sanitizeDescription(original.description()),
                        "(no description)");
        this.sanitizedDefinition =
                new DefaultToolDefinition(name, description, original.inputSchema());
    }

    private static String orFallback(String candidate, String fallback) {
        return candidate == null || candidate.isBlank() ? fallback : candidate;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return sanitizedDefinition;
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        Optional<String> blocked =
                guardArguments ? McpToolArgumentGuard.checkArguments(toolInput) : Optional.empty();
        if (blocked.isPresent()) {
            return blockedResult(blocked.get());
        }
        return McpToolOutputSanitizer.sanitizeResult(delegate.call(toolInput));
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        Optional<String> blocked =
                guardArguments ? McpToolArgumentGuard.checkArguments(toolInput) : Optional.empty();
        if (blocked.isPresent()) {
            return blockedResult(blocked.get());
        }
        return McpToolOutputSanitizer.sanitizeResult(delegate.call(toolInput, toolContext));
    }

    /**
     * Prose meant to be read by the model and relayed to a person (or acted on by adjusting the
     * next call) - same reasoning as {@code UnavailableToolCallback#call}. {@link
     * McpToolOutputSanitizer#ensureJsonObject} wraps it as a real JSON object before it leaves this
     * class: see that method's javadoc for why prose alone crashes a Gemini-backed turn. This path
     * never throws (the block happens before the delegate is ever called), so wrapping here is
     * unconditionally safe/correct - unlike a delegate FAILURE (see class javadoc's 2026-08-13 note
     * on why that must propagate as a real exception instead).
     */
    private String blockedResult(String reason) {
        return McpToolOutputSanitizer.ensureJsonObject(
                "This tool call ('"
                        + sanitizedDefinition.name()
                        + "') was blocked before it reached the MCP "
                        + "server because one of its arguments "
                        + reason
                        + " - a baseline platform security check "
                        + "(path-traversal / SSRF protection). Do not retry with a similar value; use a different "
                        + "argument or approach, or tell the user this specific request can't be carried out this way.");
    }
}
