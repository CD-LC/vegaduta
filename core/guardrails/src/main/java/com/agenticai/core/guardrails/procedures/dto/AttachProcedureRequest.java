package com.agenticai.core.guardrails.procedures.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Attach (or re-position) a procedure on an agent. */
public record AttachProcedureRequest(
    @NotNull UUID procedureId,
    @Min(0) Integer position
) {
}
