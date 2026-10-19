package com.agenticai.core.agents.tools.mcpapps;

/**
 * A refusal from the MCP Apps layer. {@code code} is stable and safe to show; the message never
 * carries server-controlled text.
 */
public class McpAppsException extends RuntimeException {

    public enum Code {
        NOT_FOUND,
        REFUSED,
        UNAVAILABLE,
        TOO_LARGE,
        BAD_REQUEST,
        RATE_LIMITED
    }

    private final Code code;

    public McpAppsException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
