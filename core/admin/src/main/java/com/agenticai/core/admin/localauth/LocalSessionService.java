package com.agenticai.core.admin.localauth;

import com.agenticai.core.admin.localauth.LocalAccountStore.RefreshRecord;
import com.agenticai.core.security.localauth.LocalAuthSettings;
import com.agenticai.core.security.localauth.LocalJwtService;
import com.agenticai.core.security.localauth.LocalJwtService.AccessTokenRequest;
import com.agenticai.core.security.localauth.LocalJwtService.IssuedAccessToken;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Login sessions of local mode: a short-lived access JWT plus a rotating opaque refresh token.
 *
 * <p><b>Rotation and theft detection.</b> Every refresh consumes the presented token and returns a
 * new one in the same family. Presenting a token that was already consumed means two parties hold
 * it, so the whole family (the login) is revoked. A second use within {@link #REUSE_GRACE} of the
 * first is treated as a benign double-submit (two tabs, a retried request): it is refused but does
 * not revoke, so a refresh race never signs the owner out.
 *
 * <p><b>Lifetime.</b> Each refresh token lives {@code refresh-token-ttl} from its issue, and no
 * longer than the login's absolute {@code session-max-ttl}, which refreshing never extends. The
 * access token is re-minted from the CURRENT account row on every refresh, so a role change or a
 * disabled account takes effect within one access-token lifetime.
 */
@Service
@ConditionalOnProperty(name = "app.auth.mode", havingValue = "local")
public class LocalSessionService {

    private static final Logger log = LoggerFactory.getLogger(LocalSessionService.class);
    static final Duration REUSE_GRACE = Duration.ofSeconds(10);
    private static final Duration PURGE_EVERY = Duration.ofHours(1);

    /** What a successful sign-in or refresh hands back. */
    public record Session(
            String accessToken,
            Instant accessExpiresAt,
            String refreshToken,
            Instant refreshExpiresAt,
            LocalAccount account) {}

    private final LocalAccountStore store;
    private final LocalJwtService tokens;
    private final LocalAuthSettings settings;
    private final Clock clock;
    private final AtomicReference<Instant> lastPurge = new AtomicReference<>(Instant.EPOCH);

    @Autowired
    public LocalSessionService(
            LocalAccountStore store, LocalJwtService tokens, LocalAuthSettings settings) {
        this(store, tokens, settings, Clock.systemUTC());
    }

    LocalSessionService(
            LocalAccountStore store, LocalJwtService tokens, LocalAuthSettings settings, Clock clock) {
        this.store = store;
        this.tokens = tokens;
        this.settings = settings;
        this.clock = clock;
    }

    /** Starts a new login (new family) for an account whose credentials were just verified. */
    public Session start(LocalAccount account, String authMethod) {
        Instant now = clock.instant();
        purgeOccasionally(now);
        store.touchLogin(account.id(), now);
        return issue(account, UUID.randomUUID().toString(), authMethod, now, now.plus(settings.sessionMaxTtl()));
    }

    /** Rotates {@code presentedRefreshToken}; throws {@code invalid_session} when it cannot be used. */
    public Session refresh(String presentedRefreshToken) {
        if (presentedRefreshToken == null || !presentedRefreshToken.startsWith(LocalTokens.REFRESH_PREFIX)) {
            throw LocalAuthException.invalidSession();
        }
        Instant now = clock.instant();
        RefreshRecord row =
                store.findRefreshByHash(LocalTokens.hash(presentedRefreshToken))
                        .orElseThrow(LocalAuthException::invalidSession);

        if (row.revokedAt() != null) {
            throw LocalAuthException.invalidSession();
        }
        if (row.usedAt() != null) {
            if (row.usedAt().plus(REUSE_GRACE).isBefore(now)) {
                log.warn("Local auth: a used refresh token was presented again; revoking login {}", row.familyId());
                store.revokeFamily(row.familyId(), now);
            }
            throw LocalAuthException.invalidSession();
        }
        if (!row.expiresAt().isAfter(now) || !row.familyExpiresAt().isAfter(now)) {
            throw LocalAuthException.invalidSession();
        }
        LocalAccount account = store.findById(row.accountId()).orElse(null);
        if (account == null || !account.enabled() || account.setupPending()) {
            store.revokeFamily(row.familyId(), now);
            throw LocalAuthException.invalidSession();
        }
        if (!store.claimRefresh(row.id(), now)) {
            // Lost the race to a concurrent refresh with the same token.
            throw LocalAuthException.invalidSession();
        }
        return issue(account, row.familyId(), row.authMethod(), row.authTime(), row.familyExpiresAt());
    }

    /** Ends the login the token belongs to. Idempotent and silent about unknown tokens. */
    public void logout(String refreshToken) {
        if (refreshToken == null || !refreshToken.startsWith(LocalTokens.REFRESH_PREFIX)) {
            return;
        }
        store.findRefreshByHash(LocalTokens.hash(refreshToken))
                .ifPresent(row -> store.revokeFamily(row.familyId(), clock.instant()));
    }

    /** Ends every login of the account (password change, disable, admin sign-out). */
    public void revokeAll(String accountId) {
        store.revokeAllForAccount(accountId, clock.instant());
    }

    /**
     * Ends every login of the account except {@code keepFamilyId}, the caller's own (the access
     * token's {@code sid}). With no family given the caller's login ends too: failing closed.
     */
    public void revokeAllExcept(String accountId, String keepFamilyId) {
        if (keepFamilyId == null || keepFamilyId.isBlank()) {
            revokeAll(accountId);
            return;
        }
        store.revokeAllForAccountExcept(accountId, keepFamilyId, clock.instant());
    }

    private Session issue(LocalAccount account, String familyId, String authMethod, Instant authTime, Instant familyExpiresAt) {
        Instant now = clock.instant();
        IssuedAccessToken access =
                tokens.issue(
                        new AccessTokenRequest(
                                account.id(),
                                account.email(),
                                account.firstName(),
                                account.lastName(),
                                account.tenantId(),
                                account.roles(),
                                familyId,
                                authTime,
                                authMethod));
        String refreshToken = LocalTokens.newRefreshToken();
        Instant refreshExpiry = now.plus(settings.refreshTokenTtl());
        if (refreshExpiry.isAfter(familyExpiresAt)) {
            refreshExpiry = familyExpiresAt;
        }
        store.insertRefresh(
                new RefreshRecord(
                        UUID.randomUUID().toString(),
                        account.id(),
                        familyId,
                        LocalTokens.hash(refreshToken),
                        authMethod,
                        authTime,
                        now,
                        refreshExpiry,
                        familyExpiresAt,
                        null,
                        null));
        return new Session(access.token(), access.expiresAt(), refreshToken, refreshExpiry, account);
    }

    private void purgeOccasionally(Instant now) {
        Instant last = lastPurge.get();
        if (last.plus(PURGE_EVERY).isBefore(now) && lastPurge.compareAndSet(last, now)) {
            try {
                int removed = store.purgeRefreshExpiredBefore(now.minus(Duration.ofDays(1)));
                if (removed > 0) {
                    log.debug("Local auth: purged {} expired refresh tokens", removed);
                }
            } catch (RuntimeException e) {
                log.warn("Local auth: refresh-token purge failed: {}", e.toString());
            }
        }
    }
}
