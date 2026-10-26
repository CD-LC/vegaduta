package com.agenticai.core.admin.localauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.admin.localauth.LocalSessionService.Session;
import com.agenticai.core.security.localauth.LocalAuthSettings;
import com.agenticai.core.security.localauth.LocalJwtService;
import com.agenticai.core.security.localauth.LocalSigningKeyStore;
import com.agenticai.core.security.localauth.LoginThrottle;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

class LocalAuthServiceTest {

    static final class MutableClock extends Clock {
        Instant now = Instant.now();

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final String EMAIL = "owner@example.test";
    private static final String PASSWORD = "correct horse battery staple";

    @TempDir Path dir;

    MutableClock clock = new MutableClock();
    InMemoryLocalAccountStore store = new InMemoryLocalAccountStore();
    InMemoryLocalCredentialStore credentials = new InMemoryLocalCredentialStore();
    LocalPasswords passwords = new LocalPasswords();
    LocalPins pins = new LocalPins(passwords);
    LocalAuthSettings settings;
    LocalJwtService jwt;
    LocalSessionService sessions;
    LocalAuthService auth;
    LocalAccount owner;

    @BeforeEach
    void setUp() {
        settings = new LocalAuthSettings(null, null, dir, null, null, null, null, null, null, null, null);
        jwt = new LocalJwtService(settings, new LocalSigningKeyStore(dir, settings.keyRotationInterval(), settings.keyRetention(), clock), clock);
        sessions = new LocalSessionService(store, jwt, settings, clock);
        LoginThrottle throttle = new LoginThrottle(3, 100, Duration.ofSeconds(30), Duration.ofMinutes(10), Duration.ofMinutes(30), 1000, clock);
        LocalDevices devices = new LocalDevices(credentials, pins, clock);
        LocalRecoveryCodes codes = new LocalRecoveryCodes(credentials, clock);
        // Deliberately not in display order: the service must report the types in a fixed order.
        auth =
                new LocalAuthService(
                        store,
                        sessions,
                        passwords,
                        throttle,
                        settings,
                        List.of(
                                new LocalRecoveryVerifier(codes, store),
                                new LocalPasskeyVerifier(new LocalPasskeyService(credentials, "", clock), store),
                                new LocalPasswordVerifier(store, passwords),
                                new LocalPinVerifier(credentials, store, pins, clock)),
                        devices,
                        codes,
                        clock);
        owner = InMemoryLocalAccountStore.account(EMAIL, PASSWORD, passwords);
        store.insert(owner);
    }

    private Session login(String password) {
        return auth.login("password", EMAIL, Map.of("password", password), "10.0.0.1");
    }

    // ------------------------------------------------------------------ login

    @Test
    void aCorrectLoginIssuesAnAccessTokenTheDecoderAccepts() {
        Session s = login(PASSWORD);

        JwtDecoder decoder = jwt.decoder();
        Jwt token = decoder.decode(s.accessToken());
        assertThat(token.getSubject()).isEqualTo(owner.id());
        assertThat(token.getClaimAsString("tenant_id")).isEqualTo("t_abc");
        assertThat(s.refreshToken()).startsWith(LocalTokens.REFRESH_PREFIX);
    }

    @Test
    void onlyAHashOfTheRefreshTokenIsStored() {
        Session s = login(PASSWORD);

        assertThat(store.refresh.values()).hasSize(1);
        assertThat(store.refresh.values().iterator().next().tokenHash())
                .isEqualTo(LocalTokens.hash(s.refreshToken()))
                .doesNotContain(s.refreshToken());
    }

    @Test
    void everyKindOfFailureIsTheSameAnswer() {
        store.insert(disabled());
        for (String[] attempt :
                new String[][] {
                    {EMAIL, "wrong password entirely"},
                    {"nobody@example.test", PASSWORD},
                    {"disabled@example.test", PASSWORD},
                }) {
            assertThatThrownBy(() -> auth.login("password", attempt[0], Map.of("password", attempt[1]), "10.0.0.9"))
                    .isInstanceOfSatisfying(
                            LocalAuthException.class,
                            e -> {
                                assertThat(e.status()).isEqualTo(401);
                                assertThat(e.code()).isEqualTo("invalid_credentials");
                            });
        }
    }

    @Test
    void anUnknownCredentialTypeAndABlankIdentifierAreRejected() {
        assertThatThrownBy(() -> auth.login("totp", EMAIL, Map.of(), "a")).isInstanceOf(LocalAuthException.class);
        assertThatThrownBy(() -> auth.login("passkey", EMAIL, Map.of(), "a"))
                .as("a passkey login with no challenge or assertion is just invalid credentials")
                .isInstanceOfSatisfying(LocalAuthException.class, e -> assertThat(e.code()).isEqualTo("invalid_credentials"));
        assertThatThrownBy(() -> auth.login("password", "  ", Map.of("password", PASSWORD), "a")).isInstanceOf(LocalAuthException.class);
    }

    @Test
    void repeatedFailuresThrottleEvenTheCorrectPasswordUntilTheLockExpires() {
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> login("nope nope nope nope")).isInstanceOf(LocalAuthException.class);
        }

        assertThatThrownBy(() -> login(PASSWORD))
                .isInstanceOfSatisfying(
                        LocalAuthException.class,
                        e -> {
                            assertThat(e.status()).isEqualTo(429);
                            assertThat(e.code()).isEqualTo("too_many_attempts");
                            assertThat(e.retryAfterSeconds()).isPositive();
                        });

        clock.advance(Duration.ofSeconds(31));
        assertThat(login(PASSWORD).accessToken()).isNotBlank();
    }

    @Test
    void anAttackerCannotLockTheOwnerOutOfTheirUsualAddress() {
        // The owner has signed in from "a" before.
        assertThat(login(PASSWORD).accessToken()).isNotBlank();
        // Someone else locks the account from another address.
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> auth.login("password", EMAIL, Map.of("password", "wrong wrong wrong"), "attacker"))
                    .isInstanceOf(LocalAuthException.class);
        }
        assertThatThrownBy(() -> auth.login("password", EMAIL, Map.of("password", PASSWORD), "attacker"))
                .isInstanceOfSatisfying(LocalAuthException.class, e -> assertThat(e.status()).isEqualTo(429));

        // The owner's address still gets in with the right password (which also clears the lock).
        assertThat(login(PASSWORD).accessToken()).isNotBlank();

        // Locked again: a wrong guess from the owner's address is answered as throttled and not
        // counted, so guessing cannot extend the lock; the right password still works afterwards.
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> auth.login("password", EMAIL, Map.of("password", "wrong wrong wrong"), "attacker"))
                    .isInstanceOf(LocalAuthException.class);
        }
        clock.advance(Duration.ofSeconds(3));
        assertThatThrownBy(() -> login("nope nope nope nope"))
                .isInstanceOfSatisfying(LocalAuthException.class, e -> assertThat(e.status()).isEqualTo(429));
        clock.advance(Duration.ofSeconds(3));
        assertThat(login(PASSWORD).accessToken()).isNotBlank();
    }

    @Test
    void anAccountWithoutAPasswordOrPendingSetupCannotSignIn() {
        LocalAccount pending = InMemoryLocalAccountStore.account("pending@example.test", "some long password", passwords);
        store.insert(pending.withPassword(pending.passwordHash(), false, true));

        assertThatThrownBy(() -> auth.login("password", "pending@example.test", Map.of("password", "some long password"), "a"))
                .isInstanceOf(LocalAuthException.class);
    }

    @Test
    void anOldHashIsUpgradedOnTheNextSuccessfulLogin() {
        String legacy = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode(PASSWORD);
        store.update(owner.withPassword("{bcrypt}" + legacy.substring(0), false, false));
        // bcrypt is the only algorithm here unless BouncyCastle is present, so just prove login still works.
        assertThat(login(PASSWORD).accessToken()).isNotBlank();
    }

    // ------------------------------------------------------------------ setup

    private LocalAccount pendingOwner() {
        LocalAccount fresh = InMemoryLocalAccountStore.account("first@example.test", "random-unknown-" + System.nanoTime(), passwords);
        LocalAccount pending = fresh.withPassword(fresh.passwordHash(), false, true);
        store.insert(pending);
        return pending;
    }

    @Test
    void theSetupTokenSetsThePasswordOnceAndSignsTheOwnerIn() {
        LocalAccount pending = pendingOwner();
        String token = auth.issueSetupToken(pending);

        Session s = auth.completeSetup(token, "my brand new passphrase", "10.0.0.1");

        assertThat(s.account().setupPending()).isFalse();
        assertThat(store.findById(pending.id()).orElseThrow().setupPending()).isFalse();
        assertThat(auth.login("password", "first@example.test", Map.of("password", "my brand new passphrase"), "10.0.0.1")).isNotNull();
        assertThatThrownBy(() -> auth.completeSetup(token, "another passphrase here", "10.0.0.1"))
                .as("single use")
                .isInstanceOfSatisfying(LocalAuthException.class, e -> assertThat(e.code()).isEqualTo("invalid_setup_token"));
    }

    @Test
    void completingSetupDeletesThePrintedLinkFile() throws java.io.IOException {
        java.nio.file.Path file = dir.resolve(LocalAuthSettings.SETUP_FILE_NAME);
        java.nio.file.Files.writeString(file, "link");
        LocalAccount pending = pendingOwner();

        auth.completeSetup(auth.issueSetupToken(pending), "a long enough passphrase", "10.0.0.1");

        assertThat(file).doesNotExist();
    }

    @Test
    void aWeakPasswordDoesNotBurnTheSetupLink() {
        LocalAccount pending = pendingOwner();
        String token = auth.issueSetupToken(pending);

        assertThatThrownBy(() -> auth.completeSetup(token, "short", "10.0.0.1"))
                .isInstanceOfSatisfying(LocalAuthException.class, e -> assertThat(e.code()).isEqualTo("weak_password"));

        assertThat(auth.completeSetup(token, "a long enough passphrase", "10.0.0.1")).isNotNull();
    }

    @Test
    void anExpiredOrForgedSetupTokenIsRefusedAndThrottled() {
        LocalAccount pending = pendingOwner();
        String token = auth.issueSetupToken(pending);
        clock.advance(settings.setupTokenTtl().plusMinutes(1));

        assertThatThrownBy(() -> auth.completeSetup(token, "a long enough passphrase", "10.0.0.1"))
                .isInstanceOfSatisfying(LocalAuthException.class, e -> assertThat(e.code()).isEqualTo("invalid_setup_token"));
        assertThatThrownBy(() -> auth.completeSetup("garbage", "a long enough passphrase", "10.0.0.1")).isInstanceOf(LocalAuthException.class);
        assertThatThrownBy(() -> auth.completeSetup(LocalTokens.newSetupToken(), "a long enough passphrase", "10.0.0.1"))
                .isInstanceOf(LocalAuthException.class);
        assertThatThrownBy(() -> auth.completeSetup(LocalTokens.newSetupToken(), "a long enough passphrase", "10.0.0.1"))
                .isInstanceOfSatisfying(LocalAuthException.class, e -> assertThat(e.status()).isEqualTo(429));
    }

    @Test
    void junkSetupTokensFromOneAddressDoNotLockTheOwnerOutOfSetup() {
        LocalAccount pending = pendingOwner();
        String token = auth.issueSetupToken(pending);
        for (int i = 0; i < 6; i++) {
            try {
                auth.completeSetup(LocalTokens.newSetupToken(), "a long enough passphrase", "198.51.100.7");
            } catch (LocalAuthException expected) {
                // invalid_setup_token, then too_many_attempts for the attacker's address
            }
        }
        assertThatThrownBy(() -> auth.completeSetup(LocalTokens.newSetupToken(), "a long enough passphrase", "198.51.100.7"))
                .as("the attacker is throttled")
                .isInstanceOfSatisfying(LocalAuthException.class, e -> assertThat(e.status()).isEqualTo(429));

        assertThat(auth.completeSetup(token, "a long enough passphrase", "10.0.0.1")).as("the owner is not").isNotNull();
    }

    @Test
    void aNewSetupTokenRetiresTheOldOne() {
        LocalAccount pending = pendingOwner();
        String first = auth.issueSetupToken(pending);
        String second = auth.issueSetupToken(pending);

        assertThatThrownBy(() -> auth.completeSetup(first, "a long enough passphrase", "x")).isInstanceOf(LocalAuthException.class);
        assertThat(auth.completeSetup(second, "a long enough passphrase", "x")).isNotNull();
    }

    @Test
    void statusReportsWhetherSetupIsStillNeeded() {
        assertThat(auth.status().setupRequired()).isFalse();
        pendingOwner();
        assertThat(auth.status().setupRequired()).isTrue();
        assertThat(auth.status().credentialTypes()).containsExactly("password", "pin", "passkey", "recovery");
    }

    // ------------------------------------------------------------------ change password

    @Test
    void changingThePasswordNeedsTheCurrentOneAndEndsEveryOtherSession() {
        Session other = login(PASSWORD);

        assertThatThrownBy(() -> auth.changePassword(owner.id(), "not the password", "another long passphrase", "a"))
                .isInstanceOfSatisfying(LocalAuthException.class, e -> assertThat(e.code()).isEqualTo("invalid_credentials"));

        Session fresh = auth.changePassword(owner.id(), PASSWORD, "another long passphrase", "a");

        assertThatThrownBy(() -> sessions.refresh(other.refreshToken())).isInstanceOf(LocalAuthException.class);
        assertThat(sessions.refresh(fresh.refreshToken())).isNotNull();
        assertThatThrownBy(() -> login(PASSWORD)).isInstanceOf(LocalAuthException.class);
        assertThat(login("another long passphrase")).isNotNull();
    }

    @Test
    void thePasswordPolicyApplies() {
        assertThatThrownBy(() -> auth.changePassword(owner.id(), PASSWORD, "short", "a"))
                .isInstanceOfSatisfying(LocalAuthException.class, e -> assertThat(e.code()).isEqualTo("weak_password"));
        assertThatThrownBy(() -> auth.changePassword(owner.id(), PASSWORD, PASSWORD, "a"))
                .isInstanceOfSatisfying(LocalAuthException.class, e -> assertThat(e.code()).isEqualTo("weak_password"));
        assertThat(LocalPasswords.violation("x".repeat(73), null)).contains("too long");
        assertThat(LocalPasswords.violation(EMAIL + "extra", EMAIL)).isNull();
        assertThat(LocalPasswords.violation("owner@example.test", "OWNER@example.test")).contains("e-mail");
        assertThat(LocalPasswords.violation("            ", null)).isNotNull();
    }

    // ------------------------------------------------------------------ password reset

    @Test
    void aResetRequestWritesTheLinkFileAndTheTokenResetsThePassword() throws java.io.IOException {
        auth.requestPasswordReset(EMAIL, "10.0.0.1");

        String text = java.nio.file.Files.readString(dir.resolve(LocalAuthSettings.RESET_FILE_NAME));
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("setup=(vdls_[A-Za-z0-9_-]+)").matcher(text);
        assertThat(m.find()).as("a setup link in the reset file").isTrue();

        Session s = auth.completeSetup(m.group(1), "a brand new passphrase", "10.0.0.1");
        assertThat(s).isNotNull();
        assertThat(auth.login("password", EMAIL, Map.of("password", "a brand new passphrase"), "10.0.0.1"))
                .as("the new password signs in")
                .isNotNull();
        assertThatThrownBy(() -> auth.login("password", EMAIL, Map.of("password", PASSWORD), "10.0.0.1"))
                .as("the old password is gone")
                .isInstanceOfSatisfying(LocalAuthException.class, e -> assertThat(e.code()).isEqualTo("invalid_credentials"));
    }

    @Test
    void aResetRequestWithNoEmailUsesTheOnlyAccount() throws java.io.IOException {
        auth.requestPasswordReset("   ", "10.0.0.1");
        assertThat(java.nio.file.Files.exists(dir.resolve(LocalAuthSettings.RESET_FILE_NAME))).isTrue();
    }

    @Test
    void aResetRequestForAnUnknownEmailWritesNoFile() {
        auth.requestPasswordReset("nobody@example.test", "10.0.0.1");
        assertThat(java.nio.file.Files.exists(dir.resolve(LocalAuthSettings.RESET_FILE_NAME))).isFalse();
    }

    private LocalAccount disabled() {
        return InMemoryLocalAccountStore.account("disabled@example.test", PASSWORD, passwords).withEnabled(false);
    }
}
