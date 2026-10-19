package com.agenticai.core.audit.seal;

import java.util.Map;
import java.util.Optional;

/**
 * The seam between the audit module (a leaf, which must not depend on the module that owns the platform
 * signing key) and the platform's existing Ed25519 signing identity. The implementation lives next to the
 * sweep in core/platform ({@code AuditSealA2aSigner}, which delegates to {@code A2aCardSigningService}).
 *
 * <p>The contract is a compact JWS (EdDSA) over {@code payload}; the seal records the JWS, the key id and
 * the raw public key so the seal is self-describing. Trust in that embedded key is NOT assumed - an offline
 * verifier should pin the platform's published key (see scripts/verify-audit-export.py).
 */
public interface AuditSealSigner {

    /** True when a signing key is available. Without one no seal is created (an unsigned seal proves little). */
    boolean isAvailable();

    /**
     * Signs {@code payload} as a compact JWS.
     *
     * @return the signature, or empty when signing is unavailable or failed
     */
    Optional<Signed> sign(Map<String, Object> payload);

    /**
     * @param compactJws      {@code header.payload.signature}, base64url, EdDSA
     * @param keyId           the signing key's {@code kid}
     * @param publicKeyBase64Url the raw 32-byte Ed25519 public key, base64url without padding (the JWK "x")
     */
    record Signed(String compactJws, String keyId, String publicKeyBase64Url) {
    }
}
