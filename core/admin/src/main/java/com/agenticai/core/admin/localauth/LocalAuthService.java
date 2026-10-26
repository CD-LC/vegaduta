package com.agenticai.core.admin.localauth;

import com.agenticai.core.admin.localauth.LocalSessionService.Session;
import com.agenticai.core.security.localauth.LocalAuthSettings;
import com.agenticai.core.security.localauth.LocalCredentialVerifier;
import com.agenticai.core.security.localauth.LoginThrottle;
import com.agenticai.core.security.publicurl.PublicWebUrl;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * The operations behind {@code /api/auth/local/*}: sign in, first-run / invite setup, password
 * change, and the public status the sign-in page reads.
 *
 * <p>Every credential check goes through {@link LoginThrottle} first (so a locked-out caller costs
 * no hashing work) and a failure of ANY kind - unknown account, wrong password, disabled account -
 * is the same {@code invalid_credentials} answer. Credentials are never logged.
 */
@Service
@ConditionalOnProperty(name = "app.auth.mode", havingValue = "local")
public class LocalAuthService {

    private static final Logger log = LoggerFactory.getLogger(LocalAuthService.class);

    private static final List<String> DISPLAY_ORDER =
            List.of("password", "pin", "passkey", "recovery");

    /** What the sign-in page needs before anyone is signed in. */
    public record Status(
            boolean setupRequired, List<String> credentialTypes, int passwordMinLength) {}

    private final LocalAccountStore store;
    private final LocalSessionService sessions;
    private final LocalPasswords passwords;
    private final LoginThrottle throttle;
    private final LocalAuthSettings settings;
    private final Map<String, LocalCredentialVerifier> verifiers = new LinkedHashMap<>();
    private final LocalDevices devices;
    private final LocalRecoveryCodes recoveryCodes;
    private final Clock clock;
    private final String publicWebUrl;

    @Autowired
    public LocalAuthService(
            LocalAccountStore store,
            LocalSessionService sessions,
            LocalPasswords passwords,
            LoginThrottle throttle,
            LocalAuthSettings settings,
            List<LocalCredentialVerifier> credentialVerifiers,
            LocalDevices devices,
            LocalRecoveryCodes recoveryCodes,
            @org.springframework.beans.factory.annotation.Value("${app.web.public-url:}")
                    String publicWebUrl) {
        this(
                store,
                sessions,
                passwords,
                throttle,
                settings,
                credentialVerifiers,
                devices,
                recoveryCodes,
                Clock.systemUTC(),
                publicWebUrl);
    }

    /** Test convenience: no public URL, so a reset link prints as a path only. */
    LocalAuthService(
            LocalAccountStore store,
            LocalSessionService sessions,
            LocalPasswords passwords,
            LoginThrottle throttle,
            LocalAuthSettings settings,
            List<LocalCredentialVerifier> credentialVerifiers,
            LocalDevices devices,
            LocalRecoveryCodes recoveryCodes) {
        this(
                store,
                sessions,
                passwords,
                throttle,
                settings,
                credentialVerifiers,
                devices,
                recoveryCodes,
                Clock.systemUTC(),
                "");
    }

    LocalAuthService(
            LocalAccountStore store,
            LocalSessionService sessions,
            LocalPasswords passwords,
            LoginThrottle throttle,
            LocalAuthSettings settings,
            List<LocalCredentialVerifier> credentialVerifiers,
            LocalDevices devices,
            LocalRecoveryCodes recoveryCodes,
            Clock clock) {
        this(
                store,
                sessions,
                passwords,
                throttle,
                settings,
                credentialVerifiers,
                devices,
                recoveryCodes,
                clock,
                "");
    }

    LocalAuthService(
            LocalAccountStore store,
            LocalSessionService sessions,
            LocalPasswords passwords,
            LoginThrottle throttle,
            LocalAuthSettings settings,
            List<LocalCredentialVerifier> credentialVerifiers,
            LocalDevices devices,
            LocalRecoveryCodes recoveryCodes,
            Clock clock,
            String publicWebUrl) {
        this.store = store;
        this.sessions = sessions;
        this.passwords = passwords;
        this.throttle = throttle;
        this.settings = settings;
        this.devices = devices;
        this.recoveryCodes = recoveryCodes;
        this.clock = clock;
        this.publicWebUrl = publicWebUrl;
        // Listed in a fixed order so the sign-in page sees the same credential types every boot.
        List<LocalCredentialVerifier> ordered = new ArrayList<>(credentialVerifiers);
        ordered.sort(
                Comparator.comparingInt(
                        v -> {
                            int rank = DISPLAY_ORDER.indexOf(v.type());
                            return rank < 0 ? DISPLAY_ORDER.size() : rank;
                        }));
        for (LocalCredentialVerifier verifier : ordered) {
            verifiers.put(verifier.type(), verifier);
        }
    }

    public Status status() {
        return new Status(
                store.anySetupPending(),
                List.copyOf(verifiers.keySet()),
                LocalPasswords.MIN_LENGTH);
    }

    /** Signs in with {@code credentialType} (default {@code password}). */
    public Session login(
            String credentialType,
            String identifier,
            Map<String, String> credentials,
            String address) {
        String type =
                credentialType == null || credentialType.isBlank()
                        ? LocalPasswordVerifier.TYPE
                        : credentialType;
        LocalCredentialVerifier verifier = verifiers.get(type);
        // A recovery code names no account (the identifier is a fixed word the verifier ignores),
        // so
        // an account-wide bucket keyed on it would be one bucket for everybody: any anonymous
        // caller
        // could keep the owner's printed codes locked. Throttle it per address instead; the code
        // space (80 bits) makes guessing irrelevant, the bucket only slows one client down.
        boolean recovery = LocalRecoveryVerifier.TYPE.equals(type);
        String id =
                recovery
                        ? "recovery:" + address
                        : identifier == null ? "" : identifier.trim().toLowerCase(Locale.ROOT);
        if (verifier == null || id.isEmpty()) {
            throw LocalAuthException.invalidCredentials();
        }
        LoginThrottle.LoginDecision gate = throttle.checkLogin(address, id);
        if (!gate.allowed()) {
            throw LocalAuthException.throttled(gate.retryAfterSeconds());
        }

        String accountId = verifier.verify(id, credentials).orElse(null);
        LocalAccount account = accountId == null ? null : store.findById(accountId).orElse(null);
        if (account == null || !account.enabled()) {
            if (gate.exempt()) {
                // A lock is on and this address is only being allowed to prove itself: a wrong
                // credential is answered as throttled and NOT counted, so guessing cannot extend
                // it.
                throw LocalAuthException.throttled(gate.retryAfterSeconds());
            }
            throttle.recordFailure(address, id);
            throw LocalAuthException.invalidCredentials();
        }
        throttle.recordSuccess(address, id);
        return sessions.start(account, verifier.authenticationMethod());
    }

    /** A setup token that has been checked (and not yet consumed) and the account it belongs to. */
    record SetupTicket(String tokenHash, LocalAccount account) {}

    /**
     * What a PIN setup hands back: the session, the device binding to keep, and the recovery codes.
     */
    public record PinSetup(
            Session session, String deviceId, String deviceSecret, List<String> recoveryCodes) {}

    /** Sets the first password of an account from a one-time setup token and signs it in. */
    public Session completeSetup(String setupToken, String newPassword, String address) {
        SetupTicket ticket = checkSetupToken(setupToken, address);
        // Checked BEFORE the token is consumed, so a too-short password costs a retry, not the
        // link.
        String violation = LocalPasswords.violation(newPassword, ticket.account().email());
        if (violation != null) {
            throw LocalAuthException.weakPassword(violation);
        }
        LocalAccount account = ticket.account();
        return consumeAndStart(
                ticket,
                account.withPassword(passwords.hash(newPassword), false, false),
                "pwd",
                address);
    }

    /**
     * Completes setup WITHOUT a password: the account gets a PIN on a new device binding instead,
     * and its {@code password_hash} stays null. The PIN policy is checked before the token is
     * consumed; recovery codes are generated here because this person has no other way back in.
     */
    public PinSetup completeSetupWithPin(
            String setupToken, String pin, String label, String address) {
        SetupTicket ticket = checkSetupToken(setupToken, address);
        LocalDevices.Enrolled device = devices.enrol(ticket.account().id(), pin, label);
        Session session;
        try {
            session = finishSetup(ticket, "pin", address);
        } catch (RuntimeException e) {
            devices.revoke(ticket.account().id(), device.deviceId());
            throw e;
        }
        List<String> codes = recoveryCodes.regenerate(ticket.account().id());
        return new PinSetup(session, device.deviceId(), device.deviceSecret(), codes);
    }

    /**
     * Resolves a setup token to its account WITHOUT consuming it, so a credential can be verified
     * first and a failed attempt costs a retry, not the link. Throttled per address (a shared
     * "setup" account bucket would let anyone who can reach the port lock the owner out of
     * finishing setup with a handful of junk tokens).
     */
    SetupTicket checkSetupToken(String setupToken, String address) {
        String key = "setup:" + address;
        requireNotThrottled(address, key);
        String hash =
                setupToken == null || !setupToken.startsWith(LocalTokens.SETUP_PREFIX)
                        ? null
                        : LocalTokens.hash(setupToken);
        LocalAccount account =
                hash == null
                        ? null
                        : store.findSetupAccount(hash, clock.instant())
                                .flatMap(store::findById)
                                .orElse(null);
        if (account == null) {
            throttle.recordFailure(address, key);
            throw LocalAuthException.invalidSetupToken();
        }
        return new SetupTicket(hash, account);
    }

    /**
     * Finishes a setup whose credential (passkey, PIN) is already verified and stored: consumes the
     * token, clears the pending flag, ends older sessions and signs in with {@code authMethod}. The
     * account ends with NO password: whatever hash it held (an admin-chosen temporary password, the
     * unknown random one of the first-run owner, the password a reset link was minted to replace)
     * is dropped, so a password that someone else knows cannot outlive the setup, and "has a
     * password" means the person set one.
     */
    Session finishSetup(SetupTicket ticket, String authMethod, String address) {
        LocalAccount account = ticket.account();
        return consumeAndStart(
                ticket, account.withPassword(null, false, false), authMethod, address);
    }

    private Session consumeAndStart(
            SetupTicket ticket, LocalAccount updated, String authMethod, String address) {
        String key = "setup:" + address;
        if (store.consumeSetupToken(ticket.tokenHash(), clock.instant()).isEmpty()) {
            throttle.recordFailure(address, key);
            throw LocalAuthException.invalidSetupToken();
        }
        store.update(updated);
        sessions.revokeAll(updated.id());
        throttle.recordSuccess(address, key);
        log.info("Local auth: account {} completed setup", updated.id());
        removeSetupFile();
        return sessions.start(updated, authMethod);
    }

    /** Changes the password of a signed-in account after re-checking the current one. */
    public Session changePassword(
            String accountId, String currentPassword, String newPassword, String address) {
        LocalAccount account =
                store.findById(accountId).orElseThrow(LocalAuthException::invalidSession);
        String key = "pw:" + accountId;
        requireNotThrottled(address, key);
        if (!passwords.matches(currentPassword, account.passwordHash())) {
            throttle.recordFailure(address, key);
            throw new LocalAuthException(
                    403, "invalid_credentials", "The current password is incorrect.");
        }
        String violation = LocalPasswords.violation(newPassword, account.email());
        if (violation != null) {
            throw LocalAuthException.weakPassword(violation);
        }
        if (passwords.matches(newPassword, account.passwordHash())) {
            throw LocalAuthException.weakPassword(
                    "Choose a password you have not used for this account.");
        }
        throttle.recordSuccess(address, key);
        LocalAccount updated = account.withPassword(passwords.hash(newPassword), false, false);
        store.update(updated);
        // Every other login of this account ends; the caller continues on the new session.
        sessions.revokeAll(account.id());
        return sessions.start(updated, "pwd");
    }

    /**
     * Ends every login of {@code accountId} except the caller's own (the access token's {@code
     * sid}): a session a removed credential started must not outlive it.
     */
    void endOtherSessions(String accountId, String keepFamilyId) {
        sessions.revokeAllExcept(accountId, keepFamilyId);
    }

    /**
     * A locked-out person asks for a password reset. The request is anonymous, so the link is never
     * returned over the wire - it is written to {@code <data-dir>/password-reset.txt} (mode 0600)
     * and logged, the same proof-of-machine-access model as the first-run setup link. The file
     * carries a one-time setup token, so the existing {@code /login?setup=TOKEN} flow sets a new
     * password and signs in. A blank e-mail resolves to the install's only account, which is the
     * common case on a personal install; the answer is generic either way so the endpoint does not
     * reveal which e-mail owns an account. Throttled per address so the file cannot be re-minted
     * without limit.
     */
    public void requestPasswordReset(String email, String address) {
        String key = "reset:" + address;
        requireNotThrottled(address, key);
        throttle.recordFailure(address, key);
        LocalAccount account =
                email == null || email.isBlank()
                        ? store.listAll().stream()
                                .filter(LocalAccount::enabled)
                                .findFirst()
                                .orElse(null)
                        : store.findByEmail(email.trim().toLowerCase(Locale.ROOT))
                                .filter(LocalAccount::enabled)
                                .orElse(null);
        if (account == null) {
            return;
        }
        String token = issueSetupToken(account);
        String expiry = Instant.now().plus(settings.setupTokenTtl()).toString();
        String link = PublicWebUrl.normalize(publicWebUrl) + "/login?setup=" + token;
        Path file = settings.dataDir().resolve(LocalAuthSettings.RESET_FILE_NAME);
        writeResetFile(file, link, expiry);
        log.info(
                "Password reset requested for {} - a one-use reset link was saved to {} (expires {}).",
                account.email(),
                file,
                expiry);
    }

    private void writeResetFile(Path file, String link, String expiry) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = Files.createTempFile(file.getParent(), "reset-", ".tmp");
            try {
                Files.setPosixFilePermissions(
                        tmp,
                        Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException ignored) {
                // Not a POSIX file system; the parent dir's 0700 still restricts it to the owner.
            }
            Files.writeString(
                    tmp,
                    "Open this link to choose a new password (single use, expires "
                            + expiry
                            + "):\n"
                            + link
                            + "\n",
                    StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warn("Could not save the reset link to {}: {}", file, e.toString());
        }
    }

    /**
     * Creates a fresh one-time setup token for {@code account} (replacing any earlier one) and
     * returns the RAW token - the only moment it exists in plaintext.
     */
    public String issueSetupToken(LocalAccount account) {
        String token = LocalTokens.newSetupToken();
        Instant now = clock.instant();
        store.insertSetupToken(
                account.id(), LocalTokens.hash(token), now, now.plus(settings.setupTokenTtl()));
        return token;
    }

    /** The printed first-run link is dead once used; do not leave it lying around. */
    private void removeSetupFile() {
        try {
            Files.deleteIfExists(settings.dataDir().resolve(LocalAuthSettings.SETUP_FILE_NAME));
        } catch (IOException | RuntimeException e) {
            log.debug("Could not remove the setup file: {}", e.toString());
        }
    }

    private void requireNotThrottled(String address, String key) {
        LoginThrottle.Decision decision = throttle.check(address, key);
        if (!decision.allowed()) {
            throw LocalAuthException.throttled(decision.retryAfterSeconds());
        }
    }
}
