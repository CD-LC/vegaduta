package com.agenticai.core.agents.assist.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/assist/code-edit} - the Phone app studio's edit
 * turn. Unlike {@link AssistRequest} there is no form-field framing: the
 * caller owns the output contract (SEARCH/REPLACE edit blocks), so it sends
 * its own system prompt and the app + request as the user turn. Both are
 * size-capped: the studio sends one single-file app, never a project.
 */
public record AssistCodeEditRequest(
    @NotBlank @Size(max = 12_000) String system,
    @NotBlank @Size(max = 300_000) String prompt
) {}
