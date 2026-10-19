package com.agenticai.core.crypto;

/**
 * Result of {@link KeyManagementProvider#generateDataKey()} - see that
 * method's javadoc. {@code plaintextKey} must never be persisted or logged;
 * only {@code wrappedKey} (and {@code providerId}, so the wrap can later be
 * routed back to the provider that produced it) is safe to store alongside
 * a secret's ciphertext.
 */
public record WrappedDataKey(byte[] plaintextKey, byte[] wrappedKey, String providerId) {
}
