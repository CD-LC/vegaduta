package com.agenticai.core.agents.tools.code.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * {@code POST /v1/validate} response. A validation VERDICT is always HTTP 200 - {@code valid=false}
 * is a normal, expected answer, not a transport failure (that's {@code
 * ToolSandboxUnavailableException}). {@code errors} is populated only when {@code valid=false};
 * {@code warnings} may appear either way.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SandboxValidateResponse(
        boolean valid,
        String sourceSha256,
        List<SandboxValidationError> errors,
        List<String> warnings) {
    /** Never-null accessor - the service omits {@code errors} entirely on a valid verdict. */
    public List<SandboxValidationError> errorsOrEmpty() {
        return errors == null ? List.of() : errors;
    }

    /** Never-null accessor - see {@link #errorsOrEmpty()}. */
    public List<String> warningsOrEmpty() {
        return warnings == null ? List.of() : warnings;
    }

    /** One diagnostic. {@code line} is null when the sandbox could not attribute it to a line. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SandboxValidationError(Integer line, String message) {}
}
