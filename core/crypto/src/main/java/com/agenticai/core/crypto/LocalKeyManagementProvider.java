package com.agenticai.core.crypto;

import jakarta.annotation.PostConstruct;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Default {@link KeyManagementProvider} - Master-Roadmap-2026.md item #6
 * (KMS-backed envelope encryption for BYOK keys), Phase 1. Every currently
 * running deployment of this platform is self-hosted (docker-compose, no
 * cloud KMS integration decided yet - see {@link EnvelopeEncryptionService}'s
 * javadoc), so this provider's "KMS" is simply the same {@code
 * APP_ENCRYPTION_KEY} env var {@link com.agenticai.core.crypto.EncryptionService}
 * already uses, wrapping each per-secret data key with AES-256-GCM instead of
 * a real cloud KMS API call.
 *
 * <p>This is still a genuine security improvement over the pre-#6 world
 * ({@code EncryptionService} encrypting every secret directly under one
 * static key): each secret now gets its own randomly generated data key, so
 * compromising one wrapped-key record does not expose any other secret's
 * plaintext, and the blast radius of a full KEK compromise is unchanged
 * (attacker still needs the KEK either way) while the blast radius of a
 * single leaked ciphertext-plus-wrapped-key pair is unaffected by every
 * OTHER secret sharing the same static key today.
 *
 * <p>Deliberately reads its own {@code app.encryption.key} property (the
 * same one {@code EncryptionService} reads) rather than depending on {@code
 * EncryptionService} directly - {@code EncryptionService} doesn't expose its
 * parsed key, and duplicating ~15 lines of key-parsing here keeps this class
 * a self-contained, swappable {@link KeyManagementProvider} implementation
 * exactly like a future cloud-KMS implementation would be, rather than
 * accidentally coupling the interface's only implementation today to a
 * concrete sibling class's internals.
 *
 * <p><b>Swapping in a real cloud KMS later:</b> implement {@link
 * KeyManagementProvider} against AWS KMS / GCP Cloud KMS / Azure Key Vault
 * (one {@code GenerateDataKey}/{@code Encrypt} call per {@link
 * #generateDataKey()}, one {@code Decrypt} call per {@link
 * #unwrapDataKey}), give it a distinct {@link #providerId()}, and make it
 * the primary {@code @Component} (e.g. behind a {@code
 * @ConditionalOnProperty}) - {@link EnvelopeEncryptionService} depends only
 * on the {@link KeyManagementProvider} interface, so no caller-side change
 * is needed. Already-wrapped keys from this local provider keep decrypting
 * correctly as long as this class (or an equivalent reading the same env
 * var) stays registered for the {@code "local"} {@link #providerId()}.
 */
@Component
public class LocalKeyManagementProvider implements KeyManagementProvider {

    public static final String PROVIDER_ID = "local";
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int REQUIRED_KEY_LENGTH_BYTES = 32; // AES-256
    private static final int DATA_KEY_LENGTH_BYTES = 32; // AES-256

    private final SecureRandom secureRandom = new SecureRandom();
    private final String rawKeyProperty;
    private SecretKeySpec kek;

    public LocalKeyManagementProvider(@Value("${app.encryption.key:}") String rawKeyProperty) {
        this.rawKeyProperty = rawKeyProperty;
    }

    @PostConstruct
    void init() {
        if (!StringUtils.hasText(rawKeyProperty)) {
            throw new IllegalStateException(
                "APP_ENCRYPTION_KEY is not set. Generate one with `openssl rand -base64 32` "
                    + "and set it as an environment variable before starting the app. "
                    + "LocalKeyManagementProvider uses this same key as its key-encryption "
                    + "key (KEK) to wrap per-secret data keys.");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(rawKeyProperty);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("APP_ENCRYPTION_KEY must be valid base64.", e);
        }
        if (decoded.length != REQUIRED_KEY_LENGTH_BYTES) {
            throw new IllegalStateException(
                "APP_ENCRYPTION_KEY must decode to exactly 32 bytes (AES-256). Got "
                    + decoded.length + " bytes.");
        }
        this.kek = new SecretKeySpec(decoded, "AES");
    }

    @Override
    public WrappedDataKey generateDataKey() {
        byte[] plaintextKey = new byte[DATA_KEY_LENGTH_BYTES];
        secureRandom.nextBytes(plaintextKey);
        byte[] wrappedKey = wrap(plaintextKey);
        return new WrappedDataKey(plaintextKey, wrappedKey, PROVIDER_ID);
    }

    @Override
    public byte[] unwrapDataKey(byte[] wrappedKey) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            byte[] ciphertext = new byte[wrappedKey.length - GCM_IV_LENGTH_BYTES];
            System.arraycopy(wrappedKey, 0, iv, 0, iv.length);
            System.arraycopy(wrappedKey, iv.length, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, kek, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to unwrap data key", e);
        }
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    private byte[] wrap(byte[] plaintextKey) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, kek, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] wrapped = cipher.doFinal(plaintextKey);

            byte[] combined = new byte[iv.length + wrapped.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(wrapped, 0, combined, iv.length, wrapped.length);
            return combined;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to wrap data key", e);
        }
    }
}
