package com.agenticai.core.agents.tools.code.dto;

import com.agenticai.core.agents.tools.code.CustomCodeToolLanguage;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * "Ask AI to generate the tool" request - the requirement is the only thing that MUST come from the
 * tenant; {@code template} is an optional canned starting point ("HTTP webhook call", "CSV
 * transform", ... or freeform) the console may offer next to it, and both feed straight into the
 * generation prompt as extra context, never parsed or validated as code themselves.
 *
 * <p>{@code toolName} is nullable and, unlike {@link CustomCodeToolRequest}, is NOT the tool's
 * persisted identity here - generation never persists anything (see {@code
 * CodeToolGenerationService}'s javadoc). It is only passed through into the prompt so the model can
 * pick sensible internal naming/messages consistent with what the tenant already typed in the
 * editor's Tool Name field, and into the sandbox validate call's {@code toolName} field, which the
 * sandbox uses only for its own logging.
 */
public record CodeToolGenerationRequest(
        @NotBlank String requirement,
        String template,
        @NotNull CustomCodeToolLanguage language,
        String toolName) {}
