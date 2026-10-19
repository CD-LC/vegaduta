package com.agenticai.core.devices.dto;

import jakarta.validation.constraints.NotBlank;

public record EnqueueCommandRequest(@NotBlank String commandType, String payload) {
}
