package com.agenticai.core.devices.dto;

import com.agenticai.core.devices.DevicePairingCode;
import java.time.Instant;

public record PairingCodeResponse(String code, Instant expiresAt) {

    public static PairingCodeResponse from(DevicePairingCode code) {
        return new PairingCodeResponse(code.getCode(), code.getExpiresAt());
    }
}
