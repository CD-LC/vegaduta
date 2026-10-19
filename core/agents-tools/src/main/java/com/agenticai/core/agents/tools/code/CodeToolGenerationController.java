package com.agenticai.core.agents.tools.code;

import com.agenticai.core.agents.tools.code.dto.CodeToolGenerationRequest;
import com.agenticai.core.agents.tools.code.dto.CodeToolGenerationResponse;
import com.agenticai.core.agents.tools.code.dto.CodeToolValidateRequest;
import com.agenticai.core.agents.tools.code.dto.SandboxValidateResponse;
import com.agenticai.core.agents.tools.code.generate.CodeToolGenerationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Ask AI to generate the tool" - the editor's AI-assisted drafting entry point, plus a standalone
 * validate-only endpoint reused by that same flow and by edge/on-device generation (see {@code
 * web/app/lib/edge/codegen.ts}).
 *
 * <p>Both routes are mutating in effect even though neither writes a row: {@code /generate} spends
 * a real LLM call and {@code /validate} spends a real sandbox compile, and both exist only to feed
 * the same privileged "author real code for this workspace" surface {@link
 * CustomCodeToolController} gates to {@code ROLE_tenant-admin}. That gate is declared centrally in
 * {@code SecurityConfig}, per this codebase's convention - and already covers this controller's
 * routes without any change there: {@code SecurityConfig} gates {@code POST /api/tools/code} and
 * {@code POST /api/tools/code/**} as one wildcard rule, which matches {@code
 * /api/tools/code/generate} and {@code /api/tools/code/validate} exactly as it matches {@code
 * /api/tools/code/{id}/test}.
 */
@RestController
@RequestMapping("/api/tools/code")
public class CodeToolGenerationController {

    private final CodeToolGenerationService generationService;

    public CodeToolGenerationController(CodeToolGenerationService generationService) {
        this.generationService = generationService;
    }

    @PostMapping("/generate")
    public CodeToolGenerationResponse generate(
            @Valid @RequestBody CodeToolGenerationRequest request) {
        return generationService.generate(request);
    }

    /**
     * Thin, LLM-free re-check of arbitrary source against the sandbox - exactly {@link
     * CodeToolGenerationService#validate}, i.e. exactly what {@code /generate} itself runs after
     * drafting, exposed standalone so a caller that already has candidate source (an edited AI
     * draft, an edge-generated draft) doesn't have to re-run generation to get a fresh verdict.
     */
    @PostMapping("/validate")
    public SandboxValidateResponse validate(@Valid @RequestBody CodeToolValidateRequest request) {
        return generationService.validate(
                request.language(), request.toolName(), request.sourceCode());
    }
}
