package com.agenticai.core.agents.tools.stdiosandbox;

/**
 * The sandbox could not (or must not) launch this server: the mechanism is unavailable and {@code
 * app.mcp.stdio.sandbox-required=true}, or an input (path, env name) could not be passed safely.
 * Always a refusal - never a silent fall back to an unsandboxed spawn. The message is user-safe: it
 * names the problem and the fix, never a path or secret.
 */
public class StdioSandboxException extends RuntimeException {

    public StdioSandboxException(String message) {
        super(message);
    }
}
