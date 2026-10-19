package com.agenticai.core.agents.tools.code.dto;

import com.agenticai.core.agents.tools.code.CustomCodeToolLanguage;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * {@code POST /api/tools/code/validate} request - a thin, LLM-free re-check of arbitrary
 * (not-yet-saved) source against the sandbox, shaped like {@link CustomCodeToolRequest}'s
 * language/sourceCode pair rather than the sandbox's own {@code SandboxValidateRequest} wire
 * record, so this stays a stable public-API DTO independent of that internal wire contract.
 *
 * <p>Two callers: the generation flow's own after-the-fact verdict (which calls {@code
 * ToolSandboxClient} directly rather than round-tripping through HTTP) and, per the design,
 * edge/on-device generation - edge mode cannot compile or sandbox-execute locally, so it always
 * defers the authoritative check to this endpoint.
 */
public record CodeToolValidateRequest(
        @NotNull CustomCodeToolLanguage language, String toolName, @NotBlank String sourceCode) {}
