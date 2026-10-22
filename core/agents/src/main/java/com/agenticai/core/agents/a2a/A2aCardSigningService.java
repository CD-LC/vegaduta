package com.agenticai.core.agents.a2a;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.tenancy.replica.ReplicaMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.NamedParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Signs A2A Agent Cards and verifies cards signed elsewhere - the trust primitive a cross-tenant
 * "callable agents" marketplace needs before cards can carry any weight: an unsigned card proves
 * nothing about who published it, a signed one chains to a key the operator pinned in config.
 *
 * <p>Wire shape follows the A2A spec's {@code signatures} field on AgentCard: each entry is a JWS
 * over the card with detached content -
 *
 * <pre>
 *   { "protected": base64url(JWS header), "signature": base64url(sig), "header": {"kid": ...} }
 * </pre>
 *
 * <p>where the signing input is {@code base64url(protected) + "." + base64url(canonicalCardJson)}
 * and {@code canonicalCardJson} is the card MINUS its {@code signatures} field, serialized with
 * sorted keys and no whitespace (the JCS/RFC-8785 shape for the field types this card carries:
 * strings, booleans, nested maps, lists - the card contains no floats, so JCS number
 * canonicalization never comes into play).
 *
 * <p>Key management: {@code app.a2a.signing.private-key-pem} is THE operator identity - pin it for
 * any real deployment (and especially anything multi-replica). Generate a pair with {@code openssl
 * genpkey -algorithm ed25519 -out a2a-signing.pem} + {@code openssl pkey -in a2a-signing.pem
 * -pubout -out a2a-signing.pub} and set BOTH PEMs (the JDK does not expose public-key derivation
 * from a PKCS8 Ed25519 private, so a private key alone is rejected at boot rather than silently
 * half-configured). When no key is configured and signing is enabled, an ephemeral key is generated
 * at boot with a loud warning: cards still verify against this instance's JWKS, but the identity
 * resets on restart - fine for dev, meaningless for a marketplace listing. The public key is served
 * at {@code /.well-known/jwks.json} so verifiers resolve {@code kid} against the same origin that
 * served the card.
 */
@Service
public class A2aCardSigningService {

    private static final Logger log = LoggerFactory.getLogger(A2aCardSigningService.class);

    private final A2aProperties properties;

    /** Sorted-keys compact writer - the canonicalizer the signature covers. */
    private final ObjectMapper canonical = JsonMappers.sortedMapKeys();

    private volatile KeyPair keyPair;
    private volatile String keyId;

    /**
     * Deployment-shape hint ({@code app.replica-mode}: e.g. {@code api}, {@code worker}, {@code
     * jobs}, {@code multi}); blank / {@code single} = one replica. Only used to decide whether an
     * ephemeral signing key is worth a startup WARN.
     */
    @Value("${app.replica-mode:}")
    private String replicaMode = "";

    public A2aCardSigningService(A2aProperties properties) {
        this.properties = properties;
    }

    /** Test seam - the hint is field-injected by Spring. */
    void setReplicaMode(String replicaMode) {
        this.replicaMode = replicaMode;
    }

    /**
     * Startup check for the ephemeral-key trap: with no pinned key each replica (and each restart)
     * mints a DIFFERENT Ed25519 identity, so cards signed by replica A fail verification against
     * replica B's JWKS. {@code app.a2a.require-pinned-key=true} turns that into a boot failure;
     * otherwise it is a WARN, and only when {@code app.replica-mode} hints at more than one replica
     * (the lazy WARN in {@link #ensureKeys()} still fires on first use either way).
     */
    @PostConstruct
    void checkKeyPinning() {
        if (!isEnabled() || StringUtils.hasText(properties.getSigning().getPrivateKeyPem())) {
            return;
        }
        if (properties.isRequirePinnedKey()) {
            throw new IllegalStateException(
                    "app.a2a.require-pinned-key=true but app.a2a.signing.private-key-pem is empty - set "
                            + "APP_A2A_SIGNING_PRIVATE_KEY + APP_A2A_SIGNING_PUBLIC_KEY (or disable signing).");
        }
        String mode = replicaMode == null ? "" : replicaMode.strip();
        // One definition of "multi" (blank/single = one replica, anything else = more than one):
        // ReplicaMode.
        if (ReplicaMode.parse(mode).isMulti()) {
            log.warn(
                    "app.a2a.signing: running with app.replica-mode={} but NO pinned signing key - each replica "
                            + "will sign with its own ephemeral Ed25519 key and cards will not verify across replicas. Pin "
                            + "APP_A2A_SIGNING_PRIVATE_KEY + APP_A2A_SIGNING_PUBLIC_KEY (or set app.a2a.require-pinned-key=true "
                            + "to fail fast).",
                    mode);
        }
    }

    public boolean isEnabled() {
        return properties.getSigning().isEnabled();
    }

    /**
     * Signs a card. {@code card} must NOT already contain a {@code signatures} field (the payload
     * is the card as the caller built it); the returned list is what the caller sets as {@code
     * card.put("signatures", ...)}. Returns an empty list when signing is disabled.
     */
    public List<Map<String, Object>> sign(Map<String, Object> card) {
        if (!isEnabled()) {
            return List.of();
        }
        try {
            byte[] payload = canonical.writeValueAsBytes(card);
            Map<String, Object> jwsHeader = new LinkedHashMap<>();
            jwsHeader.put("alg", "EdDSA");
            jwsHeader.put("kid", keyId());
            jwsHeader.put("typ", "JWS");
            jwsHeader.put("b64", true);
            jwsHeader.put("crit", List.of("b64"));
            String protectedB64 = base64url(canonical.writeValueAsBytes(jwsHeader));
            byte[] signingInput = signingInput(protectedB64, payload);

            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(keyPair().getPrivate());
            signer.update(signingInput);

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("protected", protectedB64);
            entry.put("signature", base64url(signer.sign()));
            entry.put("header", Map.of("kid", keyId()));
            return List.of(entry);
        } catch (GeneralSecurityException | com.fasterxml.jackson.core.JsonProcessingException e) {
            // A signing failure must not break discovery - an unsigned card is
            // still a valid card; log loudly so an operator notices the key is
            // broken rather than silently shipping unsigned cards.
            log.error("A2A card signing failed - serving unsigned card", e);
            return List.of();
        }
    }

    /**
     * Signs an arbitrary payload as a compact JWS ({@code header.payload.sig}) over the same
     * operator key - the signing primitive {@code AgentAttestationService} (KYA-style outbound
     * identity) builds its JWT on. Returns null when signing is disabled, so a caller that needs a
     * signature can distinguish "not configured" from a real token.
     */
    public String signCompactJws(Map<String, Object> protectedHeader, Map<String, Object> payload) {
        if (!isEnabled()) {
            return null;
        }
        try {
            Map<String, Object> header = new LinkedHashMap<>(protectedHeader);
            header.putIfAbsent("alg", "EdDSA");
            header.putIfAbsent("kid", keyId());
            String signingInputB64 =
                    base64url(canonical.writeValueAsBytes(header))
                            + "."
                            + base64url(canonical.writeValueAsBytes(payload));
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(keyPair().getPrivate());
            signer.update(signingInputB64.getBytes(StandardCharsets.US_ASCII));
            return signingInputB64 + "." + base64url(signer.sign());
        } catch (GeneralSecurityException | com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error("Compact JWS signing failed", e);
            return null;
        }
    }

    /**
     * Verifies a card's {@code signatures} against {@code publicKey} - the inbound direction of the
     * marketplace (verifying a card fetched from another deployment). Strips the {@code signatures}
     * field, re-canonicalizes, and re-checks every entry; returns true only if at least one
     * verifies. Tampered cards, wrong keys and malformed entries all fail closed to false.
     */
    public boolean verify(Map<String, Object> card, PublicKey publicKey) {
        Object signatures = card.get("signatures");
        if (!(signatures instanceof List<?> list) || list.isEmpty()) {
            return false;
        }
        Map<String, Object> unsigned = new TreeMap<>(card);
        unsigned.remove("signatures");
        try {
            byte[] payload = canonical.writeValueAsBytes(unsigned);
            for (Object entryObj : list) {
                if (!(entryObj instanceof Map<?, ?> entry)) {
                    continue;
                }
                if (!(entry.get("protected") instanceof String protectedB64)
                        || !(entry.get("signature") instanceof String sigB64)) {
                    continue;
                }
                Signature verifier = Signature.getInstance("Ed25519");
                verifier.initVerify(publicKey);
                verifier.update(signingInput(protectedB64, payload));
                if (verifier.verify(Base64.getUrlDecoder().decode(sigB64))) {
                    return true;
                }
            }
        } catch (GeneralSecurityException
                | com.fasterxml.jackson.core.JsonProcessingException
                | IllegalArgumentException e) {
            return false;
        }
        return false;
    }

    /** The public half of the signing key as an OKP JWK (RFC 8037), for JWKS. */
    public Map<String, Object> publicJwk() {
        byte[] encoded = keyPair().getPublic().getEncoded();
        // X.509 SubjectPublicKeyInfo for Ed25519 = fixed 12-byte header + raw key.
        byte[] raw = new byte[32];
        System.arraycopy(encoded, encoded.length - 32, raw, 0, 32);
        Map<String, Object> jwk = new LinkedHashMap<>();
        jwk.put("kty", "OKP");
        jwk.put("crv", "Ed25519");
        jwk.put("x", base64url(raw));
        jwk.put("kid", keyId());
        jwk.put("use", "sig");
        jwk.put("alg", "EdDSA");
        return jwk;
    }

    public String keyId() {
        ensureKeys();
        return keyId;
    }

    public PublicKey publicKey() {
        return keyPair().getPublic();
    }

    private KeyPair keyPair() {
        ensureKeys();
        return keyPair;
    }

    private synchronized void ensureKeys() {
        if (keyPair != null) {
            return;
        }
        A2aProperties.Signing cfg = properties.getSigning();
        // Built into locals and published keyId-first, keyPair-last (JAVA-044):
        // keyPair != null is the "initialized" guard, so it must only become
        // visible once keyId is set - and a failure while deriving the kid can
        // no longer leave a half-initialized service (keyPair set, keyId null).
        KeyPair generated;
        try {
            if (StringUtils.hasText(cfg.getPrivateKeyPem())) {
                if (!StringUtils.hasText(cfg.getPublicKeyPem())) {
                    throw new IllegalStateException(
                            "app.a2a.signing.private-key-pem is set without public-key-pem - the JDK "
                                    + "cannot derive an Ed25519 public key from a PKCS8 private; set both "
                                    + "(openssl pkey -in <key> -pubout).");
                }
                generated =
                        new KeyPair(
                                parsePublicKey(cfg.getPublicKeyPem()),
                                parsePrivateKey(cfg.getPrivateKeyPem()));
            } else {
                generated = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
                log.warn(
                        "app.a2a.signing: no private-key-pem configured - generated an EPHEMERAL "
                                + "Ed25519 key for this boot. Signed cards verify against this instance's JWKS "
                                + "only until restart; pin APP_A2A_SIGNING_PRIVATE_KEY + APP_A2A_SIGNING_PUBLIC_KEY "
                                + "for a stable identity (required for anything multi-replica or marketplace-facing).");
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(
                    "app.a2a.signing key material is not usable Ed25519 PEM", e);
        }
        String configuredKid = cfg.getKeyId();
        keyId =
                StringUtils.hasText(configuredKid)
                        ? configuredKid
                        : thumbprintKid(generated.getPublic());
        keyPair = generated;
    }

    private static String thumbprintKid(PublicKey publicKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(publicKey.getEncoded());
            byte[] first8 = new byte[8];
            System.arraycopy(digest, 0, first8, 0, 8);
            return "a2a-" + base64url(first8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Accepts a PKCS8 PEM, or a raw 32-byte seed (base64 or hex) for convenience. */
    static PrivateKey parsePrivateKey(String pemOrSeed) throws GeneralSecurityException {
        byte[] der = pemToDer(pemOrSeed);
        if (der.length == 32) {
            return KeyFactory.getInstance("Ed25519")
                    .generatePrivate(new EdECPrivateKeySpec(NamedParameterSpec.ED25519, der));
        }
        return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    static PublicKey parsePublicKey(String pem) throws GeneralSecurityException {
        return KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(pemToDer(pem)));
    }

    static byte[] pemToDer(String pemOrBare) {
        String s =
                pemOrBare
                        .strip()
                        .replaceAll("-----BEGIN [^-]+-----", "")
                        .replaceAll("-----END [^-]+-----", "")
                        .replaceAll("\\s", "");
        try {
            return Base64.getDecoder().decode(s);
        } catch (IllegalArgumentException notBase64) {
            // raw hex seed convenience form
            if (s.matches("[0-9a-fA-F]+") && s.length() % 2 == 0) {
                return bytesFromHex(s);
            }
            throw notBase64;
        }
    }

    private static byte[] signingInput(String protectedB64, byte[] payload) {
        byte[] p = protectedB64.getBytes(StandardCharsets.US_ASCII);
        byte[] pl = base64urlBytes(payload);
        byte[] out = new byte[p.length + 1 + pl.length];
        System.arraycopy(p, 0, out, 0, p.length);
        out[p.length] = '.';
        System.arraycopy(pl, 0, out, p.length + 1, pl.length);
        return out;
    }

    private static String base64url(byte[] in) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(in);
    }

    private static byte[] base64urlBytes(byte[] in) {
        return base64url(in).getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] bytesFromHex(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
