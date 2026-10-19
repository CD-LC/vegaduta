package com.agenticai.core.agents.tools.code.dto;

import com.agenticai.core.agents.tools.code.CustomCodeToolLanguage;
import java.util.List;

/**
 * "Ask AI to generate the tool" result - deliberately {@link SandboxValidateResponse} flattened
 * with the generated {@code sourceCode} and the {@code language} the caller asked for, rather than
 * a new error shape: the console's existing REJECTED-status red-panel rendering ({@code
 * errors}/{@code warnings}) already knows how to read exactly this.
 *
 * <p>{@code valid=false} is a normal, expected result here, same posture as {@link
 * SandboxValidateResponse} itself - the model's first attempt not compiling is not this endpoint
 * failing, it is this endpoint doing its job and telling the tenant so before they save it. Nothing
 * is ever persisted by this response; saving stays the tenant's own, separate action via {@code
 * POST /api/tools/code}.
 */
public record CodeToolGenerationResponse(
        String sourceCode,
        boolean valid,
        List<SandboxValidateResponse.SandboxValidationError> errors,
        List<String> warnings,
        String sourceSha256,
        CustomCodeToolLanguage language) {
    public static CodeToolGenerationResponse from(
            String sourceCode, CustomCodeToolLanguage language, SandboxValidateResponse verdict) {
        return new CodeToolGenerationResponse(
                sourceCode,
                verdict.valid(),
                verdict.errorsOrEmpty(),
                verdict.warningsOrEmpty(),
                verdict.sourceSha256(),
                language);
    }
}
