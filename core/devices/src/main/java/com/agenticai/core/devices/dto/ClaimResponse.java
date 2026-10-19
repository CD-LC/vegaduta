package com.agenticai.core.devices.dto;

import java.util.UUID;

public record ClaimResponse(UUID deviceId, String deviceToken) {
}
