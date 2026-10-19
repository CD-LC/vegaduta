package com.agenticai.core.admin.localauth;

import com.agenticai.core.admin.localauth.LocalAuthService.SetupTicket;
import com.agenticai.core.admin.localauth.LocalCredentialStore.DeviceRecord;
import com.agenticai.core.admin.localauth.LocalCredentialStore.PasskeyRecord;
import com.agenticai.core.admin.localauth.LocalSessionService.Session;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

/**
 * Everything behind {@code /api/auth/local/unlock} that is not a plain sign-in: listing and changing
 * the ways an account can sign in, and the passkey ceremonies that may run either for a signed-in
 * person or, during first-run/invite setup, for someone who only holds a setup token.
 *
 * <p><b>Freshness.</b> Adding or removing a way to sign in, and regenerating recovery codes, need a
 * sign-in no older than {@link #FRESH_FOR}: a stolen but still valid access token (or an unlocked
 * laptop) cannot quietly enrol an attacker's passkey. The check reads the token's {@code auth_time},
 * which refreshing preserves, so only signing in again renews it; the answer is {@code 403
 * reauth_required}.
 *
 * <p><b>Removal ends other logins.</b> Removing a PIN device or passkey, or regenerating the recovery
 * codes, signs out every other login of the account (the caller keeps theirs, identified by the
 * token's {@code sid}): a session that credential started must not outlive it.
 *
 * <p><b>Never lock out.</b> Removing the last way to sign in (no password, no other passkey, no
 * other active device) is refused with {@code 409 last_credential}.
 */
@Service
@ConditionalOnProperty(name = "app.auth.mode", havingValue = "local")
public class LocalUnlockService {

    private static final Logger log = LoggerFactory.getLogger(LocalUnlockService.class);

    static final Duration FRESH_FOR = Duration.ofMinutes(10);

    public record PasskeyView(String id, String label, String createdAt, String lastUsedAt, boolean backedUp) {}

    public record DeviceView(String id, String label, String createdAt, String lastUsedAt) {}

    public record Methods(boolean hasPassword, List<PasskeyView> passkeys, List<DeviceView> devices, int recoveryCodesRemaining) {}

    /** {@code session} and {@code recoveryCodes} are set only when a setup token was used. */
    public record Registered(PasskeyView passkey, Session session, List<String> recoveryCodes) {}

    private final LocalAccountStore accounts;
    private final LocalCredentialStore credentials;
    private final LocalPasskeyService passkeys;
    private final LocalDevices devices;
    private final LocalRecoveryCodes recoveryCodes;
    private final LocalAuthService auth;
    private final Clock clock;

    @Autowired
    public LocalUnlockService(
            LocalAccountStore accounts,
            LocalCredentialStore credentials,
            LocalPasskeyService passkeys,
            LocalDevices devices,
            LocalRecoveryCodes recoveryCodes,
            LocalAuthService auth) {
        this(accounts, credentials, passkeys, devices, recoveryCodes, auth, Clock.systemUTC());
    }

    LocalUnlockService(
            LocalAccountStore accounts,
            LocalCredentialStore credentials,
            LocalPasskeyService passkeys,
            LocalDevices devices,
            LocalRecoveryCodes recoveryCodes,
            LocalAuthService auth,
            Clock clock) {
        this.accounts = accounts;
        this.credentials = credentials;
        this.passkeys = passkeys;
        this.devices = devices;
        this.recoveryCodes = recoveryCodes;
        this.auth = auth;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ reads

    public Methods methods(Jwt jwt) {
        LocalAccount account = account(jwt);
        return new Methods(
                hasUsablePassword(account),
                credentials.listPasskeys(account.id()).stream().map(LocalUnlockService::view).toList(),
                devices.active(account.id()).stream().map(LocalUnlockService::view).toList(),
                recoveryCodes.remaining(account.id()));
    }

    // ------------------------------------------------------------------ devices and recovery codes (FRESH)

    public LocalDevices.Enrolled enrolDevice(Jwt jwt, String pin, String label) {
        LocalAccount account = freshAccount(jwt);
        LocalDevices.Enrolled enrolled = devices.enrol(account.id(), pin, label);
        log.info("Local auth: account {} added a PIN device", account.id());
        return enrolled;
    }

    public void removeDevice(Jwt jwt, String deviceId) {
        LocalAccount account = freshAccount(jwt);
        String id = requireUuid(deviceId);
        List<DeviceRecord> active = devices.active(account.id());
        if (active.stream().noneMatch(d -> d.id().equals(id))) {
            throw LocalAuthException.notFound();
        }
        if (!hasAnotherWay(account, !credentials.listPasskeys(account.id()).isEmpty(), active.size() > 1)) {
            throw LocalAuthException.lastCredential();
        }
        if (!devices.revoke(account.id(), id)) {
            throw LocalAuthException.notFound();
        }
        auth.endOtherSessions(account.id(), sid(jwt));
        log.info("Local auth: account {} removed a PIN device", account.id());
    }

    public void removePasskey(Jwt jwt, String passkeyId) {
        LocalAccount account = freshAccount(jwt);
        String id = requireUuid(passkeyId);
        List<PasskeyRecord> all = credentials.listPasskeys(account.id());
        if (all.stream().noneMatch(p -> p.id().equals(id))) {
            throw LocalAuthException.notFound();
        }
        boolean otherPasskey = all.size() > 1;
        if (!hasAnotherWay(account, otherPasskey, !devices.active(account.id()).isEmpty())) {
            throw LocalAuthException.lastCredential();
        }
        if (!credentials.deletePasskey(account.id(), id)) {
            throw LocalAuthException.notFound();
        }
        auth.endOtherSessions(account.id(), sid(jwt));
        log.info("Local auth: account {} removed a passkey", account.id());
    }

    public List<String> regenerateRecoveryCodes(Jwt jwt) {
        LocalAccount account = freshAccount(jwt);
        List<String> codes = recoveryCodes.regenerate(account.id());
        auth.endOtherSessions(account.id(), sid(jwt));
        log.info("Local auth: account {} regenerated its recovery codes", account.id());
        return codes;
    }

    // ------------------------------------------------------------------ passkey ceremonies

    public LocalPasskeyService.Options loginOptions(String originHeader) {
        return passkeys.loginOptions(originHeader);
    }

    /**
     * Registration options for the signed-in person (fresh sign-in required) or, when {@code jwt} is
     * null, for the account a still-unconsumed setup token belongs to.
     */
    public LocalPasskeyService.Options registrationOptions(Jwt jwt, String setupToken, String originHeader, String address) {
        if (jwt != null) {
            return passkeys.registrationOptions(freshAccount(jwt), originHeader, true);
        }
        SetupTicket ticket = auth.checkSetupToken(setupToken, address);
        return passkeys.registrationOptions(ticket.account(), originHeader, false);
    }

    /**
     * Verifies and stores a registration. On the setup-token path the token is consumed only AFTER
     * the credential verified and was stored; if consuming then fails the credential is removed again.
     */
    public Registered register(Jwt jwt, String challengeId, String credentialJson, String label, String setupToken, String address) {
        if (jwt != null) {
            LocalAccount account = freshAccount(jwt);
            PasskeyRecord stored = passkeys.register(account, challengeId, credentialJson, label);
            log.info("Local auth: account {} added a passkey", account.id());
            return new Registered(view(stored), null, null);
        }
        SetupTicket ticket = auth.checkSetupToken(setupToken, address);
        PasskeyRecord stored = passkeys.register(ticket.account(), challengeId, credentialJson, label);
        Session session;
        try {
            session = auth.finishSetup(ticket, "hwk", address);
        } catch (RuntimeException e) {
            credentials.deletePasskey(ticket.account().id(), stored.id());
            throw e;
        }
        return new Registered(view(stored), session, recoveryCodes.regenerate(ticket.account().id()));
    }

    // ------------------------------------------------------------------ identity and freshness

    /** The enabled account the access token belongs to. */
    LocalAccount account(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) {
            throw LocalAuthException.invalidSession();
        }
        return accounts.findById(jwt.getSubject()).filter(LocalAccount::enabled).orElseThrow(LocalAuthException::invalidSession);
    }

    /** Like {@link #account} but the sign-in must be recent: {@code 403 reauth_required} otherwise. */
    LocalAccount freshAccount(Jwt jwt) {
        LocalAccount account = account(jwt);
        Instant authTime = authTime(jwt);
        if (authTime == null || authTime.plus(FRESH_FOR).isBefore(clock.instant())) {
            throw LocalAuthException.reauthRequired();
        }
        return account;
    }

    /** The login (refresh family) the access token belongs to. */
    private static String sid(Jwt jwt) {
        return jwt.getClaimAsString("sid");
    }

    private static Instant authTime(Jwt jwt) {
        Object claim = jwt.getClaim("auth_time");
        if (claim instanceof Number n) {
            return Instant.ofEpochSecond(n.longValue());
        }
        if (claim instanceof Instant i) {
            return i;
        }
        return null;
    }

    private static boolean hasAnotherWay(LocalAccount account, boolean otherPasskey, boolean otherDevice) {
        return hasUsablePassword(account) || otherPasskey || otherDevice;
    }

    /** A stored hash only counts while the account can sign in with it (a pending setup cannot). */
    private static boolean hasUsablePassword(LocalAccount account) {
        return account.passwordHash() != null && !account.setupPending();
    }

    private static String requireUuid(String id) {
        try {
            return UUID.fromString(id).toString();
        } catch (IllegalArgumentException e) {
            throw LocalAuthException.notFound();
        }
    }

    private static PasskeyView view(PasskeyRecord p) {
        return new PasskeyView(p.id(), p.label(), p.createdAt().toString(), p.lastUsedAt() == null ? null : p.lastUsedAt().toString(), p.backupState());
    }

    private static DeviceView view(DeviceRecord d) {
        return new DeviceView(d.id(), d.label(), d.createdAt().toString(), d.lastUsedAt() == null ? null : d.lastUsedAt().toString());
    }
}
