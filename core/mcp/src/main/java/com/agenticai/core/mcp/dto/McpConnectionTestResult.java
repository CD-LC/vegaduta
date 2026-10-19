package com.agenticai.core.mcp.dto;

import com.agenticai.core.mcp.McpConnectionPhase;
import java.util.List;

/**
 * Result of a real, bounded connection test against a dynamic MCP
 * registration - the response body of {@code POST
 * /api/mcp/registrations/{id}/test}. Contract frozen for the web agent
 * (2026-07-25), extended 2026-07-26 (MCP-SELF-HEAL): {@code { "ok": bool,
 * "phase": "SPAWN|HTTP|HANDSHAKE|INIT_TIMEOUT|OK", "detail": string, "hint":
 * string|null, "toolCount": number|null, "suggestedFix": {"command": string,
 * "args": string[], "reason": string}|null }}.
 *
 * <p>{@code suggestedFix} is new (2026-07-26) and, like {@code hint}, is
 * present only on some FAILED results - specifically a STDIO failure whose
 * stderr yielded a corrected command/args that already passed {@link
 * com.agenticai.core.mcp.McpStdioCommandGuard} (see {@code
 * McpStdioSelfHealer} in core/agents, which builds it: a fast regex match
 * for known-shape errors like uv's "executable not provided by package"
 * message, falling back to one capped LLM-assisted attempt for anything
 * else). It is a PROPOSAL only - nothing on this path ever mutates the
 * registration - so the web UI is expected to render it as an "Apply
 * suggested fix?" confirmation that, on accept, PUTs the registration's
 * {@code command}/{@code args} to {@code suggestedFix.command}/{@code
 * suggestedFix.args} through the normal update endpoint (which re-enforces
 * the same guard).
 */
public record McpConnectionTestResult(
    boolean ok,
    McpConnectionPhase phase,
    String detail,
    String hint,
    Integer toolCount,
    SuggestedFix suggestedFix
) {
    /**
     * A machine-actionable correction for a failed STDIO registration -
     * {@code command}/{@code args} are the exact replacement values for the
     * registration's own fields, already validated against {@link
     * com.agenticai.core.mcp.McpStdioCommandGuard} by the caller that built
     * this record (never a raw, unvalidated proposal). {@code reason} is a
     * short, human-readable explanation for the confirmation prompt.
     */
    public record SuggestedFix(String command, List<String> args, String reason) {}

    public static McpConnectionTestResult success(int toolCount) {
        return new McpConnectionTestResult(
            true, McpConnectionPhase.OK,
            "Connected - server advertised " + toolCount + " tool(s)", null, toolCount, null);
    }

    public static McpConnectionTestResult failure(McpConnectionPhase phase, String detail, String hint) {
        return new McpConnectionTestResult(false, phase, detail, hint, null, null);
    }

    /** Same as {@link #failure} plus a validated, one-click-appliable correction - see {@link SuggestedFix}. */
    public static McpConnectionTestResult failureWithSuggestedFix(
        McpConnectionPhase phase, String detail, String hint, SuggestedFix suggestedFix
    ) {
        return new McpConnectionTestResult(false, phase, detail, hint, null, suggestedFix);
    }
}
