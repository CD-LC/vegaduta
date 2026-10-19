package com.agenticai.core.payments;

import com.agenticai.core.crypto.EnvelopeEncryptionService;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ed25519 signing for {@link PaymentMandate} canonical JSON, backed by a
 * lazily created per-tenant keypair ({@code payment_signing_key}, V345).
 * The private key persists ONLY as {@link EnvelopeEncryptionService}
 * ciphertext - it is decrypted in memory per sign call and never logged.
 * Verification needs only the public key, so {@link MandateEnforcer} can
 * verify a stored mandate without ever touching the private half.
 *
 * <p>Ed25519 is JDK-built-in ({@code KeyPairGenerator.getInstance("Ed25519")},
 * JDK 15+) - no extra dependency, and the same primitive AP2-adjacent
 * protocols converge on.
 */
@Service
public class MandateSigningService {

    private static final Logger log = LoggerFactory.getLogger(MandateSigningService.class);

    private final PaymentSigningKeyRepository keyRepository;
    private final EnvelopeEncryptionService encryptionService;

    public MandateSigningService(
        PaymentSigningKeyRepository keyRepository,
        EnvelopeEncryptionService encryptionService
    ) {
        this.keyRepository = keyRepository;
        this.encryptionService = encryptionService;
    }

    /**
     * The tenant's ACTIVE signing key, creating the keypair on first use.
     * Transactional so a concurrent first-time create collapses onto the
     * {@code uq_payment_signing_key_active} unique index rather than
     * double-minting.
     */
    @Transactional
    public PaymentSigningKey activeKey() {
        return keyRepository.findFirstByStatusOrderByCreatedAtDesc("ACTIVE")
            .orElseGet(this::generateAndPersist);
    }

    private PaymentSigningKey generateAndPersist() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
            KeyPair pair = generator.generateKeyPair();
            String keyId = "psk_" + UUID.randomUUID();
            String privateEncrypted = encryptionService.encrypt(
                Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
            PaymentSigningKey key = new PaymentSigningKey(
                keyId, pair.getPublic().getEncoded(), privateEncrypted);
            log.info("Generated per-tenant payment signing key {}", keyId);
            return keyRepository.save(key);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 key generation failed", e);
        }
    }

    /** Signs {@code canonicalJson}'s UTF-8 bytes with the tenant's private key. */
    @Transactional(readOnly = true)
    public byte[] sign(PaymentSigningKey key, String canonicalJson) {
        try {
            PrivateKey privateKey = loadPrivateKey(key);
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(privateKey);
            signer.update(canonicalJson.getBytes(StandardCharsets.UTF_8));
            return signer.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Mandate signing failed", e);
        }
    }

    /**
     * Verifies {@code signature} over {@code canonicalJson} (UTF-8) against
     * the public key of the {@code payment_signing_key} row the mandate
     * names. Public-key-only - safe to call on any thread.
     */
    public boolean verify(PaymentMandate mandate) {
        return verify(mandate.getKeyId(), mandate.getCanonicalJson(), mandate.getSignature());
    }

    /**
     * The field-level overload {@link #verify(PaymentMandate)} delegates to -
     * used by the commerce-mandate layer (intent/cart mandates, buyer
     * authorizations), which stores the same canonical_json + signature +
     * key_id triple on its own tables.
     */
    public boolean verify(String keyId, String canonicalJson, byte[] signature) {
        try {
            PaymentSigningKey key = keyRepository.findByKeyId(keyId).orElse(null);
            if (key == null) {
                return false;
            }
            PublicKey publicKey = KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(key.getPublicKey()));
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(canonicalJson.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    private PrivateKey loadPrivateKey(PaymentSigningKey key) throws GeneralSecurityException {
        byte[] pkcs8 = Base64.getDecoder().decode(encryptionService.decrypt(key.getPrivateKeyEncrypted()));
        return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
    }
}
