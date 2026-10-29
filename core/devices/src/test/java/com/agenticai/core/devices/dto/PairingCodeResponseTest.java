package com.agenticai.core.devices.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.devices.DevicePairingCode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

class PairingCodeResponseTest {

    @Test
    void fromExposesOnlyTheCodeAndItsExpiryNeverTheTenantSchema() {
        Instant expires = Instant.now().plus(10, ChronoUnit.MINUTES);
        DevicePairingCode code = new DevicePairingCode("654321", "tenant_acme", "user-1", expires);

        PairingCodeResponse response = PairingCodeResponse.from(code);

        assertThat(response.code()).isEqualTo("654321");
        assertThat(response.expiresAt()).isEqualTo(expires);
    }
}
