package com.agenticai.core.agents.a2a;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Wire vocabulary and the canonical signing input shared by the outbound signer ({@link
 * A2aMessageSigner}) and the inbound verifier ({@link A2aInboundGuard}).
 *
 * <p><b>What is signed.</b> A detached JWS (RFC 7515 appendix F: {@code protected..signature}, the
 * payload is not sent) over the canonical JSON (sorted keys, no whitespace - the canonicalizer
 * {@code A2aCardSigningService} already uses) of
 *
 * <pre>
 *   { "m": HTTP method, "p": request path, "bd": base64url(SHA-256(body)), "ts": epoch millis,
 *     "n": nonce, "h": hop, "t": trace id, "iss": URL of the signer's Agent Card }
 * </pre>
 *
 * The verifier rebuilds this object from the request it actually received, so a changed body, path,
 * method, timestamp, nonce, hop or trace id fails verification.
 */
final class A2aMessageSecurity {

    /**
     * Agent Card extension URI ({@code capabilities.extensions[].uri}) a server publishes when it
     * verifies message signatures and honours the hop counter; a client sends them only to a peer
     * that lists it.
     */
    static final String EXTENSION_URI = "urn:vegaduta:a2a:ext:message-integrity:v1";

    static final String HEADER_SIGNATURE = "X-Vegaduta-A2A-Signature";
    static final String HEADER_TIMESTAMP = "X-Vegaduta-A2A-Timestamp";
    static final String HEADER_NONCE = "X-Vegaduta-A2A-Nonce";
    static final String HEADER_HOP = "X-Vegaduta-A2A-Hop";
    static final String HEADER_TRACE = "X-Vegaduta-A2A-Trace";
    static final String HEADER_KEY_ID = "X-Vegaduta-A2A-Key-Id";
    static final String HEADER_ORIGIN = "X-Vegaduta-A2A-Origin";

    /** Keys in {@code params.message.metadata} that repeat the hop and trace headers. */
    static final String METADATA_HOP = "vegaduta.a2a.hop";

    static final String METADATA_TRACE = "vegaduta.a2a.trace";

    /** JOSE {@code typ} of the protected header, so a card or token signature is never accepted here. */
    static final String JWS_TYPE = "vegaduta-a2a-msg+jws";

    static final String WELL_KNOWN_CARD_PATH = "/.well-known/agent-card.json";

    private static final ObjectMapper CANONICAL = JsonMappers.sortedMapKeys();
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._:\\-]{1,128}");

    private A2aMessageSecurity() {}

    static String bodyDigest(byte[] body) {
        try {
            return base64url(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static Map<String, Object> claims(
            String httpMethod,
            String path,
            byte[] body,
            long timestampMillis,
            String nonce,
            int hop,
            String traceId,
            String originCardUrl) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("m", httpMethod);
        claims.put("p", path);
        claims.put("bd", bodyDigest(body));
        claims.put("ts", timestampMillis);
        claims.put("n", nonce);
        claims.put("h", hop);
        claims.put("t", traceId);
        claims.put("iss", originCardUrl);
        return claims;
    }

    static byte[] canonicalBytes(Map<String, Object> claims) {
        try {
            return CANONICAL.writeValueAsBytes(claims);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The JWS signing input for the claims: {@code protectedB64 + "." + base64url(canonical)}. */
    static byte[] signingInput(String protectedB64, Map<String, Object> claims) {
        return (protectedB64 + "." + base64url(canonicalBytes(claims)))
                .getBytes(StandardCharsets.US_ASCII);
    }

    static String base64url(byte[] in) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(in);
    }

    /** A correlation id taken from a header: kept only if it is short and made of safe characters. */
    static String safeId(String raw) {
        return raw != null && SAFE_ID.matcher(raw).matches() ? raw : null;
    }

    /** The hop a message's own metadata claims, or -1 when it carries none. */
    static int metadataHop(JsonNode params) {
        JsonNode value = params == null ? null : params.path("message").path("metadata").get(METADATA_HOP);
        if (value == null || value.isNull() || value.isMissingNode()) {
            return -1;
        }
        if (!value.canConvertToInt() || value.asInt() < 0) {
            throw A2aError.invalidRequest("message.metadata." + METADATA_HOP + " must be a non-negative integer");
        }
        return value.asInt();
    }

    static String metadataTrace(JsonNode params) {
        JsonNode value = params == null ? null : params.path("message").path("metadata").get(METADATA_TRACE);
        return value != null && value.isTextual() ? safeId(value.asText()) : null;
    }

    /** True when a peer's card lists the message-integrity extension. */
    static boolean advertises(JsonNode card) {
        JsonNode extensions = card.path("capabilities").path("extensions");
        if (extensions.isArray()) {
            for (JsonNode ext : extensions) {
                if (EXTENSION_URI.equals(ext.path("uri").asText(null))) {
                    return true;
                }
            }
        }
        return false;
    }
}
