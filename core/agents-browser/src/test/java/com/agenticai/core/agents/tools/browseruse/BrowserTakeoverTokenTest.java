package com.agenticai.core.agents.tools.browseruse;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The signed takeover token: round trip, expiry, tampering, wrong key, and ids that try to smuggle
 * the field separator.
 */
class BrowserTakeoverTokenTest {

    private static final Instant NOW = Instant.parse("2026-10-05T10:00:00Z");

    @Test
    void aFreshTokenVerifiesAndCarriesItsClaims() {
        BrowserTakeoverToken tokens = new BrowserTakeoverToken("secret-1");
        UUID id = UUID.randomUUID();
        String token = tokens.issue(id, 3, "tenant-1", "user-1", NOW.plusSeconds(60));
        BrowserTakeoverToken.Claims claims = tokens.verify(token, NOW);
        assertThat(claims).isNotNull();
        assertThat(claims.takeoverId()).isEqualTo(id);
        assertThat(claims.epoch()).isEqualTo(3);
        assertThat(claims.tenantId()).isEqualTo("tenant-1");
        assertThat(claims.userId()).isEqualTo("user-1");
    }

    @Test
    void anExpiredTokenIsRejected() {
        BrowserTakeoverToken tokens = new BrowserTakeoverToken("secret-1");
        String token = tokens.issue(UUID.randomUUID(), 1, "t", "u", NOW.plusSeconds(60));
        assertThat(tokens.verify(token, NOW.plusSeconds(59))).isNotNull();
        assertThat(tokens.verify(token, NOW.plusSeconds(60))).isNull();
        assertThat(tokens.verify(token, NOW.plusSeconds(3600))).isNull();
    }

    @Test
    void aTamperedOrForeignTokenIsRejected() {
        BrowserTakeoverToken tokens = new BrowserTakeoverToken("secret-1");
        String token =
                tokens.issue(UUID.randomUUID(), 1, "tenant-1", "user-1", NOW.plusSeconds(60));
        String[] parts = token.split("\\.");
        // flip the signature
        assertThat(
                        tokens.verify(
                                parts[0] + "." + parts[1] + "." + parts[2].substring(1) + "A", NOW))
                .isNull();
        // swap the payload for another one, keep the old signature
        String other =
                tokens.issue(UUID.randomUUID(), 1, "tenant-2", "user-9", NOW.plusSeconds(60))
                        .split("\\.")[1];
        assertThat(tokens.verify(parts[0] + "." + other + "." + parts[2], NOW)).isNull();
        // another process's key
        assertThat(new BrowserTakeoverToken("secret-2").verify(token, NOW)).isNull();
        // garbage never throws
        assertThat(tokens.verify(null, NOW)).isNull();
        assertThat(tokens.verify("", NOW)).isNull();
        assertThat(tokens.verify("v1.x.y", NOW)).isNull();
        assertThat(tokens.verify("a".repeat(5_000), NOW)).isNull();
    }

    @Test
    void aSeparatorInsideATenantOrUserIdCannotShiftTheFields() {
        BrowserTakeoverToken tokens = new BrowserTakeoverToken("secret-1");
        String token =
                tokens.issue(UUID.randomUUID(), 1, "ten|ant", "us|er%7C", NOW.plusSeconds(60));
        BrowserTakeoverToken.Claims claims = tokens.verify(token, NOW);
        assertThat(claims).isNotNull();
        assertThat(claims.tenantId()).isEqualTo("ten|ant");
        assertThat(claims.userId()).isEqualTo("us|er%7C");
    }

    @Test
    void withoutAConfiguredSecretEachInstanceGetsItsOwnRandomKey() {
        BrowserTakeoverToken a = new BrowserTakeoverToken("");
        BrowserTakeoverToken b = new BrowserTakeoverToken(null);
        String token = a.issue(UUID.randomUUID(), 1, "t", "u", NOW.plusSeconds(60));
        assertThat(a.verify(token, NOW)).isNotNull();
        assertThat(b.verify(token, NOW)).isNull();
    }
}
