package com.agenticai.core.admin.localauth;

import static com.agenticai.core.admin.localauth.SoftAuthenticator.UP;
import static com.agenticai.core.admin.localauth.SoftAuthenticator.UV;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.admin.localauth.LocalAuthService.PinSetup;
import com.agenticai.core.admin.localauth.LocalCredentialStore.PasskeyRecord;
import com.agenticai.core.admin.localauth.LocalSessionService.Session;
import com.agenticai.core.security.localauth.LocalAuthSettings;
import com.agenticai.core.security.localauth.LocalJwtService;
import com.agenticai.core.security.localauth.LocalSigningKeyStore;
import com.agenticai.core.security.localauth.LoginThrottle;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The sign-in methods end to end at the service level: passkey, PIN and recovery code through the
 * SAME {@link LocalAuthService#login} path (and so the same throttle) as passwords, the freshness
 * gate, the never-lock-out rule, and passwordless setup.
 */
class LocalUnlockServiceTest {

    static final String ORIGIN = "https://console.example.test";
    static final String RP_ID = "console.example.test";
    static final String PIN = "482916";
    static final String PASSWORD = "correct horse battery staple";
    static final String IP = "10.0.0.1";

    @TempDir Path dir;

    LocalAuthServiceTest.MutableClock clock = new LocalAuthServiceTest.MutableClock();
    InMemoryLocalAccountStore accounts = new InMemoryLocalAccountStore();
    InMemoryLocalCredentialStore credentials = new InMemoryLocalCredentialStore();
    LocalPasswords passwords = new LocalPasswords();
    LocalPins pins = new LocalPins(passwords);
    LocalAuthSettings settings;
    LocalJwtService jwt;
    LocalDevices devices;
    LocalRecoveryCodes recoveryCodes;
    LocalPasskeyService passkeys;
    LocalAuthService auth;
    LocalUnlockService unlock;
    LocalAccount owner;
    SoftAuthenticator key = new SoftAuthenticator();

    @BeforeEach
    void setUp() {
        settings = new LocalAuthSettings(null, null, dir, null, null, null, null, null, null, null, null);
        jwt = new LocalJwtService(settings, new LocalSigningKeyStore(dir, settings.keyRotationInterval(), settings.keyRetention(), clock), clock);
        LocalSessionService sessions = new LocalSessionService(accounts, jwt, settings, clock);
        // Three failures lock an account/device, three from one address lock the address.
        LoginThrottle throttle = new LoginThrottle(3, 3, Duration.ofSeconds(30), Duration.ofMinutes(10), Duration.ofMinutes(30), 1000, clock);
        devices = new LocalDevices(credentials, pins, clock);
        recoveryCodes = new LocalRecoveryCodes(credentials, clock);
        passkeys = new LocalPasskeyService(credentials, "", clock);
        auth =
                new LocalAuthService(
                        accounts,
                        sessions,
                        passwords,
                        throttle,
                        settings,
                        List.of(
                                new LocalPasswordVerifier(accounts, passwords),
                                new LocalPinVerifier(credentials, accounts, pins, clock),
                                new LocalPasskeyVerifier(passkeys, accounts),
                                new LocalRecoveryVerifier(recoveryCodes, accounts)),
                        devices,
                        recoveryCodes,
                        clock);
        unlock = new LocalUnlockService(accounts, credentials, passkeys, devices, recoveryCodes, auth, clock);
        owner = InMemoryLocalAccountStore.account("owner@example.test", PASSWORD, passwords);
        accounts.insert(owner);
    }

    // ------------------------------------------------------------------ helpers

    private Jwt tokenFor(LocalAccount account, Instant authTime) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "none").subject(account.id());
        if (authTime != null) {
            b.claim("auth_time", authTime.getEpochSecond());
        }
        return b.build();
    }

    private Jwt fresh(LocalAccount account) {
        return tokenFor(account, clock.instant());
    }

    private static void assertRefused(Runnable action, int status, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        LocalAuthException.class,
                        e -> {
                            assertThat(e.status()).isEqualTo(status);
                            assertThat(e.code()).isEqualTo(code);
                        });
    }

    private List<String> amr(Session session) {
        try {
            return com.nimbusds.jwt.JWTParser.parse(session.accessToken()).getJWTClaimsSet().getStringListClaim("amr");
        } catch (java.text.ParseException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Reads the claim without validating time: the decoder uses the wall clock, this test moves its own. */
    private static long authTimeOf(Session session) {
        try {
            return com.nimbusds.jwt.JWTParser.parse(session.accessToken()).getJWTClaimsSet().getLongClaim("auth_time");
        } catch (java.text.ParseException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String challengeOf(LocalPasskeyService.Options options) {
        return (String) options.publicKey().get("challenge");
    }

    private PasskeyRecord registerPasskey(LocalAccount account, SoftAuthenticator authenticator) {
        LocalPasskeyService.Options options = unlock.registrationOptions(fresh(account), null, ORIGIN, IP);
        String response = authenticator.registration(challengeOf(options), ORIGIN, RP_ID, UP | UV | SoftAuthenticator.AT);
        unlock.register(fresh(account), options.challengeId(), response, "Laptop", null, IP);
        return credentials.findPasskeyByCredentialId(authenticator.credentialIdB64()).orElseThrow();
    }

    private Session passkeyLogin(SoftAuthenticator authenticator, long counter, String address) {
        LocalPasskeyService.Options options = unlock.loginOptions(ORIGIN);
        String assertion = authenticator.assertion(challengeOf(options), ORIGIN, RP_ID, UP | UV, counter, null, false);
        return auth.login("passkey", options.challengeId(), Map.of("assertion", assertion), address);
    }

    private LocalAccount pendingAccount() {
        LocalAccount fresh = InMemoryLocalAccountStore.account("first@example.test", null, passwords);
        LocalAccount pending = fresh.withPassword(null, false, true);
        accounts.insert(pending);
        return pending;
    }

    // ------------------------------------------------------------------ one login path, one throttle

    @Test
    void aPinSignsInThroughTheSameLoginAndRecordsPinAsTheMethod() {
        LocalDevices.Enrolled device = devices.enrol(owner.id(), PIN, "Laptop");

        Session s = auth.login("pin", device.deviceId().toUpperCase(), Map.of("pin", PIN, "deviceSecret", device.deviceSecret()), IP);

        assertThat(s.account().id()).isEqualTo(owner.id());
        assertThat(amr(s)).containsExactly("pin");
        assertThat(jwt.decoder().decode(s.accessToken()).getClaim("auth_time").toString()).isEqualTo(String.valueOf(clock.instant().getEpochSecond()));
    }

    @Test
    void wrongPinsAreThrottledLikePasswordsAndTheLockExpires() {
        LocalDevices.Enrolled device = devices.enrol(owner.id(), PIN, "Laptop");
        for (int i = 0; i < 3; i++) {
            assertRefused(() -> auth.login("pin", device.deviceId(), Map.of("pin", "111112", "deviceSecret", device.deviceSecret()), IP), 401, "invalid_credentials");
        }

        assertThatThrownBy(() -> auth.login("pin", device.deviceId(), Map.of("pin", PIN, "deviceSecret", device.deviceSecret()), IP))
                .as("even the right pin is refused while the device is locked")
                .isInstanceOfSatisfying(
                        LocalAuthException.class,
                        e -> {
                            assertThat(e.status()).isEqualTo(429);
                            assertThat(e.code()).isEqualTo("too_many_attempts");
                            assertThat(e.retryAfterSeconds()).isPositive();
                        });
        assertThat(credentials.devices.get(device.deviceId()).failedAttempts()).as("a throttled attempt is not even evaluated").isEqualTo(3);

        clock.advance(Duration.ofSeconds(31));
        assertThat(auth.login("pin", device.deviceId(), Map.of("pin", PIN, "deviceSecret", device.deviceSecret()), IP)).isNotNull();
    }

    @Test
    void thePinOnlyEverWorksWithTheDeviceSecret() {
        LocalDevices.Enrolled device = devices.enrol(owner.id(), PIN, "Laptop");

        assertRefused(() -> auth.login("pin", device.deviceId(), Map.of("pin", PIN), IP), 401, "invalid_credentials");
        assertRefused(() -> auth.login("pin", device.deviceId(), Map.of("pin", PIN, "deviceSecret", pins.newDeviceSecret()), IP), 401, "invalid_credentials");
    }

    @Test
    void aPasskeySignsInThroughTheSameLoginAndRecordsHardwareKeyAsTheMethod() {
        registerPasskey(owner, key);

        Session s = passkeyLogin(key, 1, IP);

        assertThat(s.account().id()).isEqualTo(owner.id());
        assertThat(amr(s)).containsExactly("hwk");
    }

    @Test
    void aPasskeyForADisabledAccountDoesNotSignIn() {
        registerPasskey(owner, key);
        accounts.update(owner.withEnabled(false));

        assertRefused(() -> passkeyLogin(key, 1, IP), 401, "invalid_credentials");
    }

    @Test
    void failedPasskeyAttemptsLockTheAddressButNotOtherAddresses() {
        registerPasskey(owner, key);
        for (int i = 0; i < 3; i++) {
            LocalPasskeyService.Options options = unlock.loginOptions(ORIGIN);
            assertRefused(() -> auth.login("passkey", options.challengeId(), Map.of("assertion", "{}"), "198.51.100.7"), 401, "invalid_credentials");
        }

        assertThatThrownBy(() -> passkeyLogin(key, 1, "198.51.100.7"))
                .as("a valid assertion from the locked address")
                .isInstanceOfSatisfying(LocalAuthException.class, e -> assertThat(e.status()).isEqualTo(429));
        assertThat(passkeyLogin(key, 1, "203.0.113.9")).as("another address is unaffected").isNotNull();
    }

    @Test
    void aRecoveryCodeSignsInOnceAndRecordsRecAsTheMethod() {
        String code = recoveryCodes.regenerate(owner.id()).get(2);

        Session s = auth.login("recovery", "recovery", Map.of("code", code.toLowerCase()), IP);

        assertThat(s.account().id()).isEqualTo(owner.id());
        assertThat(amr(s)).containsExactly("rec");
        assertRefused(() -> auth.login("recovery", "recovery", Map.of("code", code), IP), 401, "invalid_credentials");
    }

    @Test
    void wrongRecoveryCodesLockTheGuessingAddressAndTheLockExpires() {
        String code = recoveryCodes.regenerate(owner.id()).get(0);
        String attacker = "198.51.100.10";
        for (int i = 0; i < 3; i++) {
            assertRefused(() -> auth.login("recovery", "recovery", Map.of("code", "AAAA-AAAA-AAAA-AAAA"), attacker), 401, "invalid_credentials");
        }

        assertRefused(() -> auth.login("recovery", "recovery", Map.of("code", code), attacker), 429, "too_many_attempts");
        clock.advance(Duration.ofSeconds(31));
        assertThat(auth.login("recovery", "recovery", Map.of("code", code), attacker)).isNotNull();
    }

    @Test
    void anAnonymousCallerCannotKeepTheOwnersRecoveryCodesLockedFromOtherAddresses() {
        String code = recoveryCodes.regenerate(owner.id()).get(0);
        for (int i = 0; i < 3; i++) {
            // Whatever identifier the page sends, the bucket is the caller's own address.
            String identifier = "recovery" + i;
            assertRefused(() -> auth.login("recovery", identifier, Map.of("code", "AAAA-AAAA-AAAA-AAAA"), "198.51.100.10"), 401, "invalid_credentials");
        }
        assertRefused(() -> auth.login("recovery", "recovery", Map.of("code", "AAAA-AAAA-AAAA-AAAA"), "198.51.100.10"), 429, "too_many_attempts");

        assertThat(auth.login("recovery", "recovery", Map.of("code", code), "203.0.113.9")).as("the owner, elsewhere").isNotNull();
    }

    @Test
    void theSessionAMethodStartsSurvivesRefreshWithItsOriginalAuthTime() {
        LocalDevices.Enrolled device = devices.enrol(owner.id(), PIN, "Laptop");
        Session s = auth.login("pin", device.deviceId(), Map.of("pin", PIN, "deviceSecret", device.deviceSecret()), IP);
        long signedInAt = clock.instant().getEpochSecond();
        clock.advance(Duration.ofMinutes(20));

        Session refreshed = new LocalSessionService(accounts, jwt, settings, clock).refresh(s.refreshToken());

        assertThat(authTimeOf(refreshed))
                .as("refreshing does not make the sign-in fresh")
                .isEqualTo(signedInAt);
        assertThat(amr(refreshed)).containsExactly("pin");
    }

    // ------------------------------------------------------------------ freshness

    @Test
    void sensitiveCallsNeedASignInFromTheLastTenMinutes() {
        Jwt nineMinutes = tokenFor(owner, clock.instant().minus(Duration.ofMinutes(9)).minusSeconds(59));
        Jwt stale = tokenFor(owner, clock.instant().minus(Duration.ofMinutes(10)).minusSeconds(1));

        assertThat(unlock.enrolDevice(nineMinutes, PIN, "ok").deviceId()).isNotBlank();
        assertRefused(() -> unlock.enrolDevice(stale, PIN, "late"), 403, "reauth_required");
    }

    @Test
    void everyChangeToHowYouSignInIsFreshGated() {
        Jwt stale = tokenFor(owner, clock.instant().minus(Duration.ofHours(2)));
        String deviceId = devices.enrol(owner.id(), PIN, "d").deviceId();
        PasskeyRecord passkey = registerPasskey(owner, key);

        assertRefused(() -> unlock.enrolDevice(stale, PIN, "x"), 403, "reauth_required");
        assertRefused(() -> unlock.removeDevice(stale, deviceId), 403, "reauth_required");
        assertRefused(() -> unlock.removePasskey(stale, passkey.id()), 403, "reauth_required");
        assertRefused(() -> unlock.regenerateRecoveryCodes(stale), 403, "reauth_required");
        assertRefused(() -> unlock.registrationOptions(stale, null, ORIGIN, IP), 403, "reauth_required");
        assertRefused(() -> unlock.register(stale, "c", "{}", "x", null, IP), 403, "reauth_required");
        assertThat(credentials.listPasskeys(owner.id())).hasSize(1);
        assertThat(credentials.listActiveDevices(owner.id())).hasSize(1);
    }

    @Test
    void aTokenWithoutAuthTimeIsNeverFresh_andReadingMethodsNeedsNoFreshness() {
        Jwt noAuthTime = tokenFor(owner, null);
        Jwt stale = tokenFor(owner, clock.instant().minus(Duration.ofDays(3)));

        assertRefused(() -> unlock.regenerateRecoveryCodes(noAuthTime), 403, "reauth_required");
        assertThat(unlock.methods(stale).hasPassword()).isTrue();
    }

    @Test
    void aTokenForADeletedOrDisabledAccountIsAnInvalidSession() {
        Jwt gone = tokenFor(InMemoryLocalAccountStore.account("ghost@example.test", null, passwords), clock.instant());
        accounts.update(owner.withEnabled(false));

        assertRefused(() -> unlock.methods(gone), 401, "invalid_session");
        assertRefused(() -> unlock.methods(fresh(owner)), 401, "invalid_session");
        assertRefused(() -> unlock.methods(null), 401, "invalid_session");
    }

    // ------------------------------------------------------------------ methods, removal, never lock out

    @Test
    void methodsListWhatIsEnrolledAndHideSecrets() {
        devices.enrol(owner.id(), PIN, "Laptop");
        devices.revoke(owner.id(), devices.enrol(owner.id(), PIN, "Old phone").deviceId());
        registerPasskey(owner, key);
        recoveryCodes.regenerate(owner.id());

        LocalUnlockService.Methods methods = unlock.methods(fresh(owner));

        assertThat(methods.hasPassword()).isTrue();
        assertThat(methods.devices()).extracting(LocalUnlockService.DeviceView::label).containsExactly("Laptop");
        assertThat(methods.passkeys()).extracting(LocalUnlockService.PasskeyView::label).containsExactly("Laptop");
        assertThat(methods.passkeys().get(0).backedUp()).isFalse();
        assertThat(methods.recoveryCodesRemaining()).isEqualTo(8);
        assertThat(methods.toString()).doesNotContain("pinHash").doesNotContain("attested");
    }

    @Test
    void anAccountWithAPasswordCanRemoveItsOnlyPasskey() {
        PasskeyRecord passkey = registerPasskey(owner, key);

        unlock.removePasskey(fresh(owner), passkey.id());

        assertThat(credentials.listPasskeys(owner.id())).isEmpty();
        assertRefused(() -> passkeyLogin(key, 1, IP), 401, "invalid_credentials");
    }

    @Test
    void thereIsAlwaysAWayLeftToSignIn() {
        accounts.update(owner.withPassword(null, false, false)); // passwordless
        PasskeyRecord passkey = registerPasskey(owner, key);
        String device = devices.enrol(owner.id(), PIN, "Laptop").deviceId();

        unlock.removePasskey(fresh(owner), passkey.id()); // the device remains
        assertRefused(() -> unlock.removeDevice(fresh(owner), device), 409, "last_credential");

        PasskeyRecord second = registerPasskey(owner, new SoftAuthenticator());
        unlock.removeDevice(fresh(owner), device); // the second passkey remains
        assertRefused(() -> unlock.removePasskey(fresh(owner), second.id()), 409, "last_credential");

        String phone = devices.enrol(owner.id(), PIN, "Phone").deviceId();
        unlock.removePasskey(fresh(owner), second.id()); // the phone remains
        assertRefused(() -> unlock.removeDevice(fresh(owner), phone), 409, "last_credential");
        assertThat(credentials.listActiveDevices(owner.id())).hasSize(1);
    }

    @Test
    void aRemovedDeviceNoLongerSignsIn() {
        LocalDevices.Enrolled device = devices.enrol(owner.id(), PIN, "Laptop");

        unlock.removeDevice(fresh(owner), device.deviceId());

        assertThatThrownBy(() -> auth.login("pin", device.deviceId(), Map.of("pin", PIN, "deviceSecret", device.deviceSecret()), IP))
                .isInstanceOf(LocalAuthException.class);
    }

    @Test
    void junkAndForeignIdsAreNotFound() {
        LocalAccount other = InMemoryLocalAccountStore.account("other@example.test", PASSWORD, passwords);
        accounts.insert(other);
        PasskeyRecord theirs = registerPasskey(other, new SoftAuthenticator());
        String theirDevice = devices.enrol(other.id(), PIN, "Theirs").deviceId();

        assertRefused(() -> unlock.removePasskey(fresh(owner), "not-a-uuid"), 404, "not_found");
        assertRefused(() -> unlock.removePasskey(fresh(owner), InMemoryLocalCredentialStore.newId()), 404, "not_found");
        assertRefused(() -> unlock.removePasskey(fresh(owner), theirs.id()), 404, "not_found");
        assertRefused(() -> unlock.removeDevice(fresh(owner), theirDevice), 404, "not_found");
        assertThat(credentials.listPasskeys(other.id())).hasSize(1);
        assertThat(credentials.listActiveDevices(other.id())).hasSize(1);
    }

    @Test
    void regeneratingRecoveryCodesInvalidatesTheOldSet() {
        List<String> old = unlock.regenerateRecoveryCodes(fresh(owner));
        List<String> fresh = unlock.regenerateRecoveryCodes(fresh(owner));

        assertThat(fresh).hasSize(8).doesNotContainAnyElementsOf(old);
        assertRefused(() -> auth.login("recovery", "recovery", Map.of("code", old.get(0)), IP), 401, "invalid_credentials");
        assertThat(auth.login("recovery", "recovery", Map.of("code", fresh.get(0)), IP)).isNotNull();
    }

    // ------------------------------------------------------------------ setup without a password

    @Test
    void setupWithAPinLeavesNoPasswordAndTheDeviceSignsIn() {
        LocalAccount pending = pendingAccount();
        String token = auth.issueSetupToken(pending);

        PinSetup done = auth.completeSetupWithPin(token, PIN, "Kitchen laptop", IP);

        LocalAccount after = accounts.findById(pending.id()).orElseThrow();
        assertThat(after.passwordHash()).as("no password was ever set").isNull();
        assertThat(after.setupPending()).isFalse();
        assertThat(done.session().account().id()).isEqualTo(pending.id());
        assertThat(amr(done.session())).containsExactly("pin");
        assertThat(done.recoveryCodes()).hasSize(8);
        assertThat(done.deviceSecret()).hasSize(43);
        assertThat(credentials.listActiveDevices(pending.id())).extracting(d -> d.label()).containsExactly("Kitchen laptop");

        Session again = auth.login("pin", done.deviceId(), Map.of("pin", PIN, "deviceSecret", done.deviceSecret()), IP);
        assertThat(again.account().id()).isEqualTo(pending.id());
        assertRefused(() -> auth.login("password", "first@example.test", Map.of("password", "anything at all, really"), IP), 401, "invalid_credentials");
        assertThat(auth.login("recovery", "recovery", Map.of("code", done.recoveryCodes().get(0)), IP)).isNotNull();
    }

    @Test
    void setupWithAPinDropsATemporaryPasswordTheAdminKnows() {
        // An admin created the account with a temporary password and then minted an invite link.
        LocalAccount temp = InMemoryLocalAccountStore.account("temp@example.test", "admin chosen temporary pw", passwords).withPassword(
                passwords.hash("admin chosen temporary pw"), true, false);
        accounts.insert(temp);
        String token = auth.issueSetupToken(temp);

        PinSetup done = auth.completeSetupWithPin(token, PIN, "Laptop", IP);

        LocalAccount after = accounts.findById(temp.id()).orElseThrow();
        assertThat(after.passwordHash()).isNull();
        assertThat(after.mustChangePassword()).isFalse();
        assertRefused(() -> auth.login("password", "temp@example.test", Map.of("password", "admin chosen temporary pw"), IP), 401, "invalid_credentials");
        assertThat(auth.login("pin", done.deviceId(), Map.of("pin", PIN, "deviceSecret", done.deviceSecret()), IP)).isNotNull();
        assertThat(unlock.methods(fresh(after)).hasPassword()).isFalse();
    }

    @Test
    void aFirstRunOwnerWhoSetUpWithOnePasskeyCannotRemoveIt() {
        // The first-run owner is created with a random password nobody knows, then set up with a passkey.
        LocalAccount first = InMemoryLocalAccountStore.account("first@example.test", "random password nobody knows", passwords);
        accounts.insert(first.withPassword(first.passwordHash(), false, true));
        String token = auth.issueSetupToken(accounts.findById(first.id()).orElseThrow());
        LocalPasskeyService.Options options = unlock.registrationOptions(null, token, ORIGIN, IP);
        unlock.register(null, options.challengeId(), key.registration(challengeOf(options), ORIGIN, RP_ID, UP | UV | SoftAuthenticator.AT), "Laptop", token, IP);
        PasskeyRecord only = credentials.listPasskeys(first.id()).get(0);

        assertRefused(() -> unlock.removePasskey(fresh(first), only.id()), 409, "last_credential");
        assertThat(credentials.listPasskeys(first.id())).hasSize(1);
    }

    @Test
    void aPendingAccountsStoredPasswordIsNotAWayToSignIn() {
        LocalAccount pending = InMemoryLocalAccountStore.account("pending@example.test", PASSWORD, passwords);
        accounts.insert(pending.withPassword(pending.passwordHash(), false, true));
        PasskeyRecord passkey = registerPasskey(pending, new SoftAuthenticator());

        assertRefused(() -> unlock.removePasskey(fresh(pending), passkey.id()), 409, "last_credential");
        assertThat(unlock.methods(fresh(pending)).hasPassword()).isFalse();
    }

    @Test
    void removingACredentialEndsTheOtherLoginsButNotTheCallers() {
        Session mine = auth.login("password", "owner@example.test", Map.of("password", PASSWORD), IP);
        Session thiefs = auth.login("password", "owner@example.test", Map.of("password", PASSWORD), "198.51.100.7");
        String sid = jwt.decoder().decode(mine.accessToken()).getClaimAsString("sid");
        Jwt caller = Jwt.withTokenValue("t").header("alg", "none").subject(owner.id()).claim("sid", sid).claim("auth_time", clock.instant().getEpochSecond()).build();
        String stolenDevice = devices.enrol(owner.id(), PIN, "Stolen laptop").deviceId();

        unlock.removeDevice(caller, stolenDevice);

        LocalSessionService sessions = new LocalSessionService(accounts, jwt, settings, clock);
        assertRefused(() -> sessions.refresh(thiefs.refreshToken()), 401, "invalid_session");
        assertThat(sessions.refresh(mine.refreshToken())).isNotNull();
    }

    @Test
    void removingAPasskeyAndRegeneratingRecoveryCodesAlsoEndOtherLogins() {
        PasskeyRecord passkey = registerPasskey(owner, key);
        Session mine = auth.login("password", "owner@example.test", Map.of("password", PASSWORD), IP);
        Session other = auth.login("password", "owner@example.test", Map.of("password", PASSWORD), "198.51.100.7");
        Session another = auth.login("password", "owner@example.test", Map.of("password", PASSWORD), "198.51.100.8");
        String sid = jwt.decoder().decode(mine.accessToken()).getClaimAsString("sid");
        Jwt caller = Jwt.withTokenValue("t").header("alg", "none").subject(owner.id()).claim("sid", sid).claim("auth_time", clock.instant().getEpochSecond()).build();
        LocalSessionService sessions = new LocalSessionService(accounts, jwt, settings, clock);

        unlock.removePasskey(caller, passkey.id());
        assertRefused(() -> sessions.refresh(other.refreshToken()), 401, "invalid_session");

        unlock.regenerateRecoveryCodes(caller);
        assertRefused(() -> sessions.refresh(another.refreshToken()), 401, "invalid_session");
        assertThat(sessions.refresh(mine.refreshToken())).isNotNull();
    }

    @Test
    void setupWithAPinIsSingleUseAndAWeakPinDoesNotBurnTheLink() {
        LocalAccount pending = pendingAccount();
        String token = auth.issueSetupToken(pending);

        assertRefused(() -> auth.completeSetupWithPin(token, "123456", "x", IP), 400, "weak_pin");
        assertRefused(() -> auth.completeSetupWithPin(token, "12ab", "x", IP), 400, "weak_pin");
        assertThat(credentials.devices).as("no device was left behind").isEmpty();

        auth.completeSetupWithPin(token, PIN, "x", IP);
        assertRefused(() -> auth.completeSetupWithPin(token, PIN, "again", IP), 401, "invalid_setup_token");
    }

    @Test
    void setupWithAPinNeedsAValidTokenAndIsThrottledPerAddress() {
        pendingAccount();

        for (int i = 0; i < 3; i++) {
            assertRefused(() -> auth.completeSetupWithPin(LocalTokens.newSetupToken(), PIN, "x", "198.51.100.7"), 401, "invalid_setup_token");
        }
        assertRefused(() -> auth.completeSetupWithPin(LocalTokens.newSetupToken(), PIN, "x", "198.51.100.7"), 429, "too_many_attempts");
        assertRefused(() -> auth.completeSetupWithPin(null, PIN, "x", "203.0.113.5"), 401, "invalid_setup_token");
        assertRefused(() -> auth.completeSetupWithPin("garbage", PIN, "x", "203.0.113.5"), 401, "invalid_setup_token");
    }

    @Test
    void setupWithAPasskeyConsumesTheTokenOnlyAfterTheCredentialVerified() {
        LocalAccount pending = pendingAccount();
        String token = auth.issueSetupToken(pending);
        LocalPasskeyService.Options options = unlock.registrationOptions(null, token, ORIGIN, IP);

        assertRefused(() -> unlock.register(null, options.challengeId(), key.registration(challengeOf(options), "https://evil.example.test", RP_ID, UP | UV | SoftAuthenticator.AT), "x", token, IP), 400, "passkey_invalid");
        assertThat(accounts.findById(pending.id()).orElseThrow().setupPending()).as("still pending: the link was not burnt").isTrue();

        LocalPasskeyService.Options retry = unlock.registrationOptions(null, token, ORIGIN, IP);
        LocalUnlockService.Registered done =
                unlock.register(null, retry.challengeId(), key.registration(challengeOf(retry), ORIGIN, RP_ID, UP | UV | SoftAuthenticator.AT), "MacBook", token, IP);

        assertThat(done.session()).isNotNull();
        assertThat(amr(done.session())).containsExactly("hwk");
        assertThat(done.recoveryCodes()).hasSize(8);
        assertThat(done.passkey().label()).isEqualTo("MacBook");
        LocalAccount after = accounts.findById(pending.id()).orElseThrow();
        assertThat(after.setupPending()).isFalse();
        assertThat(after.passwordHash()).isNull();
        assertThat(passkeyLogin(key, 1, IP).account().id()).isEqualTo(pending.id());
        assertRefused(() -> unlock.registrationOptions(null, token, ORIGIN, IP), 401, "invalid_setup_token");
    }

    @Test
    void passkeyRegistrationWithoutABearerNeedsAValidSetupToken() {
        pendingAccount();

        assertRefused(() -> unlock.registrationOptions(null, null, ORIGIN, IP), 401, "invalid_setup_token");
        assertRefused(() -> unlock.registrationOptions(null, "vdls_forged", ORIGIN, IP), 401, "invalid_setup_token");
        assertRefused(() -> unlock.register(null, "c", "{}", "x", "vdls_forged", IP), 401, "invalid_setup_token");
    }

    @Test
    void aSignedInPersonRegistersWithoutTouchingSetup() {
        PasskeyRecord passkey = registerPasskey(owner, key);

        assertThat(passkey.accountId()).isEqualTo(owner.id());
        assertThat(unlock.methods(fresh(owner)).passkeys()).hasSize(1);
    }
}
