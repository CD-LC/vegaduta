package com.agenticai.core.agents.tools.code.dto;

import com.agenticai.core.agents.tools.code.CustomCodeToolLanguage;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Shared create/update request shape. {@code toolName} is read only by the create path - it is
 * immutable once a tool exists (an agent's allowed-tool list and an LLM's learned call site both
 * key off the literal name), so the update path never looks at it. Same posture as {@code
 * UserToolScriptRequest}.
 *
 * <p>{@code timeoutMs}/{@code memoryMb} are nullable "what I'd like" hints, not commitments: the
 * service clamps them into the operator's configured ceilings and substitutes the defaults when
 * absent, so {@code @Positive} here only catches the obviously-nonsensical.
 */
public record CustomCodeToolRequest(
        String toolName,
        @NotBlank String displayName,
        String description,
        @NotNull CustomCodeToolLanguage language,
        @NotBlank String sourceCode,
        String inputSchemaJson,
        String outputDescription,
        @Positive Integer timeoutMs,
        @Positive Integer memoryMb) {}
