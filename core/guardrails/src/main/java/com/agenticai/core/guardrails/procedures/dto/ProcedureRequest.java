package com.agenticai.core.guardrails.procedures.dto;

import com.agenticai.core.guardrails.procedures.ProcedureGuardKind;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

/**
 * Create/update payload for a procedure: envelope fields plus the FULL
 * replacement rule list (same "PUT replaces the document" semantics
 * {@code GuardrailRuleRequest} follows - rules are few and ordered, so a
 * whole-list write is simpler and safer than per-rule deltas).
 */
public record ProcedureRequest(
    @NotBlank @Size(max = 255) String name,
    @Size(max = 4000) String description,
    @Valid List<RuleInput> rules
) {

    public record RuleInput(
        @NotBlank @Size(max = 4000) String instruction,
        ProcedureGuardKind guardKind,
        @Size(max = 255) String guardTool,
        Map<String, Object> guardConfig
    ) {
    }
}
