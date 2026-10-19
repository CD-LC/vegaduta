package com.agenticai.core.devices.dto;

import com.agenticai.core.devices.DevicePlatform;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ClaimRequest(
    // Public endpoint; the code is used verbatim in a Redis rate-limit key.
    @NotBlank @Size(max = 64) String code,
    @NotBlank @Size(max = 255) String deviceName,
    @NotNull DevicePlatform platform
) {
}
