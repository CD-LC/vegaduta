package com.agenticai.core.audit.seal;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Server-side check of a seal's JWS signature (Ed25519, JDK built-in). Public-key only, pure and
 * thread-safe. A seal verifies only if the signature is valid under the key the seal itself carries
 * AND the signed payload binds this exact seal ({@code seal_hash}, {@code seq}, tenant scope) - so
 * a valid signature lifted from another seal does not transfer.
 *
 * <p>Note the trust boundary: the key embedded in a seal is only as trustworthy as the database.
 * The offline script lets the customer pin the platform's published key instead.
 */
public final class AuditSealSignatures {

    /** X.509 SubjectPublicKeyInfo prefix of an Ed25519 key; the 32 raw key bytes follow. */
    private static final byte[] ED25519_SPKI_PREFIX = {
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
    };

    private static final ObjectMapper JSON = JsonMappers.plain();

    private AuditSealSignatures() {}

    /** True iff the seal's signature verifies under its embedded key and binds this seal. */
    public static boolean verify(AuditSealRecord seal) {
        if (seal.signature() == null
                || seal.signature().isBlank()
                || seal.publicKey() == null
                || seal.publicKey().isBlank()) {
            return false;
        }
        try {
            String[] parts = seal.signature().split("\\.", -1);
            if (parts.length != 3) {
                return false;
            }
            Base64.Decoder decoder = Base64.getUrlDecoder();
            JsonNode header = JSON.readTree(decoder.decode(parts[0]));
            JsonNode payload = JSON.readTree(decoder.decode(parts[1]));
            if (!"EdDSA".equals(header.path("alg").asText())) {
                return false;
            }
            if (seal.keyId() != null
                    && header.hasNonNull("kid")
                    && !seal.keyId().equals(header.get("kid").asText())) {
                return false;
            }
            PublicKey key = publicKeyOf(decoder.decode(seal.publicKey()));
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!verifier.verify(decoder.decode(parts[2]))) {
                return false;
            }
            return seal.sealHash().equals(payload.path("seal_hash").asText())
                    && payload.path("seq").canConvertToLong()
                    && payload.path("seq").asLong() == seal.seq()
                    && seal.tenantScope().equals(payload.path("tenant").asText());
        } catch (GeneralSecurityException | java.io.IOException | IllegalArgumentException e) {
            return false; // malformed material fails closed
        }
    }

    static PublicKey publicKeyOf(byte[] raw) throws GeneralSecurityException {
        if (raw.length != 32) {
            throw new GeneralSecurityException("Ed25519 public key must be 32 bytes");
        }
        byte[] spki = new byte[ED25519_SPKI_PREFIX.length + raw.length];
        System.arraycopy(ED25519_SPKI_PREFIX, 0, spki, 0, ED25519_SPKI_PREFIX.length);
        System.arraycopy(raw, 0, spki, ED25519_SPKI_PREFIX.length, raw.length);
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
    }
}
