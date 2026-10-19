package com.agenticai.core.admin.localauth;

import com.agenticai.core.admin.localauth.LocalCredentialStore.DuplicateCredentialException;
import com.agenticai.core.admin.localauth.LocalCredentialStore.PasskeyRecord;
import com.agenticai.core.admin.localauth.PasskeyChallenges.Challenge;
import com.agenticai.core.admin.localauth.PasskeyChallenges.Purpose;
import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.converter.AttestedCredentialDataConverter;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.credential.CredentialRecordImpl;
import com.webauthn4j.data.AuthenticationData;
import com.webauthn4j.data.AuthenticationParameters;
import com.webauthn4j.data.AuthenticatorTransport;
import com.webauthn4j.data.PublicKeyCredentialParameters;
import com.webauthn4j.data.PublicKeyCredentialType;
import com.webauthn4j.data.RegistrationData;
import com.webauthn4j.data.RegistrationParameters;
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData;
import com.webauthn4j.data.attestation.authenticator.AuthenticatorData;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.attestation.statement.NoneAttestationStatement;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.server.ServerProperty;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * WebAuthn for local mode: builds the options the browser passes to {@code navigator.credentials},
 * and verifies what comes back, with webauthn4j.
 *
 * <p><b>What is required.</b> Usernameless (discoverable) credentials with user verification, so a
 * passkey replaces the e-mail field AND the password. Attestation is {@code none}: the install trusts
 * whatever authenticator its owner chooses, so nothing is gained by checking a vendor chain. ES256 and
 * RS256 only.
 *
 * <p><b>Challenges</b> are 32 random bytes, in memory, single use, two minutes ({@link
 * PasskeyChallenges}). A registration challenge is bound to the account it was issued for; a sign-in
 * challenge is bound to nobody, because the credential itself says who is signing in.
 *
 * <p><b>RP ID and origin</b> come from the request ({@link PasskeyOrigins}) and the RP ID is stored
 * with each credential. Sign-in verifies against the STORED RP ID, so moving the console to another
 * host makes the old passkeys fail verification instead of validating against the wrong relying party.
 *
 * <p><b>User handle.</b> The account id as UTF-8 bytes. When an assertion carries one it must equal
 * the owner of the credential found by id; a mismatch is refused.
 *
 * <p>Nothing submitted is ever logged, and a library failure is reported only by its class name.
 */
@Service
@ConditionalOnProperty(name = "app.auth.mode", havingValue = "local")
public class LocalPasskeyService {

    private static final Logger log = LoggerFactory.getLogger(LocalPasskeyService.class);

    public static final int MAX_PER_ACCOUNT = 20;
    private static final int TIMEOUT_MILLIS = 60_000;

    private static final List<PublicKeyCredentialParameters> ALGORITHMS =
            List.of(
                    new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.ES256),
                    new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.RS256));

    /** What the browser needs to start a ceremony: the id to send back, and the options. */
    public record Options(String challengeId, Map<String, Object> publicKey) {}

    private final LocalCredentialStore store;
    private final PasskeyOrigins origins;
    private final PasskeyChallenges challenges;
    private final Clock clock;
    private final ObjectConverter objectConverter = new ObjectConverter();
    private final WebAuthnManager manager = WebAuthnManager.createNonStrictWebAuthnManager(objectConverter);
    private final AttestedCredentialDataConverter credentialDataConverter = new AttestedCredentialDataConverter(objectConverter);

    @Autowired
    public LocalPasskeyService(
            LocalCredentialStore store, @Value("${app.auth.local.passkey.origins:}") String allowedOrigins) {
        this(store, allowedOrigins, Clock.systemUTC());
    }

    LocalPasskeyService(LocalCredentialStore store, String allowedOrigins, Clock clock) {
        this.store = store;
        this.origins = new PasskeyOrigins(allowedOrigins);
        this.clock = clock;
        this.challenges = new PasskeyChallenges(clock);
    }

    // ------------------------------------------------------------------ registration

    /**
     * Options for registering a passkey for {@code account}. With {@code excludeExisting} the
     * account's current credentials are listed so the same authenticator is not enrolled twice.
     */
    public Options registrationOptions(LocalAccount account, String originHeader, boolean excludeExisting) {
        PasskeyOrigins.Resolved where = origins.resolve(originHeader);
        Challenge challenge = challenges.issue(Purpose.REGISTER, account.id(), where.origin(), where.rpId());

        Map<String, Object> rp = new LinkedHashMap<>();
        rp.put("id", where.rpId());
        rp.put("name", "VegaDūta");
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", b64(account.id().getBytes(StandardCharsets.UTF_8)));
        user.put("name", account.email());
        user.put("displayName", displayName(account));
        List<Map<String, Object>> exclude = new ArrayList<>();
        if (excludeExisting) {
            for (PasskeyRecord existing : store.listPasskeys(account.id())) {
                exclude.add(Map.of("type", "public-key", "id", existing.credentialId()));
            }
        }
        Map<String, Object> selection = new LinkedHashMap<>();
        selection.put("residentKey", "required");
        selection.put("userVerification", "required");

        Map<String, Object> publicKey = new LinkedHashMap<>();
        publicKey.put("challenge", b64(challenge.bytes()));
        publicKey.put("rp", rp);
        publicKey.put("user", user);
        publicKey.put("pubKeyCredParams", List.of(Map.of("type", "public-key", "alg", -7), Map.of("type", "public-key", "alg", -257)));
        publicKey.put("timeout", TIMEOUT_MILLIS);
        publicKey.put("attestation", "none");
        publicKey.put("authenticatorSelection", selection);
        publicKey.put("excludeCredentials", exclude);
        return new Options(challenge.id(), publicKey);
    }

    /**
     * Verifies a registration response against the challenge and stores the credential.
     *
     * @throws LocalAuthException {@code passkey_invalid} for anything wrong with the response or the challenge
     */
    public PasskeyRecord register(LocalAccount account, String challengeId, String credentialJson, String label) {
        // Taken first and unconditionally: a challenge is spent by being answered, right or wrong.
        Challenge challenge =
                challenges
                        .take(challengeId, Purpose.REGISTER)
                        .filter(c -> account.id().equals(c.accountId()))
                        .orElseThrow(LocalAuthException::passkeyInvalid);
        if (store.listPasskeys(account.id()).size() >= MAX_PER_ACCOUNT) {
            throw LocalAuthException.limitReached("This account already has the maximum number of passkeys.");
        }
        RegistrationData data;
        try {
            ServerProperty server =
                    new ServerProperty(Set.of(new Origin(challenge.origin())), challenge.rpId(), new DefaultChallenge(challenge.bytes()));
            data = manager.verifyRegistrationResponseJSON(credentialJson, new RegistrationParameters(server, ALGORITHMS, true, true));
        } catch (RuntimeException e) {
            log.debug("Passkey registration refused: {}", e.getClass().getSimpleName());
            throw LocalAuthException.passkeyInvalid();
        }
        AuthenticatorData<?> authData = data.getAttestationObject().getAuthenticatorData();
        AttestedCredentialData credential = authData.getAttestedCredentialData();
        if (credential == null) {
            throw LocalAuthException.passkeyInvalid();
        }
        String transports =
                data.getTransports() == null
                        ? ""
                        : data.getTransports().stream().map(AuthenticatorTransport::getValue).sorted().collect(Collectors.joining(","));
        PasskeyRecord record =
                new PasskeyRecord(
                        UUID.randomUUID().toString(),
                        account.id(),
                        b64(credential.getCredentialId()),
                        credentialDataConverter.convert(credential),
                        authData.getSignCount(),
                        authData.isFlagUV(),
                        authData.isFlagBE(),
                        authData.isFlagBS(),
                        transports,
                        challenge.rpId(),
                        cleanLabel(label, "Passkey"),
                        clock.instant(),
                        null);
        try {
            store.insertPasskey(record);
        } catch (DuplicateCredentialException e) {
            throw LocalAuthException.passkeyInvalid();
        }
        return record;
    }

    // ------------------------------------------------------------------ sign-in

    /** Options for a usernameless sign-in: no allow-list, the authenticator offers its own credentials. */
    public Options loginOptions(String originHeader) {
        PasskeyOrigins.Resolved where = origins.resolve(originHeader);
        Challenge challenge = challenges.issue(Purpose.LOGIN, null, where.origin(), where.rpId());
        Map<String, Object> publicKey = new LinkedHashMap<>();
        publicKey.put("challenge", b64(challenge.bytes()));
        publicKey.put("rpId", where.rpId());
        publicKey.put("timeout", TIMEOUT_MILLIS);
        publicKey.put("userVerification", "required");
        publicKey.put("allowCredentials", List.of());
        return new Options(challenge.id(), publicKey);
    }

    /** Verifies an assertion; returns the owning account id, or empty for any failure at all. */
    public Optional<String> verifyAssertion(String challengeId, String assertionJson) {
        Challenge challenge = challenges.take(challengeId, Purpose.LOGIN).orElse(null);
        if (challenge == null || assertionJson == null) {
            return Optional.empty();
        }
        try {
            AuthenticationData parsed = manager.parseAuthenticationResponseJSON(assertionJson);
            PasskeyRecord passkey = store.findPasskeyByCredentialId(b64(parsed.getCredentialId())).orElse(null);
            if (passkey == null) {
                return Optional.empty();
            }
            // The challenge origin comes from a request header of the unauthenticated options call, so
            // it is pinned here to the credential: its host must BE the RP ID the passkey was made
            // for. A page on a sibling origin (or another subdomain) cannot get a challenge that
            // expects its own origin and still verify against this credential.
            PasskeyOrigins.Resolved asked = PasskeyOrigins.parse(challenge.origin());
            if (asked == null || !asked.rpId().equals(passkey.rpId())) {
                return Optional.empty();
            }
            byte[] handle = parsed.getUserHandle();
            if (handle != null && handle.length > 0 && !MessageDigest.isEqual(handle, passkey.accountId().getBytes(StandardCharsets.UTF_8))) {
                return Optional.empty();
            }
            CredentialRecordImpl stored =
                    new CredentialRecordImpl(
                            new NoneAttestationStatement(),
                            passkey.uvInitialized(),
                            passkey.backupEligible(),
                            passkey.backupState(),
                            passkey.signCount(),
                            credentialDataConverter.convert(passkey.attestedCredentialData()),
                            null,
                            null,
                            null,
                            transports(passkey.transports()));
            ServerProperty server =
                    new ServerProperty(Set.of(new Origin(challenge.origin())), passkey.rpId(), new DefaultChallenge(challenge.bytes()));
            AuthenticationData verified =
                    manager.verifyAuthenticationResponseJSON(assertionJson, new AuthenticationParameters(server, stored, null, true, true));
            AuthenticatorData<?> authData = verified.getAuthenticatorData();
            if (!store.recordPasskeyUse(passkey.id(), authData.getSignCount(), authData.isFlagBS(), clock.instant())) {
                log.warn("Local auth: passkey {} presented a counter behind the stored one; refused", passkey.id());
                return Optional.empty();
            }
            return Optional.of(passkey.accountId());
        } catch (RuntimeException e) {
            log.debug("Passkey assertion refused: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------ helpers

    static String cleanLabel(String label, String fallback) {
        String trimmed = label == null ? "" : label.strip();
        if (trimmed.isEmpty()) {
            return fallback;
        }
        return trimmed.length() > 100 ? trimmed.substring(0, 100) : trimmed;
    }

    private static String displayName(LocalAccount account) {
        String name = ((account.firstName() == null ? "" : account.firstName()) + " " + (account.lastName() == null ? "" : account.lastName())).strip();
        return name.isEmpty() ? account.email() : name;
    }

    private static Set<AuthenticatorTransport> transports(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(csv.split(",")).filter(s -> !s.isBlank()).map(AuthenticatorTransport::create).collect(Collectors.toSet());
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
