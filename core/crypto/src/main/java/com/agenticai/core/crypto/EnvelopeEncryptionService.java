package com.agenticai.core.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Tech-Stack-Audit-2026.md P2.2 / Master-Roadmap-2026.md item #6 (KMS-backed envelope encryption
 * for BYOK keys). Standard two-layer envelope encryption: every {@link #encrypt} call generates a
 * fresh, random, single-use data key (DEK) via {@link KeyManagementProvider#generateDataKey()},
 * encrypts the actual secret locally with that DEK, then stores the DEK itself only in its wrapped
 * (KMS-encrypted) form alongside the ciphertext. {@link #decrypt} reverses this: unwrap the DEK via
 * the KMS, then decrypt the payload locally with the now-plaintext DEK.
 *
 * <p><b>Why this is a real improvement over {@link EncryptionService}</b> (which every existing
 * BYOK credential table - Twilio, Google, Sarvam, every LLM provider key,
 * Meta/Slack/Discord/Telegram/email/X, MCP server env - still uses as of this pass, see that
 * class's own javadoc): {@code EncryptionService} encrypts every secret directly under one static
 * long-lived master key, so any single leaked ciphertext plus that one key exposes every other
 * secret ever encrypted under it too. Under envelope encryption, each secret has its own DEK; a
 * leaked wrapped-DEK-plus- ciphertext pair is useless without the KEK, exactly like today, but the
 * KEK itself is now only ever used to wrap/unwrap small 32-byte keys rather than being the
 * encryption key for every secret directly - the standard justification for why real cloud KMS
 * services are built around this pattern rather than direct bulk encryption.
 *
 * <p><b>Phase 1 vs Phase 2 (honest scope of this pass):</b> this ships with only {@link
 * LocalKeyManagementProvider} wired in (a KEK-wraps-DEK scheme using the same {@code
 * APP_ENCRYPTION_KEY} env var {@code EncryptionService} already uses - see that provider's own
 * javadoc for why a real cloud KMS wasn't picked yet). It is also intentionally NOT wired into any
 * of the ~15 existing credential entities/tables in this pass - doing that migration for a live
 * multi-tenant platform's already-persisted ciphertext, across every BYOK credential type, in one
 * sweep, with no compile/test verification available in this session, would be exactly the kind of
 * risky mass change this codebase's own established discipline (small, additive, independently
 * verifiable steps) argues against. This class is ready for new call sites today and is the target
 * format for a dedicated follow-up migration of the existing tables once a real cloud KMS provider
 * is chosen and this can be properly compiled/tested.
 *
 * <p>Ciphertext layout: {@code base64(wrappedKeyLen[2 bytes] || wrappedKey || providerIdLen[1 byte]
 * || providerId(UTF-8) || iv[12 bytes] || GCM(ciphertext + tag))}. {@code providerId} is stored
 * per-record (not assumed global) precisely so a later switch to a cloud KMS provider can coexist
 * with old records still wrapped by {@code "local"} - {@link #decrypt} reads it back out and could
 * route to a different provider instance per record once more than one is ever registered (today
 * there is only ever the one Spring injects).
 */
@Component
public class EnvelopeEncryptionService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    private final KeyManagementProvider keyManagementProvider;
    private final SecureRandom secureRandom = new SecureRandom();

    private final Map<String, KeyManagementProvider> registered;

    /**
     * Test and single-provider convenience: {@code primary} both wraps new data keys and is the
     * only provider that can unwrap.
     */
    public EnvelopeEncryptionService(KeyManagementProvider primary) {
        this(primary, List.of(primary));
    }

    /**
     * {@code primary} (Spring's {@code @Primary} bean, selected by {@code app.crypto.kms.provider})
     * wraps every NEW data key. {@code all} is every registered provider; {@link #decrypt} routes
     * each stored envelope to the provider whose {@link KeyManagementProvider#providerId()} was
     * recorded in it. The local provider stays registered next to a cloud one, so envelopes written
     * before the switch keep decrypting. An envelope whose provider/key is not registered fails
     * with a message naming both ids.
     */
    @Autowired
    public EnvelopeEncryptionService(
            KeyManagementProvider primary, List<KeyManagementProvider> all) {
        this.keyManagementProvider = primary;
        Map<String, KeyManagementProvider> byId = new LinkedHashMap<>();
        byId.put(primary.providerId(), primary);
        for (KeyManagementProvider p : all) {
            KeyManagementProvider previous = byId.putIfAbsent(p.providerId(), p);
            if (previous != null && previous != p) {
                throw new IllegalStateException(
                        "Two KeyManagementProvider beans share providerId '"
                                + p.providerId()
                                + "'");
            }
        }
        this.registered = Map.copyOf(byId);
    }

    public String encrypt(String plaintext) {
        WrappedDataKey dataKey = keyManagementProvider.generateDataKey();
        try {
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            SecretKeySpec dek = new SecretKeySpec(dataKey.plaintextKey(), "AES");
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, dek, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            return encodeEnvelope(dataKey.wrappedKey(), dataKey.providerId(), iv, ciphertext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to encrypt secret", e);
        } finally {
            // The plaintext DEK only ever needs to live for this one call -
            // zero it out rather than waiting on GC, same defensive posture
            // a real KMS SDK's own generateDataKey() response handling uses.
            Arrays.fill(dataKey.plaintextKey(), (byte) 0);
        }
    }

    public String decrypt(String encoded) {
        byte[] combined = Base64.getDecoder().decode(encoded);
        int offset = 0;

        int wrappedKeyLen = ((combined[offset] & 0xFF) << 8) | (combined[offset + 1] & 0xFF);
        offset += 2;
        byte[] wrappedKey = Arrays.copyOfRange(combined, offset, offset + wrappedKeyLen);
        offset += wrappedKeyLen;

        int providerIdLen = combined[offset] & 0xFF;
        offset += 1;
        String providerId = new String(combined, offset, providerIdLen, StandardCharsets.UTF_8);
        offset += providerIdLen;

        byte[] iv = Arrays.copyOfRange(combined, offset, offset + GCM_IV_LENGTH_BYTES);
        offset += GCM_IV_LENGTH_BYTES;
        byte[] ciphertext = Arrays.copyOfRange(combined, offset, combined.length);

        KeyManagementProvider provider = registered.get(providerId);
        if (provider == null) {
            // A wrong KEK is never tried silently: an envelope wrapped by a provider/key that is no
            // longer configured (app.crypto.kms.provider or its key id changed) fails here,
            // clearly.
            throw new IllegalStateException(
                    "Envelope was wrapped by KMS provider '"
                            + providerId
                            + "' but the registered providers are "
                            + new TreeSet<>(registered.keySet())
                            + ". Configure app.crypto.kms.provider and its key id to match the one"
                            + " that wrapped this data.");
        }

        byte[] dek = provider.unwrapDataKey(wrappedKey);
        try {
            SecretKeySpec dekSpec = new SecretKeySpec(dek, "AES");
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(
                    Cipher.DECRYPT_MODE, dekSpec, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to decrypt secret", e);
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    private static String encodeEnvelope(
            byte[] wrappedKey, String providerId, byte[] iv, byte[] ciphertext) {
        byte[] providerIdBytes = providerId.getBytes(StandardCharsets.UTF_8);
        if (wrappedKey.length > 0xFFFF) {
            throw new IllegalStateException("wrappedKey unexpectedly exceeds 65535 bytes");
        }
        if (providerIdBytes.length > 0xFF) {
            throw new IllegalStateException("providerId unexpectedly exceeds 255 bytes");
        }

        byte[] combined =
                new byte
                        [2
                                + wrappedKey.length
                                + 1
                                + providerIdBytes.length
                                + iv.length
                                + ciphertext.length];
        int offset = 0;
        combined[offset++] = (byte) ((wrappedKey.length >> 8) & 0xFF);
        combined[offset++] = (byte) (wrappedKey.length & 0xFF);
        System.arraycopy(wrappedKey, 0, combined, offset, wrappedKey.length);
        offset += wrappedKey.length;
        combined[offset++] = (byte) providerIdBytes.length;
        System.arraycopy(providerIdBytes, 0, combined, offset, providerIdBytes.length);
        offset += providerIdBytes.length;
        System.arraycopy(iv, 0, combined, offset, iv.length);
        offset += iv.length;
        System.arraycopy(ciphertext, 0, combined, offset, ciphertext.length);

        return Base64.getEncoder().encodeToString(combined);
    }
}
