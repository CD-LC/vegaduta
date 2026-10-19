package com.agenticai.core.agents.decision.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Same single-field shape {@code SarvamCredentialActivationRequest} carries. */
public record JevCredentialActivationRequest(
    @NotBlank(message = "apiKey is required")
    @Size(max = 512, message = "apiKey is implausibly long")
    @Size(max = 4096) String apiKey
) {
}
