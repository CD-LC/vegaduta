package com.agenticai.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.security.ImpersonationGrantResolver.ViewAsSession;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link ViewAsSession#isUsableAt} is the 410-vs-proceed decision {@code ViewAsFilter} makes on
 * every view-as request: usable only while unended and unexpired.
 */
class ImpersonationGrantResolverTest {

    private static ViewAsSession session(Instant expiresAt, Instant endedAt) {
        return new ViewAsSession(
                UUID.randomUUID(),
                "admin-kc-1",
                "ops@vegaduta.ai",
                UUID.randomUUID(),
                "tenant_target",
                "Target Co",
                "target-kc-9",
                "user@target.co",
                List.of("tenant-admin"),
                expiresAt,
                endedAt);
    }

    @Test
    void aLiveSessionIsUsableUntilItsExpiryInstant() {
        Instant now = Instant.now();
        ViewAsSession session = session(now.plusSeconds(60), null);

        assertThat(session.isUsableAt(now)).isTrue();
        assertThat(session.isUsableAt(now.plusSeconds(59))).isTrue();
        assertThat(session.isUsableAt(now.plusSeconds(61))).isFalse();
    }

    @Test
    void anEndedSessionIsNeverUsableEvenBeforeItsExpiry() {
        Instant now = Instant.now();
        ViewAsSession session = session(now.plusSeconds(60), now.minusSeconds(10));

        assertThat(session.isUsableAt(now)).isFalse();
    }

    @Test
    void anExpiredSessionIsNotUsableAtOrAfterItsExpiry() {
        Instant now = Instant.now();
        ViewAsSession session = session(now, null);

        // now.isBefore(expiresAt) is false at the exact expiry instant.
        assertThat(session.isUsableAt(now)).isFalse();
        assertThat(session.isUsableAt(now.minusSeconds(1))).isTrue();
    }
}
