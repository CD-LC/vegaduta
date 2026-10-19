package com.agenticai.core.mcpserver;

import java.util.UUID;

public class McpServerAccessGrantNotFoundException extends RuntimeException {

    public McpServerAccessGrantNotFoundException(UUID id) {
        super("No MCP server access grant found with id " + id);
    }
}
