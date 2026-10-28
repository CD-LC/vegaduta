package com.agenticai.core.agents.a2a;

import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * The outbound half of message signing: builds the headers an A2A request to a peer carries - the
 * hop number, the trace id and, when this instance can sign, a detached JWS over (method, path,
 * body digest, timestamp, nonce, hop, trace, origin card) made with the same Ed25519 key {@link
 * A2aCardSigningService} signs the Agent Card with. The peer finds the matching public key through
 * our Agent Card ({@code X-Vegaduta-A2A-Origin}) and our JWKS, exactly as it verifies the card.
 *
 * <p>Signing needs {@code app.a2a.public-base-url}: the signature names our card URL, and without
 * a configured public origin there is no URL a peer could fetch it from. Without it (or with
 * signing disabled) the request still carries hop and trace, just no signature.
 */
@ConditionalOnEditionFeature(EditionFeature.A2A_DIRECTORY)
@Component
public class A2aMessageSigner {

    private static final Logger log = LoggerFactory.getLogger(A2aMessageSigner.class);

    private final A2aCardSigningService signingService;
    private final A2aProperties properties;
    private final Clock clock;

    @Autowired
    public A2aMessageSigner(A2aCardSigningService signingService, A2aProperties properties) {
        this(signingService, properties, Clock.systemUTC());
    }

    A2aMessageSigner(A2aCardSigningService signingService, A2aProperties properties, Clock clock) {
        this.signingService = signingService;
        this.properties = properties;
        this.clock = clock;
    }

    /** True when a request can carry a signature a peer could verify. */
    public boolean canSign() {
        return signingService.isEnabled() && StringUtils.hasText(originCardUrl());
    }

    /** Our platform Agent Card URL, the place a peer resolves our signing key from. */
    String originCardUrl() {
        String base = properties.getPublicBaseUrl();
        if (!StringUtils.hasText(base)) {
            return null;
        }
        String trimmed = base.strip();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed + A2aMessageSecurity.WELL_KNOWN_CARD_PATH;
    }

    /**
     * Headers for a {@code POST endpoint} carrying {@code body}.
     *
     * @param hop the hop number of THIS call (the caller's own hop plus one)
     * @param traceId the originating correlation id
     * @param sign whether to add the signature headers (false = hop and trace only)
     */
    public Map<String, String> headers(
            URI endpoint, byte[] body, int hop, String traceId, boolean sign) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(A2aMessageSecurity.HEADER_HOP, Integer.toString(hop));
        headers.put(A2aMessageSecurity.HEADER_TRACE, traceId);
        if (!sign || !canSign()) {
            return headers;
        }
        long timestamp = clock.millis();
        String nonce = UUID.randomUUID().toString();
        String origin = originCardUrl();
        String path = endpoint.getRawPath() == null ? "" : endpoint.getRawPath();
        Map<String, Object> claims =
                A2aMessageSecurity.claims("POST", path, body, timestamp, nonce, hop, traceId, origin);
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("typ", A2aMessageSecurity.JWS_TYPE);
        String compact = signingService.signCompactJws(header, claims);
        String[] parts = compact == null ? new String[0] : compact.split("\\.");
        if (parts.length != 3) {
            log.warn("A2A request signing failed; sending the request unsigned");
            return headers;
        }
        // Detached form (RFC 7515 appendix F): the payload is rebuilt by the verifier, not sent.
        headers.put(A2aMessageSecurity.HEADER_SIGNATURE, parts[0] + ".." + parts[2]);
        headers.put(A2aMessageSecurity.HEADER_TIMESTAMP, Long.toString(timestamp));
        headers.put(A2aMessageSecurity.HEADER_NONCE, nonce);
        headers.put(A2aMessageSecurity.HEADER_KEY_ID, signingService.keyId());
        headers.put(A2aMessageSecurity.HEADER_ORIGIN, origin);
        return headers;
    }

    /** Convenience for callers that hold the body as text. */
    public Map<String, String> headers(
            URI endpoint, String body, int hop, String traceId, boolean sign) {
        return headers(endpoint, body.getBytes(StandardCharsets.UTF_8), hop, traceId, sign);
    }
}
