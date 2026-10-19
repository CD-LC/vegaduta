package com.agenticai.core.sdlc.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

/**
 * @param templateId required - which E2B template to boot (e.g. E2B's stock {@code base} template, or a tenant's own custom template built via E2B's own tooling - this scaffold does not manage template creation).
 * @param timeoutSeconds sandbox time-to-live; null lets E2B apply its own default (15s - deliberately not defaulted higher here so a caller must think about it rather than silently getting a longer-lived, longer-billed sandbox).
 * @param envVars optional environment variables for the sandboxed process.
 * @param metadata optional free-form tags, queryable later via {@code GET /api/sdlc/sandbox}.
 */
public record CreateSandboxRequest(
    @NotBlank(message = "templateId is required") String templateId,
    Integer timeoutSeconds,
    Map<String, String> envVars,
    Map<String, String> metadata
) {
}
