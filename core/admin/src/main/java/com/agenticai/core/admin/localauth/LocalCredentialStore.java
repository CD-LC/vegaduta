package com.agenticai.core.admin.localauth;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence of the sign-in methods other than a password: passkeys, device PINs and recovery
 * codes (tables from migration V655). An interface so the logic is unit-tested against an
 * in-memory fake; the one production implementation is {@link JdbcLocalCredentialStore}.
 *
 * <p>Every operation is ONE statement, and the two that must be race-free (spending a recovery
 * code, counting a wrong PIN) are each a single conditional UPDATE. Nothing here sees a raw code,
 * PIN or device secret: only hashes.
 */
public interface LocalCredentialStore {

    /** One WebAuthn credential. {@code credentialId} is base64url; the byte[] is webauthn4j's serialised form. */
    record PasskeyRecord(
            String id,
            String accountId,
            String credentialId,
            byte[] attestedCredentialData,
            long signCount,
            boolean uvInitialized,
            boolean backupEligible,
            boolean backupState,
            String transports,
            String rpId,
            String label,
            Instant createdAt,
            Instant lastUsedAt) {}

    /** One browser/device binding for PIN sign-in. */
    record DeviceRecord(
            String id,
            String accountId,
            String label,
            String pinHash,
            int failedAttempts,
            Instant createdAt,
            Instant lastUsedAt,
            Instant revokedAt) {}

    // ---- passkeys

    /** Throws {@link DuplicateCredentialException} when the credential id is already registered. */
    void insertPasskey(PasskeyRecord passkey);

    Optional<PasskeyRecord> findPasskeyByCredentialId(String credentialId);

    List<PasskeyRecord> listPasskeys(String accountId);

    /**
     * Records a successful assertion: new counter, backup state, last-used time. Returns false (and
     * changes nothing) when the stored counter is already above {@code signCount}, which means a
     * concurrent or replayed use got there first.
     */
    boolean recordPasskeyUse(String id, long signCount, boolean backupState, Instant at);

    /** Deletes the passkey if it belongs to the account; true when a row was removed. */
    boolean deletePasskey(String accountId, String id);

    // ---- devices

    void insertDevice(DeviceRecord device);

    Optional<DeviceRecord> findDevice(String id);

    /** Devices of the account that are not revoked, oldest first. */
    List<DeviceRecord> listActiveDevices(String accountId);

    /** Revokes the device if it belongs to the account and is active; true when it changed. */
    boolean revokeDevice(String accountId, String id, Instant at);

    /**
     * Counts one wrong PIN against an ACTIVE device and revokes it when the count reaches {@code
     * maxFailures}. Returns the new count, or 0 when the device is unknown or already revoked.
     */
    int recordDeviceFailure(String id, int maxFailures, Instant at);

    /**
     * A correct PIN: resets the consecutive-failure counter and stamps the last-used time. Returns
     * false when the device is unknown or was revoked in the meantime, in which case the sign-in
     * must be refused (the caller's read of the row may be older than a concurrent revocation).
     */
    boolean recordDeviceSuccess(String id, Instant at);

    // ---- recovery codes

    /** Atomically replaces every recovery code of the account with {@code codeHashes}. */
    void replaceRecoveryCodes(String accountId, List<String> codeHashes, Instant at);

    /** Spends an unused code; returns its account id for the single caller that got there first. */
    Optional<String> consumeRecoveryCode(String codeHash, Instant at);

    int countUnusedRecoveryCodes(String accountId);

    /** Thrown by {@link #insertPasskey} when the credential id is already registered. */
    class DuplicateCredentialException extends RuntimeException {
        public DuplicateCredentialException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
