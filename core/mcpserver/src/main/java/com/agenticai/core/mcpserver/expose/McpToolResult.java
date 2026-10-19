package com.agenticai.core.mcpserver.expose;

import java.util.List;

/**
 * Outcome of one dynamic tool call, in the shape MCP's {@code tools/call} result wants: one or more
 * text content items plus the {@code isError} flag. A tool-level failure (bad arguments, budget,
 * unanswerable approval, ...) is a normal result with {@code isError = true} - NOT a JSON-RPC error -
 * so the calling model can read the message and react to it.
 */
public record McpToolResult(boolean error, List<String> texts) {

    public McpToolResult {
        texts = texts == null ? List.of() : List.copyOf(texts);
    }

    public static McpToolResult ok(String... texts) {
        return new McpToolResult(false, List.of(texts));
    }

    public static McpToolResult failure(String message) {
        return new McpToolResult(true, List.of(message));
    }
}
