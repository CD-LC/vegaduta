package com.agenticai.core.devices;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * State transitions on the paired-device row: {@link MobileDevice#touch} (heartbeat evidence),
 * {@link MobileDevice#revoke} (the only way a tenant invalidates a device token, since tokens
 * never rotate), and the capability CSV the client declares.
 */
class MobileDeviceTest {

    private static MobileDevice device() {
        return new MobileDevice("Asha's Pixel", DevicePlatform.ANDROID, "a1b2c3");
    }

    @Test
    void aNewDeviceStartsActiveWithNoLastSeenAndNoCapabilities() {
        MobileDevice device = device();

        assertThat(device.getDeviceName()).isEqualTo("Asha's Pixel");
        assertThat(device.getPlatform()).isEqualTo(DevicePlatform.ANDROID);
        assertThat(device.getDeviceToken()).isEqualTo("a1b2c3");
        assertThat(device.getCreatedAt()).isNotNull();
        assertThat(device.getLastSeenAt()).isNull();
        assertThat(device.isRevoked()).isFalse();
        assertThat(device.getCapabilities()).isNull();
        assertThat(device.getId()).isNull();
    }

    @Test
    void touchStampsTheLastSeenHeartbeat() {
        MobileDevice device = device();

        device.touch();

        assertThat(device.getLastSeenAt()).isNotNull();
    }

    @Test
    void revokeMarksTheDeviceSoAuthResolutionRejectsIt() {
        MobileDevice device = device();

        device.revoke();

        assertThat(device.isRevoked()).isTrue();
    }

    @Test
    void capabilitiesAreStoredVerbatim() {
        MobileDevice device = device();

        device.setCapabilities("camera.photo,location.get");

        assertThat(device.getCapabilities()).isEqualTo("camera.photo,location.get");

        device.setCapabilities(null);

        assertThat(device.getCapabilities()).isNull();
    }
}
