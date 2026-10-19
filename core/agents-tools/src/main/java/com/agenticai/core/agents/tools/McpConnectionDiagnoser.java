package com.agenticai.core.agents.tools;

import com.agenticai.core.agents.tools.RemoteMcpPreProbe.ProbeResult;
import com.agenticai.core.mcp.McpConnectionPhase;
import com.agenticai.core.mcp.McpServerType;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

/**
 * Pure, deterministic mapper from raw connection evidence (the thrown failure, the remote pre-probe
 * result, the stdio child's stderr tail) to a structured, ACTIONABLE diagnosis {@code {phase,
 * detail, hint}} - the Connection Doctor's brain (MCP-DOCTOR, 2026-07-25). Kept free of any SDK/
 * Spring/IO dependency specifically so the status→hint table is directly unit-testable ({@code
 * McpConnectionDiagnoserTest}).
 */
final class McpConnectionDiagnoser {

    record Diagnosis(McpConnectionPhase phase, String detail, String hint) {}

    private McpConnectionDiagnoser() {}

    /**
     * What a remote pre-probe alone can conclude - empty when the probe saw nothing wrong (or never
     * ran), in which case the handshake failure itself drives the diagnosis via {@link
     * #diagnoseRemoteFailure}.
     */
    static Optional<Diagnosis> diagnoseProbe(ProbeResult probe, McpServerType type) {
        if (probe == null || !probe.performed()) {
            return Optional.empty();
        }
        switch (probe.failureKind()) {
            case UNKNOWN_HOST:
                return Optional.of(
                        new Diagnosis(
                                McpConnectionPhase.HTTP,
                                "The server's hostname does not resolve.",
                                "Check the URL for typos - the domain name could not be found in DNS."));
            case CONNECT_REFUSED:
                return Optional.of(
                        new Diagnosis(
                                McpConnectionPhase.HTTP,
                                "Server unreachable from the platform (connection refused or no route).",
                                "Verify the server is running and publicly reachable - the platform cannot reach private/internal addresses."));
            case TIMEOUT:
                return Optional.of(
                        new Diagnosis(
                                McpConnectionPhase.HTTP,
                                "Server did not answer HTTP within "
                                        + RemoteMcpPreProbe.PROBE_TIMEOUT.toSeconds()
                                        + "s.",
                                "Server unreachable from the platform, or extremely slow - verify the URL and that the host is up."));
            case TLS:
                return Optional.of(
                        new Diagnosis(
                                McpConnectionPhase.HTTP,
                                "TLS handshake with the server failed"
                                        + (probe.detail() == null
                                                ? "."
                                                : " (" + probe.detail() + ")."),
                                "Check the URL's certificate - self-signed or expired certificates are not accepted."));
            case OTHER:
                return Optional.of(
                        new Diagnosis(
                                McpConnectionPhase.HTTP,
                                "HTTP probe failed: " + probe.detail(),
                                null));
            case NONE:
            default:
                break;
        }
        Integer status = probe.statusCode();
        if (status == null) {
            return Optional.empty();
        }
        if (status == 401 || status == 403) {
            return Optional.of(
                    new Diagnosis(
                            McpConnectionPhase.HTTP,
                            "The server rejected the request with HTTP "
                                    + status
                                    + " (authentication/authorization).",
                            "This server requires credentials - add e.g. Authorization = Bearer <token> (or the header it expects) to its env entries."));
        }
        if (status == 404 || status == 405) {
            // 405 on a STREAMABLE_HTTP POST probe is a real signal (the probe
            // already speaks the type's own verb); on SSE it usually means the
            // endpoint isn't a GET/SSE endpoint - both map to the same pair of
            // likely causes.
            String typeHint =
                    type == McpServerType.SSE
                            ? "URL looks wrong or this endpoint isn't MCP. If the server actually speaks streamable HTTP, switch the type to STREAMABLE_HTTP."
                            : "URL looks wrong or this endpoint isn't MCP - check the path (many servers serve MCP under /mcp or /sse).";
            return Optional.of(
                    new Diagnosis(
                            McpConnectionPhase.HTTP,
                            "The server answered HTTP " + status + " for the MCP endpoint.",
                            typeHint));
        }
        if (status >= 500) {
            return Optional.of(
                    new Diagnosis(
                            McpConnectionPhase.HTTP,
                            "The server answered HTTP " + status + " (server-side error).",
                            "The remote server is up but failing internally - check its logs or status page."));
        }
        if (status >= 200
                && status < 300
                && type == McpServerType.STREAMABLE_HTTP
                && probe.contentType() != null
                && probe.contentType().contains("text/event-stream")) {
            // A compliant streamable-HTTP server MAY answer POSTs with an SSE
            // stream, so 2xx+event-stream alone is not proof of a type
            // mismatch - but this mapper is only ever consulted when the real
            // handshake ALSO failed (a successful connect never gets
            // diagnosed), and in that combination "actually an SSE server" is
            // by far the most likely cause.
            return Optional.of(
                    new Diagnosis(
                            McpConnectionPhase.HTTP,
                            "The server answered with a server-sent-events stream.",
                            "This looks like an SSE server - try switching the registration's type to SSE."));
        }
        return Optional.empty();
    }

    /**
     * Full remote-failure diagnosis: probe evidence first (it names the actionable cause), then the
     * handshake failure's own shape.
     */
    static Diagnosis diagnoseRemoteFailure(
            Throwable failure, ProbeResult probe, McpServerType type) {
        Optional<Diagnosis> fromProbe = diagnoseProbe(probe, type);
        if (fromProbe.isPresent()) {
            return fromProbe.get();
        }
        String message = describe(failure);
        if (isTimeout(failure)) {
            String typeSwitchHint =
                    type == McpServerType.STREAMABLE_HTTP
                            ? "The server accepted HTTP but never completed the MCP handshake - if it is an SSE-only server, switch the type to SSE; otherwise verify this URL is really an MCP endpoint and that required auth headers are set in env."
                            : "The server accepted HTTP but never completed the MCP handshake - if it only speaks streamable HTTP, switch the type to STREAMABLE_HTTP; otherwise verify this URL is really an MCP endpoint and that required auth headers are set in env.";
            return new Diagnosis(
                    McpConnectionPhase.INIT_TIMEOUT,
                    "MCP initialization timed out (" + message + ").",
                    typeSwitchHint);
        }
        return new Diagnosis(
                McpConnectionPhase.HANDSHAKE,
                "MCP handshake failed: " + message,
                "The endpoint answered HTTP but did not complete the MCP protocol exchange - verify the URL, the transport type (SSE vs STREAMABLE_HTTP), and any required auth headers in env.");
    }

    /**
     * Compatibility overload for every caller (mainly tests) that has no catalog/registry
     * provenance to check missing env vars against - same as calling the 3-arg overload with an
     * empty {@code missingRequiredEnvVars}.
     */
    static Diagnosis diagnoseStdioFailure(Throwable failure, List<String> stderrTail) {
        return diagnoseStdioFailure(failure, stderrTail, List.of());
    }

    /**
     * Full stdio-failure diagnosis: spawn-shape errors first, then the stderr tail (where the
     * actionable cause usually is), then timeout vs generic.
     *
     * @param missingRequiredEnvVars required env var name(s) this registration's stored env is
     *     missing, per its catalog/registry provenance - empty when that provenance is unknown (a
     *     hand-typed custom command) or fully satisfied. See {@code
     *     DynamicMcpConnectionManager#missingRequiredEnvVars}, the only caller that ever computes a
     *     non-empty list. Named specifically (2026-08-05) in the INIT_TIMEOUT hint instead of this
     *     class's old generic "may be waiting for missing configuration (env vars)" - a timed-out
     *     handshake with a known-missing required credential is the single most common real cause
     *     of that failure shape, and a tenant should not have to guess which variable.
     */
    static Diagnosis diagnoseStdioFailure(
            Throwable failure, List<String> stderrTail, List<String> missingRequiredEnvVars) {
        String message = describe(failure);
        String stderrBlock =
                stderrTail == null || stderrTail.isEmpty()
                        ? ""
                        : "\nServer stderr (last "
                                + stderrTail.size()
                                + " line(s)):\n"
                                + String.join("\n", stderrTail);
        if (isSpawnFailure(failure)) {
            return new Diagnosis(
                    McpConnectionPhase.SPAWN,
                    "The server process could not be started: " + message + stderrBlock,
                    "Check the command and args - the launcher or package may not exist. The stderr output above usually names the exact fix.");
        }
        if (isTimeout(failure)) {
            String namedVarSentence = missingVarSentence(missingRequiredEnvVars);
            String hint;
            if (namedVarSentence != null) {
                hint =
                        stderrBlock.isEmpty()
                                ? namedVarSentence
                                : namedVarSentence
                                        + " The stderr output above may also help confirm the cause.";
            } else {
                hint =
                        stderrBlock.isEmpty()
                                ? "The process started but never completed the MCP handshake - it may not be an MCP server, or it may be waiting for missing configuration (env vars)."
                                : "The process started but never completed the MCP handshake - the stderr output above usually names the exact cause (wrong binary/entrypoint, missing env var, crash on boot).";
            }
            return new Diagnosis(
                    McpConnectionPhase.INIT_TIMEOUT,
                    "MCP initialization timed out (" + message + ")." + stderrBlock,
                    hint);
        }
        return new Diagnosis(
                McpConnectionPhase.HANDSHAKE,
                "MCP handshake failed: " + message + stderrBlock,
                stderrBlock.isEmpty()
                        ? null
                        : "The stderr output above usually names the exact cause.");
    }

    /**
     * Null when nothing is known missing; otherwise "This server requires FOO, BAR, which haven't
     * been configured. Add them in the server's credentials." (singular-correct for exactly one
     * var).
     */
    private static String missingVarSentence(List<String> missingRequiredEnvVars) {
        if (missingRequiredEnvVars == null || missingRequiredEnvVars.isEmpty()) {
            return null;
        }
        boolean singular = missingRequiredEnvVars.size() == 1;
        return "This server requires "
                + String.join(", ", missingRequiredEnvVars)
                + ", which "
                + (singular ? "hasn't" : "haven't")
                + " been configured. Add "
                + (singular ? "it" : "them")
                + " in the server's credentials.";
    }

    /**
     * SDK 0.18.2's spawn path throws RuntimeException wrapping IOException("Cannot run program
     * ...") when the binary doesn't exist/can't exec.
     */
    private static boolean isSpawnFailure(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            String m = t.getMessage();
            if (t instanceof java.io.IOException
                    && m != null
                    && (m.contains("Cannot run program")
                            || m.contains("error=2")
                            || m.contains("error=13"))) {
                return true;
            }
            if (m != null && m.contains("Process input or output stream is null")) {
                return true; // SDK's "spawned but immediately dead" shape
            }
        }
        return false;
    }

    /**
     * Reactor buries the real TimeoutException under "Client failed to initialize by explicit API
     * call" - walk the cause chain AND the message text.
     */
    private static boolean isTimeout(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof TimeoutException) {
                return true;
            }
            String m = t.getMessage();
            if (m != null
                    && (m.contains("Timeout on blocking read")
                            || m.contains("Did not observe any item")
                            || m.contains("Client failed to initialize"))) {
                return true;
            }
        }
        return false;
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return (message == null || message.isBlank()) ? t.getClass().getSimpleName() : message;
    }
}
