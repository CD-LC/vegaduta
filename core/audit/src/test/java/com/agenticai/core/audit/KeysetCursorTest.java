package com.agenticai.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class KeysetCursorTest {

    @Test
    void roundTripsMicrosecondTimestampsAndIds() {
        KeysetCursor c =
                new KeysetCursor(Instant.parse("2026-10-16T10:00:00.123456Z"), UUID.randomUUID());
        String token = c.encode();
        assertThat(token).doesNotContain("=", "+", "/");
        assertThat(KeysetCursor.decode(token)).isEqualTo(c);
    }

    @Test
    void preEpochTimestampsRoundTrip() {
        KeysetCursor c = new KeysetCursor(Instant.parse("1969-12-31T23:59:59.999999Z"), UUID.randomUUID());
        assertThat(KeysetCursor.decode(c.encode())).isEqualTo(c);
    }

    @Test
    void foreignTokensAreRejectedAsBadRequests() {
        assertThatThrownBy(() -> KeysetCursor.decode("not a cursor"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KeysetCursor.decode("djI6MTp4"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
