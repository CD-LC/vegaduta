package com.agenticai.core.admin.localauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.admin.localauth.LocalAuthServiceTest.MutableClock;
import com.agenticai.core.admin.localauth.LocalSessionService.Session;
import com.agenticai.core.security.localauth.LocalAuthSettings;
import com.agenticai.core.security.localauth.LocalJwtService;
import com.agenticai.core.security.localauth.LocalSigningKeyStore;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.oauth2.jwt.Jwt;

class LocalSessionServiceTest {

    @TempDir Path dir;

    MutableClock clock = new MutableClock();
    InMemoryLocalAccountStore store = new InMemoryLocalAccountStore();
    LocalPasswords passwords = new LocalPasswords();
    LocalAuthSettings settings;
    LocalJwtService jwt;
    LocalSessionService sessions;
    LocalAccount account;

    @BeforeEach
    void setUp() {
        settings = new LocalAuthSettings(null, null, dir, null, null, null, null, null, null, null, null);
        jwt = new LocalJwtService(settings, new LocalSigningKeyStore(dir, settings.keyRotationInterval(), settings.keyRetention(), clock), clock);
        sessions = new LocalSessionService(store, jwt, settings, clock);
        account = InMemoryLocalAccountStore.account("owner@example.test", "correct horse battery", passwords);
        store.insert(account);
    }

    @Test
    void refreshRotatesTheTokenAndKeepsTheLoginIdentity() {
        Session first = sessions.start(account, "pwd");

        Session second = sessions.refresh(first.refreshToken());

        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
        Jwt a = jwt.decoder().decode(first.accessToken());
        Jwt b = jwt.decoder().decode(second.accessToken());
        assertThat(b.getClaimAsString("sid")).isEqualTo(a.getClaimAsString("sid"));
        assertThat(b.getClaim("auth_time").toString())
                .as("a refresh does not move auth_time (the recent-authentication gate relies on it)")
                .isEqualTo(a.getClaim("auth_time").toString());
    }

    @Test
    void aRefreshTokenWorksOnlyOnce() {
        Session first = sessions.start(account, "pwd");
        sessions.refresh(first.refreshToken());
        clock.advance(Duration.ofMinutes(1));

        assertThatThrownBy(() -> sessions.refresh(first.refreshToken())).isInstanceOf(LocalAuthException.class);
    }

    @Test
    void reusingAnOldTokenAfterTheGraceWindowRevokesTheWholeLogin() {
        Session first = sessions.start(account, "pwd");
        Session second = sessions.refresh(first.refreshToken());
        clock.advance(Duration.ofMinutes(5));

        assertThatThrownBy(() -> sessions.refresh(first.refreshToken())).isInstanceOf(LocalAuthException.class);

        assertThatThrownBy(() -> sessions.refresh(second.refreshToken()))
                .as("the thief and the owner are both signed out")
                .isInstanceOf(LocalAuthException.class);
    }

    @Test
    void aDoubleSubmitInsideTheGraceWindowDoesNotSignTheOwnerOut() {
        Session first = sessions.start(account, "pwd");
        Session second = sessions.refresh(first.refreshToken());

        assertThatThrownBy(() -> sessions.refresh(first.refreshToken())).isInstanceOf(LocalAuthException.class);

        assertThat(sessions.refresh(second.refreshToken())).isNotNull();
    }

    @Test
    void logoutRevokesTheLoginAndIsIdempotent() {
        Session s = sessions.start(account, "pwd");

        sessions.logout(s.refreshToken());
        sessions.logout(s.refreshToken());
        sessions.logout("vdlr_unknown");
        sessions.logout(null);

        assertThatThrownBy(() -> sessions.refresh(s.refreshToken())).isInstanceOf(LocalAuthException.class);
    }

    @Test
    void anIdleRefreshTokenExpires() {
        Session s = sessions.start(account, "pwd");
        clock.advance(settings.refreshTokenTtl().plusMinutes(1));

        assertThatThrownBy(() -> sessions.refresh(s.refreshToken())).isInstanceOf(LocalAuthException.class);
    }

    @Test
    void refreshingNeverExtendsTheAbsoluteSessionLifetime() {
        Session s = sessions.start(account, "pwd");
        // Refresh every 20 days (inside the 30-day idle TTL) until just before the 90-day cap.
        for (int i = 0; i < 4; i++) {
            clock.advance(Duration.ofDays(20));
            s = sessions.refresh(s.refreshToken());
        }
        // Day 80: the token's expiry is clamped to the 90-day family limit, not now + 30 days.
        assertThat(s.refreshExpiresAt()).isBeforeOrEqualTo(clock.instant().plus(Duration.ofDays(10)).plusSeconds(1));

        clock.advance(Duration.ofDays(11));
        Session last = s;
        assertThatThrownBy(() -> sessions.refresh(last.refreshToken())).isInstanceOf(LocalAuthException.class);
    }

    @Test
    void aDisabledAccountCannotRefreshAndItsLoginIsRevoked() {
        Session s = sessions.start(account, "pwd");
        store.update(account.withEnabled(false));

        assertThatThrownBy(() -> sessions.refresh(s.refreshToken())).isInstanceOf(LocalAuthException.class);
        store.update(account.withEnabled(true));
        assertThatThrownBy(() -> sessions.refresh(s.refreshToken())).as("revoked, not merely paused").isInstanceOf(LocalAuthException.class);
    }

    @Test
    void aRoleChangeTakesEffectOnTheNextRefresh() {
        Session s = sessions.start(account, "pwd");
        store.update(account.withRoles(java.util.Set.of("agent-builder")));

        Session next = sessions.refresh(s.refreshToken());

        assertThat(jwt.decoder().decode(next.accessToken()).getClaim("realm_access").toString()).contains("agent-builder");
    }

    @Test
    void garbageTokensAreRefusedWithoutTouchingTheStore() {
        assertThatThrownBy(() -> sessions.refresh(null)).isInstanceOf(LocalAuthException.class);
        assertThatThrownBy(() -> sessions.refresh("eyJhbGciOi.jwt.notarefresh")).isInstanceOf(LocalAuthException.class);
        assertThatThrownBy(() -> sessions.refresh(LocalTokens.newRefreshToken())).isInstanceOf(LocalAuthException.class);
    }

    @Test
    void tokensHaveEnoughEntropyAndDistinctPrefixes() {
        assertThat(LocalTokens.newRefreshToken()).startsWith("vdlr_").hasSize(48);
        assertThat(LocalTokens.newSetupToken()).startsWith("vdls_");
        assertThat(LocalTokens.newRefreshToken()).isNotEqualTo(LocalTokens.newRefreshToken());
        assertThat(LocalTokens.hash("x")).hasSize(64).matches("[0-9a-f]+");
    }
}
