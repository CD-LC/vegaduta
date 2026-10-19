package com.agenticai.core.crypto;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * AES-256-GCM encryption for secrets at rest (provider API keys, channel credentials, OAuth refresh
 * tokens - every {@code encryptionService.encrypt/ decrypt} call site across the codebase goes
 * through this one class, which is what makes an encryption-at-rest audit tractable: "is X
 * encrypted?" is answerable by grepping for this type rather than auditing each entity's
 * persistence code by hand).
 *
 * <p>Key comes from the {@code APP_ENCRYPTION_KEY} env var (base64-encoded, 32 raw bytes) — never
 * hardcoded, never logged. The app fails fast at startup if it's missing or the wrong length,
 * rather than silently storing plaintext or crashing later on first use.
 *
 * <p>Ciphertext layout: {@code base64(IV[12 bytes] || GCM(ciphertext + tag))}. A fresh random IV is
 * generated per encryption call — GCM must never reuse an IV with the same key.
 *
 * <h2>#815 Governance follow-up - key rotation</h2>
 *
 * <p>{@code encrypt()} always uses the current key ({@code APP_ENCRYPTION_KEY}). {@code decrypt()}
 * tries the current key first and, if that fails (the GCM auth tag won't verify against a
 * ciphertext written under a different key - that's the normal, expected failure mode, not
 * corruption), falls back to the optional previous key ({@code APP_ENCRYPTION_KEY_PREVIOUS}). This
 * is the standard dual-key rotation pattern and was deliberately chosen over a
 * versioned-ciphertext-prefix scheme: it requires zero ciphertext format change and therefore zero
 * migration of the ~15 credential tables already storing ciphertext under the pre-rotation format
 * (Twilio, Google, Sarvam, Meta, Slack, Discord, Telegram, email, X, MCP server env, every LLM
 * provider key - see this class's own callers).
 *
 * <p><b>Rotation runbook:</b> (1) generate a new key with {@code openssl rand -base64 32}; (2) set
 * {@code APP_ENCRYPTION_KEY_PREVIOUS} to the CURRENT (about-to-be-replaced) value of {@code
 * APP_ENCRYPTION_KEY}; (3) set {@code APP_ENCRYPTION_KEY} to the new value; (4) restart. From that
 * point, every new/updated secret is encrypted under the new key, and every not-yet-rewritten row
 * still decrypts via the previous-key fallback. Once confident every credential has been rewritten
 * at least once since the rotation (an admin can force this by re-saving each credential through
 * its normal update endpoint - no bulk re-encryption job exists here, an honest scope limit for
 * this pass), remove {@code APP_ENCRYPTION_KEY_PREVIOUS} and restart again to retire the old key
 * from memory entirely.
 */
@Component
public class EncryptionService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int REQUIRED_KEY_LENGTH_BYTES = 32; // AES-256

    private final SecureRandom secureRandom = new SecureRandom();
    private final @Nullable String rawKeyProperty;
    private final @Nullable String rawPreviousKeyProperty;
    private SecretKeySpec keySpec;

    /** Set only during a key-rotation window; null otherwise. */
    private @Nullable SecretKeySpec previousKeySpec;

    public EncryptionService(
            @Value("${app.encryption.key:}") @Nullable String rawKeyProperty,
            @Value("${app.encryption.key-previous:}") @Nullable String rawPreviousKeyProperty) {
        this.rawKeyProperty = rawKeyProperty;
        this.rawPreviousKeyProperty = rawPreviousKeyProperty;
    }

    @PostConstruct
    void init() {
        if (!StringUtils.hasText(rawKeyProperty)) {
            throw new IllegalStateException(
                    "APP_ENCRYPTION_KEY is not set. Generate one with `openssl rand -base64 32` "
                            + "and set it as an environment variable before starting the app. "
                            + "This key encrypts every stored LLM provider API key — the app "
                            + "refuses to start without it rather than falling back to plaintext.");
        }
        this.keySpec = parseKey(rawKeyProperty, "APP_ENCRYPTION_KEY");
        // Optional - only set during a key rotation window (see this class's
        // javadoc). Absent in the overwhelmingly common non-rotating case,
        // in which decrypt() behaves exactly as it always has.
        this.previousKeySpec =
                StringUtils.hasText(rawPreviousKeyProperty)
                        ? parseKey(rawPreviousKeyProperty, "APP_ENCRYPTION_KEY_PREVIOUS")
                        : null;
    }

    private SecretKeySpec parseKey(String rawKey, String envVarName) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(rawKey);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(envVarName + " must be valid base64.", e);
        }
        if (decoded.length != REQUIRED_KEY_LENGTH_BYTES) {
            throw new IllegalStateException(
                    envVarName
                            + " must decode to exactly 32 bytes (AES-256). Got "
                            + decoded.length
                            + " bytes. Generate one with `openssl rand -base64 32`.");
        }
        return new SecretKeySpec(decoded, "AES");
    }

    public String encrypt(String plaintext) {
        return encryptWith(plaintext, keySpec);
    }

    private String encryptWith(String plaintext, SecretKeySpec key) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to encrypt secret", e);
        }
    }

    public String decrypt(String encoded) {
        try {
            return decryptWith(encoded, keySpec);
        } catch (GeneralSecurityException currentKeyFailure) {
            // Not corruption - GCM's auth tag simply won't verify against a
            // ciphertext written under a different key. During a rotation
            // window (see this class's javadoc) that's the EXPECTED outcome
            // for every row not yet rewritten since the rotation, so this is
            // the normal fallback path, not an error path.
            if (previousKeySpec == null) {
                throw new IllegalStateException("Failed to decrypt secret", currentKeyFailure);
            }
            try {
                return decryptWith(encoded, previousKeySpec);
            } catch (GeneralSecurityException previousKeyFailure) {
                throw new IllegalStateException(
                        "Failed to decrypt secret with either the current or previous encryption"
                                + " key",
                        previousKeyFailure);
            }
        }
    }

    private String decryptWith(String encoded, SecretKeySpec key) throws GeneralSecurityException {
        byte[] combined;
        try {
            combined = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            // Not base64 at all: a plaintext placeholder or a value written by something else.
            throw new GeneralSecurityException("value is not base64-encoded ciphertext", e);
        }
        if (combined.length <= GCM_IV_LENGTH_BYTES) {
            throw new GeneralSecurityException("value is too short to be ciphertext");
        }
        byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
        byte[] ciphertext = new byte[combined.length - GCM_IV_LENGTH_BYTES];
        System.arraycopy(combined, 0, iv, 0, iv.length);
        System.arraycopy(combined, iv.length, ciphertext, 0, ciphertext.length);

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
        byte[] plaintext = cipher.doFinal(ciphertext);

        return new String(plaintext, StandardCharsets.UTF_8);
    }

    /**
     * #815 Governance follow-up - decrypts (current key, falling back to the previous key exactly
     * like {@link #decrypt}) and re-encrypts under the CURRENT key unconditionally. A no-op in
     * effect when the ciphertext was already under the current key (still a fresh IV/ciphertext,
     * but the same plaintext) - lets a caller "touch" a credential to guarantee it's migrated off a
     * retiring previous key without needing to know which key originally encrypted it.
     */
    public String reencrypt(String encoded) {
        return encrypt(decrypt(encoded));
    }
}
