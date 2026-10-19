package com.agenticai.core.devices.dto;

import com.agenticai.core.devices.DeviceCommandStatus;
import jakarta.validation.constraints.NotNull;

public record CommandResultRequest(@NotNull DeviceCommandStatus status, String result) {
}
