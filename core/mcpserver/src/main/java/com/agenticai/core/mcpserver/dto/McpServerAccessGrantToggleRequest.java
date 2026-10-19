package com.agenticai.core.mcpserver.dto;

import jakarta.validation.constraints.NotNull;

public record McpServerAccessGrantToggleRequest(@NotNull Boolean enabled) {
}
