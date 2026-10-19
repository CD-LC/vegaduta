package com.agenticai.core.admin.localauth;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence of local-mode accounts, refresh tokens and setup tokens. An interface so the session
 * and account logic is unit-tested against an in-memory fake; the one production implementation is
 * {@link JdbcLocalAccountStore}.
 *
 * <p>Tokens are only ever stored as SHA-256 hashes ({@link LocalTokens#hash}); nothing here sees a
 * raw token except as the argument to be hashed by the caller.
 */
public interface LocalAccountStore {

    /** A refresh token row. */
    record RefreshRecord(
            String id,
            String accountId,
            String familyId,
            String tokenHash,
            String authMethod,
            Instant authTime,
            Instant issuedAt,
            Instant expiresAt,
            Instant familyExpiresAt,
            Instant usedAt,
            Instant revokedAt) {}

    // ---- accounts

    long countAccounts();

    boolean anySetupPending();

    Optional<LocalAccount> findById(String id);

    /** Case-insensitive lookup by e-mail address. */
    Optional<LocalAccount> findByEmail(String email);

    List<LocalAccount> listAll();

    List<LocalAccount> listByTenant(String tenantId);

    /** Inserts a new account. Throws {@link DuplicateAccountException} when the e-mail is taken. */
    void insert(LocalAccount account);

    /** Overwrites every mutable column of the account with the given values. */
    void update(LocalAccount account);

    void delete(String id);

    void touchLogin(String id, Instant at);

    // ---- refresh tokens

    void insertRefresh(RefreshRecord record);

    Optional<RefreshRecord> findRefreshByHash(String tokenHash);

    /** Marks the token used; true only for the single caller that got there first. */
    boolean claimRefresh(String id, Instant at);

    void revokeFamily(String familyId, Instant at);

    void revokeAllForAccount(String accountId, Instant at);

    /** Like {@link #revokeAllForAccount} but leaves the login {@code keepFamilyId} (the caller's own) alone. */
    void revokeAllForAccountExcept(String accountId, String keepFamilyId, Instant at);

    /** Deletes refresh rows that expired before {@code cutoff}; returns how many. */
    int purgeRefreshExpiredBefore(Instant cutoff);

    // ---- setup tokens

    /** Stores a new setup token and invalidates the account's earlier unconsumed ones. */
    void insertSetupToken(String accountId, String tokenHash, Instant createdAt, Instant expiresAt);

    /** The account an unexpired, unconsumed setup token belongs to, without consuming it. */
    Optional<String> findSetupAccount(String tokenHash, Instant now);

    /** Atomically consumes an unexpired, unconsumed setup token; returns its account id. */
    Optional<String> consumeSetupToken(String tokenHash, Instant now);

    /** Thrown by {@link #insert} when the e-mail address is already registered. */
    class DuplicateAccountException extends RuntimeException {
        public DuplicateAccountException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
