package com.agenticai.core.mcp;

import java.util.UUID;

public class McpServerRegistrationNotFoundException extends RuntimeException {

    public McpServerRegistrationNotFoundException(UUID id) {
        super("No MCP server registration found with id " + id);
    }
}
