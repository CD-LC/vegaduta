package com.agenticai.core.agents.a2a;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Message-level checks every inbound A2A request passes after transport authentication (the bearer
 * JWT) and before any work is done, in this order:
 *
 * <ol>
 *   <li><b>Hop limit.</b> The request's hop number (the larger of the {@code X-Vegaduta-A2A-Hop}
 *       header and {@code message.metadata["vegaduta.a2a.hop"]}) must not exceed {@code
 *       app.a2a.max-hops}. Enforced for signed and unsigned requests alike: it limits how far a
 *       chain of cooperating instances can go, and a caller that lies about its hop only gets itself
 *       a longer chain, which its own next hop then bounds again.
 *   <li><b>Signature.</b> If the request carries {@code X-Vegaduta-A2A-Signature}: the timestamp
 *       must be inside {@code timestamp-window}; the caller's key is resolved from its Agent Card
 *       ({@link A2aPeerKeyResolver}); the detached JWS must verify over the request as received
 *       ({@link A2aMessageSecurity}); and only then is the nonce claimed in the {@link
 *       A2aNonceCache}, so a replay is refused and garbage cannot fill the cache.
 *   <li><b>Legacy peers.</b> A request with no signature is accepted unless {@code
 *       require-signed-messages=true}.
 * </ol>
 *
 * Every refusal is an {@link A2aError} with a distinct code, so the JSON-RPC binding answers with a
 * proper error envelope and the REST binding with the matching HTTP status.
 *
 * <p>Known limits (also in docs/architecture/a2a-conformance.md): the signature binds the path the
 * server saw, so a reverse proxy that rewrites the path breaks verification; it does not bind the
 * receiving host, so a signed request could be replayed to a different server that trusts the same
 * caller key (its own nonce cache cannot know it was used elsewhere); and the nonce cache is per
 * replica unless {@code app.a2a.task-store=redis}.
 */
@ConditionalOnEditionFeature(EditionFeature.A2A_DIRECTORY)
@Component
public class A2aInboundGuard {

    private static final Logger log = LoggerFactory.getLogger(A2aInboundGuard.class);
    private static final ObjectMapper JSON = JsonMappers.plain();

    private final A2aMessageSecurityProperties security;
    private final A2aNonceCache nonces;
    private final A2aPeerKeyResolver keys;
    private final Clock clock;

    @Autowired
    public A2aInboundGuard(
            A2aMessageSecurityProperties security,
            A2aNonceCache nonces,
            A2aPeerKeyResolver keys) {
        this(security, nonces, keys, Clock.systemUTC());
    }

    A2aInboundGuard(
            A2aMessageSecurityProperties security,
            A2aNonceCache nonces,
            A2aPeerKeyResolver keys,
            Clock clock) {
        this.security = security;
        this.nonces = nonces;
        this.keys = keys;
        this.clock = clock;
    }

    /** The context of a request that did not come through the checks (hop 0, no signature). */
    static A2aCallContext rootContext() {
        return A2aCallContext.root(UUID.randomUUID().toString());
    }

    /**
     * Runs the checks for one request.
     *
     * @param meta HTTP method, path and headers of the request
     * @param body the raw request body, exactly as received
     * @param params the JSON-RPC {@code params} (or, on REST, the body object) for the metadata
     *     fallback; may be null
     * @return the call context to run the request under
     * @throws A2aError when the request must be refused
     */
    public A2aCallContext check(A2aInboundMeta meta, String body, JsonNode params) {
        int headerHop = parseHop(meta.get(A2aMessageSecurity.HEADER_HOP));
        int hop = Math.max(Math.max(headerHop, 0), A2aMessageSecurity.metadataHop(params));
        int max = security.getMaxHops();
        if (max > 0 && hop > max) {
            throw A2aError.hopLimitExceeded(hop, max);
        }

        String signature = meta.get(A2aMessageSecurity.HEADER_SIGNATURE);
        A2aMessageSecurityProperties.Server server = security.getServer();
        String trace =
                firstNonNull(
                        A2aMessageSecurity.safeId(meta.get(A2aMessageSecurity.HEADER_TRACE)),
                        A2aMessageSecurity.metadataTrace(params));
        if (signature == null) {
            if (server.isRequireSignedMessages()) {
                throw A2aError.messageSignatureRequired();
            }
            return new A2aCallContext(hop, trace != null ? trace : newTrace(), false);
        }
        if (!server.isVerifySignatures()) {
            return new A2aCallContext(hop, trace != null ? trace : newTrace(), false);
        }
        verifySignature(meta, body, signature, headerHop, trace);
        return new A2aCallContext(hop, trace, true);
    }

    private void verifySignature(
            A2aInboundMeta meta, String body, String signature, int headerHop, String trace) {
        String timestampRaw = meta.get(A2aMessageSecurity.HEADER_TIMESTAMP);
        String nonce = meta.get(A2aMessageSecurity.HEADER_NONCE);
        String keyId = meta.get(A2aMessageSecurity.HEADER_KEY_ID);
        String origin = meta.get(A2aMessageSecurity.HEADER_ORIGIN);
        if (timestampRaw == null
                || nonce == null
                || keyId == null
                || origin == null
                || trace == null
                || headerHop < 0
                || nonce.length() > 128) {
            throw A2aError.messageSignatureInvalid("a required signature header is missing");
        }
        long timestamp;
        try {
            timestamp = Long.parseLong(timestampRaw);
        } catch (NumberFormatException e) {
            throw A2aError.messageSignatureInvalid("timestamp is not a number");
        }
        long skewMillis = clock.millis() - timestamp;
        if (Math.abs(skewMillis) > security.getServer().getTimestampWindow().toMillis()) {
            throw A2aError.messageExpired(skewMillis / 1000);
        }

        String[] parts = signature.split("\\.", -1);
        if (parts.length != 3 || parts[0].isEmpty() || !parts[1].isEmpty() || parts[2].isEmpty()) {
            throw A2aError.messageSignatureInvalid("signature is not a detached JWS");
        }
        checkProtectedHeader(parts[0], keyId);

        Optional<PublicKey> key = keys.resolve(origin, keyId);
        if (key.isEmpty()) {
            throw A2aError.messageSignatureInvalid("the signing key could not be resolved");
        }
        Map<String, Object> claims =
                A2aMessageSecurity.claims(
                        meta.httpMethod(),
                        meta.path(),
                        (body == null ? "" : body).getBytes(StandardCharsets.UTF_8),
                        timestamp,
                        nonce,
                        headerHop,
                        trace,
                        origin);
        if (!verify(key.get(), parts[0], claims, parts[2])) {
            throw A2aError.messageSignatureInvalid("the signature does not match the request");
        }
        switch (nonces.claim(keyId, nonce)) {
            case REPLAYED -> throw A2aError.messageReplayed();
            case FULL -> throw A2aError.replayCacheFull();
            case FRESH -> {
                // accepted
            }
        }
    }

    private static void checkProtectedHeader(String protectedB64, String keyId) {
        try {
            JsonNode header = JSON.readTree(Base64.getUrlDecoder().decode(protectedB64));
            if (!"EdDSA".equals(header.path("alg").asText(null))
                    || !A2aMessageSecurity.JWS_TYPE.equals(header.path("typ").asText(null))
                    || !keyId.equals(header.path("kid").asText(null))) {
                throw A2aError.messageSignatureInvalid("unexpected signature header");
            }
        } catch (A2aError e) {
            throw e;
        } catch (Exception e) {
            throw A2aError.messageSignatureInvalid("signature header is malformed");
        }
    }

    private static boolean verify(
            PublicKey key, String protectedB64, Map<String, Object> claims, String signatureB64) {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(A2aMessageSecurity.signingInput(protectedB64, claims));
            return verifier.verify(Base64.getUrlDecoder().decode(signatureB64));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            log.debug("A2A message signature did not verify: {}", e.toString());
            return false;
        }
    }

    /** -1 when absent; the request is refused when present but not a non-negative integer. */
    private static int parseHop(String raw) {
        if (raw == null) {
            return -1;
        }
        try {
            int hop = Integer.parseInt(raw);
            if (hop < 0) {
                throw new NumberFormatException();
            }
            return hop;
        } catch (NumberFormatException e) {
            throw A2aError.invalidRequest(
                    A2aMessageSecurity.HEADER_HOP + " must be a non-negative integer");
        }
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    private static String newTrace() {
        return UUID.randomUUID().toString();
    }
}
