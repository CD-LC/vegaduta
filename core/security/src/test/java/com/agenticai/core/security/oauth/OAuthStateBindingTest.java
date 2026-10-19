package com.agenticai.core.security.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;

/** SEC-018: OAuth connect state must be single-use, short-lived, and bound to the session that started it. */
class OAuthStateBindingTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-30T10:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };

    private static String cookieValue(ResponseCookie c) {
        return c.getValue();
    }

    @Test
    void validFlowWithMatchingCookieIsAccepted() {
        OAuthStateBinding b = new OAuthStateBinding(true, clock);
        OAuthStateBinding.Issued issued = b.issue("google", "tenant_a", "user-1", "user-1");

        OAuthStateBinding.Binding bound =
            b.consume("google", issued.nonce(), "tenant_a", "user-1", cookieValue(issued.cookie()));

        assertThat(bound.tenant()).isEqualTo("tenant_a");
        assertThat(bound.subjectKey()).isEqualTo("user-1");
    }

    @Test
    void cookieIsHttpOnlySecureLaxAndScopedToTheCallback() {
        ResponseCookie c = new OAuthStateBinding(false, clock).issue("x", "tenant_a", null, "admin").cookie();
        assertThat(c.isHttpOnly()).isTrue();
        assertThat(c.isSecure()).isTrue();
        assertThat(c.getSameSite()).isEqualTo("Lax");
        assertThat(c.getPath()).isEqualTo("/oauth/x/callback");
        assertThat(c.getMaxAge()).isEqualTo(Duration.ofMinutes(10));
        assertThat(c.getName()).isEqualTo("vd_oauth_x");
    }

    @Test
    void replayedStateIsRejected() {
        OAuthStateBinding b = new OAuthStateBinding(false, clock);
        OAuthStateBinding.Issued issued = b.issue("google", "tenant_a", null, "admin");
        b.consume("google", issued.nonce(), "tenant_a", null, cookieValue(issued.cookie()));

        assertThatThrownBy(() -> b.consume("google", issued.nonce(), "tenant_a", null, cookieValue(issued.cookie())))
            .isInstanceOf(OAuthStateBindingException.class);
    }

    @Test
    void stateWithoutMatchingCookieIsRejected() {
        OAuthStateBinding b = new OAuthStateBinding(false, clock);
        OAuthStateBinding.Issued attackers = b.issue("google", "tenant_attacker", null, "attacker");
        OAuthStateBinding.Issued victims = b.issue("google", "tenant_victim", null, "victim");

        // Victim's browser carries its OWN binding cookie; the attacker's state must not be accepted with it.
        assertThatThrownBy(() -> b.consume("google", attackers.nonce(), "tenant_attacker", null,
            cookieValue(victims.cookie()))).isInstanceOf(OAuthStateBindingException.class);
    }

    @Test
    void missingCookieIsRejectedWhenBrowserBindingIsRequired() {
        OAuthStateBinding b = new OAuthStateBinding(true, clock);
        OAuthStateBinding.Issued issued = b.issue("google", "tenant_a", null, "admin");

        assertThatThrownBy(() -> b.consume("google", issued.nonce(), "tenant_a", null, null))
            .isInstanceOf(OAuthStateBindingException.class);
    }

    @Test
    void missingCookieIsToleratedOnlyWhileBrowserBindingIsOff() {
        OAuthStateBinding b = new OAuthStateBinding(false, clock);
        OAuthStateBinding.Issued issued = b.issue("google", "tenant_a", null, "admin");

        assertThat(b.consume("google", issued.nonce(), "tenant_a", null, null).tenant()).isEqualTo("tenant_a");
    }

    @Test
    void expiredStateIsRejected() {
        OAuthStateBinding b = new OAuthStateBinding(false, clock);
        OAuthStateBinding.Issued issued = b.issue("google", "tenant_a", null, "admin");
        now.set(now.get().plus(Duration.ofMinutes(10)).plusSeconds(1));

        assertThatThrownBy(() -> b.consume("google", issued.nonce(), "tenant_a", null, cookieValue(issued.cookie())))
            .isInstanceOf(OAuthStateBindingException.class);
    }

    @Test
    void stateBoundToTenantACannotLandTokensInAnotherTenantOrUserOrProvider() {
        OAuthStateBinding b = new OAuthStateBinding(false, clock);
        OAuthStateBinding.Issued a = b.issue("google", "tenant_a", "user-a", "user-a");
        assertThatThrownBy(() -> b.consume("google", a.nonce(), "tenant_b", "user-a", cookieValue(a.cookie())))
            .isInstanceOf(OAuthStateBindingException.class);

        OAuthStateBinding.Issued a2 = b.issue("google", "tenant_a", "user-a", "user-a");
        assertThatThrownBy(() -> b.consume("google", a2.nonce(), "tenant_a", "user-b", cookieValue(a2.cookie())))
            .isInstanceOf(OAuthStateBindingException.class);

        OAuthStateBinding.Issued a3 = b.issue("google", "tenant_a", null, "admin");
        assertThatThrownBy(() -> b.consume("x", a3.nonce(), "tenant_a", null, cookieValue(a3.cookie())))
            .isInstanceOf(OAuthStateBindingException.class);
    }

    @Test
    void stateWithNoNonceOrAnUnknownNonceIsRejected() {
        OAuthStateBinding b = new OAuthStateBinding(false, clock);
        assertThatThrownBy(() -> b.consume("google", null, "tenant_a", null, null))
            .isInstanceOf(OAuthStateBindingException.class);
        assertThatThrownBy(() -> b.consume("google", "made-up", "tenant_a", null, null))
            .isInstanceOf(OAuthStateBindingException.class);
    }

    @Test
    void noncesAre256BitAndUnique() {
        OAuthStateBinding b = new OAuthStateBinding(false, clock);
        String n1 = b.issue("google", "t", null, "u").nonce();
        String n2 = b.issue("google", "t", null, "u").nonce();
        assertThat(n1).hasSize(43).isNotEqualTo(n2).doesNotContain("|");
        assertThat(b.pendingCount()).isEqualTo(2);
    }
}
