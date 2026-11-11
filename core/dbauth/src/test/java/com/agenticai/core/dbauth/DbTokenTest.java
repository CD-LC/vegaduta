package com.agenticai.core.dbauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link DbToken}'s record guards and its deliberately token-free {@code toString} - the one
 * property that matters here is that logging a token object can never leak the credential.
 */
class DbTokenTest {

    @Test
    void holdsValueAndExpiry() {
        Instant expires = Instant.parse("2026-09-01T00:00:00Z");
        DbToken token = new DbToken("secret-token", expires);

        assertThat(token.value()).isEqualTo("secret-token");
        assertThat(token.expiresAt()).isEqualTo(expires);
    }

    @Test
    void rejectsNullBlankValueAndMissingExpiry() {
        Instant expires = Instant.parse("2026-09-01T00:00:00Z");

        assertThatThrownBy(() -> new DbToken(null, expires))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
        assertThatThrownBy(() -> new DbToken("   ", expires))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
        assertThatThrownBy(() -> new DbToken("tok", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expiry");
    }

    @Test
    void toStringPrintsTheExpiryNeverTheCredential() {
        Instant expires = Instant.parse("2026-09-01T12:34:56Z");
        DbToken token = new DbToken("super-secret-value", expires);

        assertThat(token.toString()).isEqualTo("DbToken[expiresAt=2026-09-01T12:34:56Z]");
        assertThat(token.toString()).doesNotContain("super-secret-value");
    }
}
