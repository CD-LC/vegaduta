package com.agenticai.core.providers.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/providers/local-openai/probe}: the model id as the server lists it. */
public record LocalOpenAiProbeRequest(
    @NotBlank(message = "model is required") @Size(max = 200) String model
) {
    public LocalOpenAiProbeRequest {
        if (model != null) {
            model = model.trim();
        }
    }
}
