package com.agenticai.core.devices.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.devices.DevicePlatform;
import com.agenticai.core.devices.MobileDevice;
import org.junit.jupiter.api.Test;

class DeviceResponseTest {

    @Test
    void fromMapsEveryDeviceField() {
        MobileDevice device = new MobileDevice("Asha's Pixel", DevicePlatform.ANDROID, "tok-1");
        device.setCapabilities("camera.photo");
        device.touch();

        DeviceResponse response = DeviceResponse.from(device);

        assertThat(response.id()).isEqualTo(device.getId());
        assertThat(response.deviceName()).isEqualTo("Asha's Pixel");
        assertThat(response.platform()).isEqualTo(DevicePlatform.ANDROID);
        assertThat(response.createdAt()).isEqualTo(device.getCreatedAt());
        assertThat(response.lastSeenAt()).isEqualTo(device.getLastSeenAt());
        assertThat(response.revoked()).isFalse();
        assertThat(response.capabilities()).isEqualTo("camera.photo");
    }

    @Test
    void fromReflectsARevokedDevice() {
        MobileDevice device = new MobileDevice("Old Phone", DevicePlatform.DESKTOP, "tok-2");
        device.revoke();

        DeviceResponse response = DeviceResponse.from(device);

        assertThat(response.revoked()).isTrue();
        assertThat(response.lastSeenAt()).isNull();
        assertThat(response.capabilities()).isNull();
    }
}
