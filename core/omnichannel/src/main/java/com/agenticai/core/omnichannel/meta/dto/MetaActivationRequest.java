package com.agenticai.core.omnichannel.meta.dto;

import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotBlank;

public record MetaActivationRequest(
    @NotBlank String phoneNumberId,
    @NotBlank String wabaId,
    @NotBlank @Size(max = 4096) String accessToken,
    @NotBlank @Size(max = 4096) String appSecret
) {
}
