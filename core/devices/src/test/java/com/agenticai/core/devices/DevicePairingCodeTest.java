package com.agenticai.core.devices;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

/**
 * The pairing code's whole contract is {@link DevicePairingCode#isUsable}: a code is a
 * single-use credential that lives for {@code DevicePairingService}'s ten-minute TTL, so both
 * edges of "not consumed AND not expired" are worth pinning.
 */
class DevicePairingCodeTest {

    private static DevicePairingCode code(Instant expiresAt) {
        return new DevicePairingCode("123456", "tenant_acme", "user-1", expiresAt);
    }

    @Test
    void aFreshCodeIsUnconsumedAndUsable() {
        Instant expires = Instant.now().plus(10, ChronoUnit.MINUTES);
        DevicePairingCode code = code(expires);

        assertThat(code.getCode()).isEqualTo("123456");
        assertThat(code.getTenantSchema()).isEqualTo("tenant_acme");
        assertThat(code.getCreatedBy()).isEqualTo("user-1");
        assertThat(code.getExpiresAt()).isEqualTo(expires);
        assertThat(code.getCreatedAt()).isNotNull();
        assertThat(code.isConsumed()).isFalse();
        // The id is database-generated - null until JPA persists the row.
        assertThat(code.getId()).isNull();
        assertThat(code.isUsable(Instant.now())).isTrue();
    }

    @Test
    void aCodeStopsBeingUsableExactlyAtItsExpiryInstant() {
        Instant expires = Instant.now().plus(10, ChronoUnit.MINUTES);
        DevicePairingCode code = code(expires);

        assertThat(code.isUsable(expires.minus(1, ChronoUnit.MILLIS))).isTrue();
        // isUsable is now.isBefore(expiresAt) - the expiry instant itself is already too late.
        assertThat(code.isUsable(expires)).isFalse();
        assertThat(code.isUsable(expires.plus(1, ChronoUnit.MINUTES))).isFalse();
    }

    @Test
    void consumeMakesEvenAnUnexpiredCodeUnusable() {
        DevicePairingCode code = code(Instant.now().plus(10, ChronoUnit.MINUTES));

        code.consume();

        assertThat(code.isConsumed()).isTrue();
        assertThat(code.isUsable(Instant.now())).isFalse();
    }
}
