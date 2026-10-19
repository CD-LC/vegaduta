package com.agenticai.core.agents.tools.script.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Shared create/update request shape. {@code toolName} is only read by the controller's create path
 * (see {@code UserToolScriptController#create}) - it's immutable once a tool exists (an agent may
 * already be relying on the exact literal name an LLM calls), so the update path never looks at it.
 */
public record UserToolScriptRequest(
        String toolName,
        @NotBlank String displayName,
        String description,
        @NotBlank String expression,
        String inputSchemaJson) {}
