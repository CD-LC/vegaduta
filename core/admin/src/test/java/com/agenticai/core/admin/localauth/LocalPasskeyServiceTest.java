package com.agenticai.core.admin.localauth;

import static com.agenticai.core.admin.localauth.SoftAuthenticator.AT;
import static com.agenticai.core.admin.localauth.SoftAuthenticator.BE;
import static com.agenticai.core.admin.localauth.SoftAuthenticator.BS;
import static com.agenticai.core.admin.localauth.SoftAuthenticator.UP;
import static com.agenticai.core.admin.localauth.SoftAuthenticator.UV;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.admin.localauth.LocalCredentialStore.PasskeyRecord;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The passkey service against a software authenticator that builds registration and assertion
 * messages itself, byte by byte, so the library is checked against an independent encoder: a real
 * EC P-256 key, a real CBOR attestation object, a real ECDSA signature.
 */
class LocalPasskeyServiceTest {

    static final String ORIGIN = "https://console.example.test";
    static final String RP_ID = "console.example.test";
    static final int REG_FLAGS = UP | UV | AT;
    static final int AUTH_FLAGS = UP | UV;

    LocalAuthServiceTest.MutableClock clock = new LocalAuthServiceTest.MutableClock();
    InMemoryLocalCredentialStore store = new InMemoryLocalCredentialStore();
    LocalPasskeyService service = new LocalPasskeyService(store, "", clock);
    LocalAccount owner;
    LocalAccount other;
    SoftAuthenticator key = new SoftAuthenticator();

    @BeforeEach
    void setUp() {
        LocalPasswords passwords = new LocalPasswords();
        owner = InMemoryLocalAccountStore.account("owner@example.test", null, passwords);
        other = InMemoryLocalAccountStore.account("other@example.test", null, passwords);
    }

    @SuppressWarnings("unchecked")
    private static String challengeOf(LocalPasskeyService.Options options) {
        return (String) options.publicKey().get("challenge");
    }

    /** Registers {@link #key} for {@link #owner} the correct way. */
    private PasskeyRecord register() {
        LocalPasskeyService.Options options = service.registrationOptions(owner, ORIGIN, true);
        return service.register(owner, options.challengeId(), key.registration(challengeOf(options), ORIGIN, RP_ID, REG_FLAGS), "Laptop");
    }

    private static void assertInvalid(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        LocalAuthException.class,
                        e -> {
                            assertThat(e.status()).isEqualTo(400);
                            assertThat(e.code()).isEqualTo("passkey_invalid");
                        });
    }

    // ------------------------------------------------------------------ options

    @Test
    void registrationOptionsAskForADiscoverableVerifiedNoneAttestationCredential() {
        LocalPasskeyService.Options options = service.registrationOptions(owner, ORIGIN, true);
        Map<String, Object> pk = options.publicKey();

        assertThat(options.challengeId()).matches("[0-9a-f]{32}");
        assertThat(pk.get("attestation")).isEqualTo("none");
        assertThat(pk.get("authenticatorSelection")).isEqualTo(Map.of("residentKey", "required", "userVerification", "required"));
        assertThat(pk.get("rp")).isEqualTo(Map.of("id", RP_ID, "name", "VegaDūta"));
        assertThat(pk.get("pubKeyCredParams").toString()).contains("-7").contains("-257");
        assertThat(pk.get("excludeCredentials")).isEqualTo(List.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> user = (Map<String, Object>) pk.get("user");
        assertThat(user.get("name")).isEqualTo(owner.email());
        assertThat(new String(java.util.Base64.getUrlDecoder().decode((String) user.get("id")), StandardCharsets.UTF_8)).isEqualTo(owner.id());
        assertThat(java.util.Base64.getUrlDecoder().decode(challengeOf(options))).hasSize(32);
    }

    @Test
    void existingCredentialsAreExcludedForASignedInPersonButNotDuringSetup() {
        PasskeyRecord existing = register();

        assertThat(service.registrationOptions(owner, ORIGIN, true).publicKey().get("excludeCredentials"))
                .isEqualTo(List.of(Map.of("type", "public-key", "id", existing.credentialId())));
        assertThat(service.registrationOptions(owner, ORIGIN, false).publicKey().get("excludeCredentials")).isEqualTo(List.of());
    }

    @Test
    void loginOptionsAreUsernamelessAndRequireUserVerification() {
        LocalPasskeyService.Options options = service.loginOptions(ORIGIN);

        assertThat(options.publicKey().get("rpId")).isEqualTo(RP_ID);
        assertThat(options.publicKey().get("userVerification")).isEqualTo("required");
        assertThat(options.publicKey().get("allowCredentials")).isEqualTo(List.of());
        assertThat(options.challengeId()).matches("[0-9a-f]{32}");
    }

    @Test
    void anIpAddressOrMissingOriginCannotEvenStart() {
        for (String origin : new String[] {"https://192.168.0.5:3000", "http://10.0.0.2", null}) {
            assertThatThrownBy(() -> service.loginOptions(origin))
                    .isInstanceOfSatisfying(LocalAuthException.class, e -> assertThat(e.code()).isEqualTo("passkey_origin_unsupported"));
            assertThatThrownBy(() -> service.registrationOptions(owner, origin, true)).isInstanceOf(LocalAuthException.class);
        }
    }

    @Test
    void theConfiguredOriginListRestrictsOptions() {
        LocalPasskeyService restricted = new LocalPasskeyService(store, "https://console.example.test", clock);

        assertThat(restricted.loginOptions(ORIGIN)).isNotNull();
        assertThatThrownBy(() -> restricted.loginOptions("https://other.example.test")).isInstanceOf(LocalAuthException.class);
    }

    // ------------------------------------------------------------------ registration

    @Test
    void aCorrectRegistrationIsStoredWithItsFlagsAndRpId() {
        PasskeyRecord stored = register();

        assertThat(store.listPasskeys(owner.id())).hasSize(1);
        assertThat(stored.credentialId()).isEqualTo(key.credentialIdB64());
        assertThat(stored.rpId()).isEqualTo(RP_ID);
        assertThat(stored.label()).isEqualTo("Laptop");
        assertThat(stored.uvInitialized()).isTrue();
        assertThat(stored.backupEligible()).isFalse();
        assertThat(stored.transports()).isEqualTo("internal");
        assertThat(stored.attestedCredentialData()).isNotEmpty();
        assertThat(stored.createdAt()).isEqualTo(clock.instant());
    }

    @Test
    void theBackupFlagsAreRecorded() {
        LocalPasskeyService.Options options = service.registrationOptions(owner, ORIGIN, true);
        PasskeyRecord stored =
                service.register(owner, options.challengeId(), key.registration(challengeOf(options), ORIGIN, RP_ID, REG_FLAGS | BE | BS), null);

        assertThat(stored.backupEligible()).isTrue();
        assertThat(stored.backupState()).isTrue();
        assertThat(stored.label()).as("default label").isEqualTo("Passkey");
    }

    @Test
    void registrationFromAnotherOriginIsRefused() {
        LocalPasskeyService.Options options = service.registrationOptions(owner, ORIGIN, true);

        assertInvalid(() -> service.register(owner, options.challengeId(), key.registration(challengeOf(options), "https://evil.example.test", RP_ID, REG_FLAGS), "x"));
        assertThat(store.passkeys).isEmpty();
    }

    @Test
    void registrationForAnotherRpIdIsRefused() {
        LocalPasskeyService.Options options = service.registrationOptions(owner, ORIGIN, true);

        assertInvalid(() -> service.register(owner, options.challengeId(), key.registration(challengeOf(options), ORIGIN, "evil.example.test", REG_FLAGS), "x"));
        assertThat(store.passkeys).isEmpty();
    }

    @Test
    void registrationWithoutUserVerificationIsRefused() {
        LocalPasskeyService.Options options = service.registrationOptions(owner, ORIGIN, true);

        assertInvalid(() -> service.register(owner, options.challengeId(), key.registration(challengeOf(options), ORIGIN, RP_ID, UP | AT), "x"));
        assertThat(store.passkeys).isEmpty();
    }

    @Test
    void registrationAnsweringADifferentChallengeIsRefused() {
        LocalPasskeyService.Options options = service.registrationOptions(owner, ORIGIN, true);
        LocalPasskeyService.Options unrelated = service.registrationOptions(owner, ORIGIN, true);

        assertInvalid(() -> service.register(owner, options.challengeId(), key.registration(challengeOf(unrelated), ORIGIN, RP_ID, REG_FLAGS), "x"));
    }

    @Test
    void aRegistrationChallengeCannotBeReplayed() {
        LocalPasskeyService.Options options = service.registrationOptions(owner, ORIGIN, true);
        String response = key.registration(challengeOf(options), ORIGIN, RP_ID, REG_FLAGS);
        service.register(owner, options.challengeId(), response, "x");

        assertInvalid(() -> service.register(owner, options.challengeId(), response, "x"));
        assertThat(store.passkeys).hasSize(1);
    }

    @Test
    void aWrongAnswerSpendsTheChallengeToo() {
        LocalPasskeyService.Options options = service.registrationOptions(owner, ORIGIN, true);

        assertInvalid(() -> service.register(owner, options.challengeId(), "{}", "x"));
        assertInvalid(() -> service.register(owner, options.challengeId(), key.registration(challengeOf(options), ORIGIN, RP_ID, REG_FLAGS), "x"));
    }

    @Test
    void aRegistrationChallengeBelongsToTheAccountItWasIssuedFor() {
        LocalPasskeyService.Options options = service.registrationOptions(owner, ORIGIN, true);

        assertInvalid(() -> service.register(other, options.challengeId(), key.registration(challengeOf(options), ORIGIN, RP_ID, REG_FLAGS), "x"));
    }

    @Test
    void anExpiredRegistrationChallengeIsRefused() {
        LocalPasskeyService.Options options = service.registrationOptions(owner, ORIGIN, true);
        clock.advance(Duration.ofMinutes(3));

        assertInvalid(() -> service.register(owner, options.challengeId(), key.registration(challengeOf(options), ORIGIN, RP_ID, REG_FLAGS), "x"));
    }

    @Test
    void garbageIsRefusedNotAServerError() {
        for (String junk : new String[] {"", "not json", "{\"id\":\"x\"}", "[]", "null"}) {
            LocalPasskeyService.Options options = service.registrationOptions(owner, ORIGIN, true);
            assertInvalid(() -> service.register(owner, options.challengeId(), junk, "x"));
        }
        assertInvalid(() -> service.register(owner, "unknown", "{}", "x"));
        assertInvalid(() -> service.register(owner, null, "{}", "x"));
    }

    @Test
    void theSameAuthenticatorCannotBeRegisteredTwice() {
        register();

        assertInvalid(this::register);
        assertThat(store.passkeys).hasSize(1);
    }

    @Test
    void thereIsALimitOnPasskeysPerAccount() {
        for (int i = 0; i < LocalPasskeyService.MAX_PER_ACCOUNT; i++) {
            SoftAuthenticator another = new SoftAuthenticator();
            LocalPasskeyService.Options options = service.registrationOptions(owner, ORIGIN, true);
            service.register(owner, options.challengeId(), another.registration(challengeOf(options), ORIGIN, RP_ID, REG_FLAGS), "k" + i);
        }
        LocalPasskeyService.Options options = service.registrationOptions(owner, ORIGIN, true);

        assertThatThrownBy(() -> service.register(owner, options.challengeId(), key.registration(challengeOf(options), ORIGIN, RP_ID, REG_FLAGS), "x"))
                .isInstanceOfSatisfying(LocalAuthException.class, e -> assertThat(e.code()).isEqualTo("limit_reached"));
    }

    // ------------------------------------------------------------------ sign-in

    private Optional<String> signIn(String origin, String rpIdForHash, int flags, long counter, byte[] userHandle, boolean tamper) {
        LocalPasskeyService.Options options = service.loginOptions(ORIGIN);
        return service.verifyAssertion(options.challengeId(), key.assertion(challengeOf(options), origin, rpIdForHash, flags, counter, userHandle, tamper));
    }

    private Optional<String> signIn(long counter) {
        return signIn(ORIGIN, RP_ID, AUTH_FLAGS, counter, null, false);
    }

    @Test
    void aCorrectAssertionIdentifiesTheOwnerAndAdvancesTheCounter() {
        register();

        assertThat(signIn(1)).contains(owner.id());
        PasskeyRecord after = store.listPasskeys(owner.id()).get(0);
        assertThat(after.signCount()).isEqualTo(1);
        assertThat(after.lastUsedAt()).isEqualTo(clock.instant());

        assertThat(signIn(2)).contains(owner.id());
        assertThat(store.listPasskeys(owner.id()).get(0).signCount()).isEqualTo(2);
    }

    @Test
    void anAuthenticatorThatAlwaysReportsZeroIsAccepted() {
        register();

        assertThat(signIn(0)).contains(owner.id());
        assertThat(signIn(0)).contains(owner.id());
    }

    @Test
    void theUserHandleMustMatchTheCredentialOwnerWhenPresent() {
        register();

        assertThat(signIn(ORIGIN, RP_ID, AUTH_FLAGS, 1, owner.id().getBytes(StandardCharsets.UTF_8), false)).contains(owner.id());
        assertThat(signIn(ORIGIN, RP_ID, AUTH_FLAGS, 2, other.id().getBytes(StandardCharsets.UTF_8), false)).as("another account's handle").isEmpty();
    }

    @Test
    void aCounterThatGoesBackwardsIsRefused() {
        register();
        assertThat(signIn(5)).isPresent();

        assertThat(signIn(3)).as("regression").isEmpty();
        assertThat(signIn(5)).as("no progress").isEmpty();
        assertThat(store.listPasskeys(owner.id()).get(0).signCount()).isEqualTo(5);
        assertThat(signIn(6)).as("a good one afterwards still works").isPresent();
    }

    @Test
    void anAssertionFromAnotherOriginIsRefused() {
        register();

        assertThat(signIn("https://evil.example.test", RP_ID, AUTH_FLAGS, 1, null, false)).isEmpty();
        assertThat(store.listPasskeys(owner.id()).get(0).signCount()).isZero();
    }

    @Test
    void aChallengeIssuedForASiblingOriginDoesNotVerifyAgainstTheCredential() {
        register();
        // An attacker page on a sibling origin may legitimately ask the browser for the parent RP ID,
        // and calls the unauthenticated options endpoint with its own origin so the challenge expects it.
        String sibling = "https://evil." + RP_ID;
        LocalPasskeyService.Options options = service.loginOptions(sibling);

        String assertion = key.assertion(challengeOf(options), sibling, RP_ID, AUTH_FLAGS, 1, null, false);

        assertThat(service.verifyAssertion(options.challengeId(), assertion)).isEmpty();
        assertThat(store.listPasskeys(owner.id()).get(0).signCount()).isZero();
    }

    @Test
    void anAssertionForAnotherRpIdIsRefused() {
        register();

        assertThat(signIn(ORIGIN, "evil.example.test", AUTH_FLAGS, 1, null, false)).isEmpty();
    }

    @Test
    void anAssertionWithoutUserVerificationIsRefused() {
        register();

        assertThat(signIn(ORIGIN, RP_ID, UP, 1, null, false)).isEmpty();
    }

    @Test
    void aTamperedSignatureIsRefused() {
        register();

        assertThat(signIn(ORIGIN, RP_ID, AUTH_FLAGS, 1, null, true)).isEmpty();
        assertThat(signIn(2)).as("and the untampered one is fine").isPresent();
    }

    @Test
    void anAssertionSignedByAnotherKeyIsRefused() {
        register();
        SoftAuthenticator impostor = new SoftAuthenticator();
        // Same credential id, different key pair: the lookup finds the owner's row, the signature cannot verify.
        System.arraycopy(key.credentialId, 0, impostor.credentialId, 0, key.credentialId.length);
        LocalPasskeyService.Options options = service.loginOptions(ORIGIN);

        assertThat(service.verifyAssertion(options.challengeId(), impostor.assertion(challengeOf(options), ORIGIN, RP_ID, AUTH_FLAGS, 1, null, false))).isEmpty();
    }

    @Test
    void aLoginChallengeCannotBeReplayed() {
        register();
        LocalPasskeyService.Options options = service.loginOptions(ORIGIN);
        String assertion = key.assertion(challengeOf(options), ORIGIN, RP_ID, AUTH_FLAGS, 1, null, false);

        assertThat(service.verifyAssertion(options.challengeId(), assertion)).isPresent();
        assertThat(service.verifyAssertion(options.challengeId(), assertion)).as("the same message again").isEmpty();
    }

    @Test
    void anAssertionAnsweringADifferentChallengeIsRefused() {
        register();
        LocalPasskeyService.Options asked = service.loginOptions(ORIGIN);
        LocalPasskeyService.Options other = service.loginOptions(ORIGIN);

        assertThat(service.verifyAssertion(asked.challengeId(), key.assertion(challengeOf(other), ORIGIN, RP_ID, AUTH_FLAGS, 1, null, false))).isEmpty();
    }

    @Test
    void anExpiredLoginChallengeIsRefused() {
        register();
        LocalPasskeyService.Options options = service.loginOptions(ORIGIN);
        String assertion = key.assertion(challengeOf(options), ORIGIN, RP_ID, AUTH_FLAGS, 1, null, false);
        clock.advance(Duration.ofMinutes(3));

        assertThat(service.verifyAssertion(options.challengeId(), assertion)).isEmpty();
    }

    @Test
    void aRegistrationChallengeCannotBeUsedToSignIn() {
        register();
        LocalPasskeyService.Options options = service.registrationOptions(owner, ORIGIN, true);

        assertThat(service.verifyAssertion(options.challengeId(), key.assertion(challengeOf(options), ORIGIN, RP_ID, AUTH_FLAGS, 1, null, false))).isEmpty();
    }

    @Test
    void anUnregisteredCredentialUnknownChallengeAndGarbageAreAllJustEmpty() {
        assertThat(signIn(1)).as("never registered").isEmpty();
        assertThat(service.verifyAssertion("nope", "{}")).isEmpty();
        assertThat(service.verifyAssertion(null, null)).isEmpty();
        LocalPasskeyService.Options options = service.loginOptions(ORIGIN);
        assertThat(service.verifyAssertion(options.challengeId(), "not json")).isEmpty();
    }

    @Test
    void movingTheConsoleToAnotherHostInvalidatesThePasskey() {
        register();
        String movedOrigin = "https://moved.example.test";
        LocalPasskeyService.Options options = service.loginOptions(movedOrigin);

        assertThat(service.verifyAssertion(options.challengeId(), key.assertion(challengeOf(options), movedOrigin, "moved.example.test", AUTH_FLAGS, 1, null, false)))
                .as("verified against the STORED rp id, not the one the request names")
                .isEmpty();
    }
}
