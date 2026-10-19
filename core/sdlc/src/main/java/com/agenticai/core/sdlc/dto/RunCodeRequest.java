package com.agenticai.core.sdlc.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * @param code the source to run inside the tenant's code-execution E2B sandbox.
 * @param language one of {@code python}/{@code node}/{@code bash} - must match what the tenant's
 *     template (core/sdlc/e2b-template/) actually has an interpreter for; the template's own
 *     {@code runner.py} is the source of truth, not this API.
 * @param timeoutSeconds wall-clock budget for the whole call (sandbox boot + execution); null
 *     falls back to {@link com.agenticai.core.sdlc.E2bCodeExecutionClient}'s default.
 * @param files optional input files mounted in the run's working directory (data-analysis
 *     sandbox). Server-side only: never read from the REST body ({@code @JsonIgnore}).
 */
public record RunCodeRequest(
    @NotBlank(message = "code is required") String code,
    @NotBlank(message = "language is required") String language,
    Integer timeoutSeconds,
    @JsonIgnore List<SandboxFile> files
) {

    public RunCodeRequest(String code, String language, Integer timeoutSeconds) {
        this(code, language, timeoutSeconds, null);
    }
}
