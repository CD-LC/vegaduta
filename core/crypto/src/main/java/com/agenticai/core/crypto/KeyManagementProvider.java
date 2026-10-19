package com.agenticai.core.crypto;

/**
 * Tech-Stack-Audit-2026.md P2.2 / Master-Roadmap-2026.md item #6 (KMS-backed
 * envelope encryption for BYOK keys). This is the seam a real cloud KMS
 * (AWS KMS, GCP Cloud KMS, Azure Key Vault) plugs into later - see {@link
 * EnvelopeEncryptionService}'s javadoc for the full design rationale and why
 * this ships with only {@link LocalKeyManagementProvider} today.
 *
 * <p>Deliberately the narrowest possible interface: "wrap a data key" and
 * "unwrap a data key". It does NOT expose raw encrypt/decrypt-of-arbitrary-
 * data, because that is exactly what real KMS services are usually NOT used
 * for directly (they're slow/rate-limited/expensive per call compared to
 * local AES) - the standard envelope-encryption pattern this interface
 * supports calls the KMS only once per secret (to wrap/unwrap a small
 * per-secret data key), while the actual bulk encryption of the secret
 * itself always happens locally with that unwrapped key. A cloud KMS
 * implementation of this interface is therefore a thin adapter: {@code
 * wrapDataKey} -&gt; one KMS Encrypt API call, {@code unwrapDataKey} -&gt; one
 * KMS Decrypt API call.
 */
public interface KeyManagementProvider {

    /**
     * Generates a fresh random data-encryption key (DEK) and returns it
     * alongside that same key wrapped (encrypted) by this provider's own
     * key-encryption key (KEK). Callers use {@link WrappedDataKey#plaintextKey()}
     * immediately to encrypt the actual secret, then persist only {@link
     * WrappedDataKey#wrappedKey()} - the plaintext DEK itself is never
     * stored, only ever held in memory for the duration of one encrypt call.
     */
    WrappedDataKey generateDataKey();

    /**
     * Reverses {@link #generateDataKey()}: given a previously wrapped DEK,
     * returns the plaintext DEK so the caller can decrypt the secret it was
     * originally used to encrypt.
     */
    byte[] unwrapDataKey(byte[] wrappedKey);

    /**
     * A short, stable identifier for which provider/key produced a given
     * wrapped DEK (e.g. {@code "local"}, or a cloud KMS key ARN/resource id).
     * Persisted alongside the wrapped key so a future multi-provider
     * deployment (e.g. mid-migration from local to a cloud KMS) can route
     * {@link #unwrapDataKey} to the correct provider instance rather than
     * assuming there is only ever one.
     */
    String providerId();
}
