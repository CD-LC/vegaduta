package com.agenticai.core.devices.dto;

import com.agenticai.core.devices.DevicePlatform;
import com.agenticai.core.devices.MobileDevice;
import java.time.Instant;
import java.util.UUID;

public record DeviceResponse(
    UUID id,
    String deviceName,
    DevicePlatform platform,
    Instant createdAt,
    Instant lastSeenAt,
    boolean revoked,
    String capabilities
) {

    public static DeviceResponse from(MobileDevice device) {
        return new DeviceResponse(
            device.getId(), device.getDeviceName(), device.getPlatform(),
            device.getCreatedAt(), device.getLastSeenAt(), device.isRevoked(),
            device.getCapabilities()
        );
    }
}
