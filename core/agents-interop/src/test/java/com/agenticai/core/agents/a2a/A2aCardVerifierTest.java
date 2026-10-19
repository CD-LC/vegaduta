package com.agenticai.core.agents.a2a;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Round-trip coverage for {@link A2aCardVerifier}: a card signed by
 * {@link A2aCardSigningService} (ephemeral key) is served through a stub
 * fetcher exactly as a remote deployment's endpoints would serve it, and the
 * verifier must prove it - plus the fail-closed cases (tamper, wrong kid,
 * non-https, no signatures).
 */
class A2aCardVerifierTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CARD_URL = "https://publisher.example.com/.well-known/agent-card.json";
    private static final String JWKS_URL = "https://publisher.example.com/.well-known/jwks.json";

    private A2aCardSigningService signing;
    private Map<String, Object> card;
    private Map<String, Object> jwks;

    @BeforeEach
    void setUp() {
        signing = new A2aCardSigningService(new A2aProperties());
        card = new LinkedHashMap<>();
        card.put("name", "Remote Agent");
        card.put("url", "https://publisher.example.com/a2a");
        card.put("version", "1.0.0");
        card.put("capabilities", Map.of("streaming", true));
        card.put("skills", List.of(Map.of("id", "search", "name", "Search")));
        card.put("signatures", signing.sign(card));
        jwks = Map.of("keys", List.of(signing.publicJwk()));
    }

    private A2aCardVerifier verifierWith(Map<String, Object> cardJson, Map<String, Object> jwksJson) {
        return new A2aCardVerifier(signing, url -> {
            try {
                if (url.equals(CARD_URL)) return MAPPER.writeValueAsString(cardJson);
                if (url.equals(JWKS_URL)) return MAPPER.writeValueAsString(jwksJson);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            throw new java.io.IOException("404 " + url);
        });
    }

    @Test
    void signedCardVerifiesEndToEnd() {
        A2aCardVerifier.VerificationResult r = verifierWith(card, jwks).verifyRemote(CARD_URL);
        assertTrue(r.verified(), r.detail());
        assertEquals(signing.keyId(), r.kid());
    }

    @Test
    void tamperedCardFails() {
        card.put("name", "Imposter Agent");
        A2aCardVerifier.VerificationResult r = verifierWith(card, jwks).verifyRemote(CARD_URL);
        assertFalse(r.verified());
    }

    @Test
    void unsignedCardFails() {
        card.remove("signatures");
        A2aCardVerifier.VerificationResult r = verifierWith(card, jwks).verifyRemote(CARD_URL);
        assertFalse(r.verified());
        assertTrue(r.detail().contains("no signatures"));
    }

    @Test
    void wrongKidInJwksFails() {
        Map<String, Object> wrongKidJwk = new LinkedHashMap<>(signing.publicJwk());
        wrongKidJwk.put("kid", "other-kid");
        A2aCardVerifier.VerificationResult r =
            verifierWith(card, Map.of("keys", List.of(wrongKidJwk))).verifyRemote(CARD_URL);
        assertFalse(r.verified());
    }

    @Test
    void nonHttpsRemoteUrlRejected() {
        A2aCardVerifier.VerificationResult r =
            verifierWith(card, jwks).verifyRemote("http://publisher.example.com/card.json");
        assertFalse(r.verified());
        assertTrue(r.detail().contains("https"));
    }

    @Test
    void loopbackHttpAllowedForDev() {
        // A stubbed fetcher serving a http://localhost card URL must not be
        // scheme-rejected - that's the dev/self-test path.
        A2aCardVerifier v = new A2aCardVerifier(signing, url -> {
            try {
                if (url.startsWith("http://localhost") && url.contains("agent-card"))
                    return MAPPER.writeValueAsString(card);
                if (url.startsWith("http://localhost"))
                    return MAPPER.writeValueAsString(jwks);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            throw new java.io.IOException("404 " + url);
        });
        A2aCardVerifier.VerificationResult r = v.verifyRemote("http://localhost:8080/.well-known/agent-card.json");
        assertTrue(r.verified(), r.detail());
    }

    @Test
    void fetchFailureIsRelayedNotThrown() {
        A2aCardVerifier v = new A2aCardVerifier(signing, url -> {
            throw new java.io.IOException("connection refused");
        });
        A2aCardVerifier.VerificationResult r = v.verifyRemote(CARD_URL);
        assertFalse(r.verified());
        assertTrue(r.detail().contains("could not fetch"));
    }

    @Test
    void advertisedJwksUriIsHonored() {
        String advertised = "https://keys.example.com/jwks.json";
        // jwks_uri is part of the signed payload - it must be on the card
        // BEFORE signing, else verification correctly fails as a tamper.
        card.put("jwks_uri", advertised);
        card.remove("signatures"); // sign() requires the field be absent
        card.put("signatures", signing.sign(card));
        A2aCardVerifier v = new A2aCardVerifier(signing, url -> {
            try {
                if (url.equals(CARD_URL)) return MAPPER.writeValueAsString(card);
                if (url.equals(advertised)) return MAPPER.writeValueAsString(jwks);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            throw new java.io.IOException("404 " + url);
        });
        A2aCardVerifier.VerificationResult r = v.verifyRemote(CARD_URL);
        assertTrue(r.verified(), r.detail());
    }

    @Test
    void nullAndMalformedUrlsFailClosed() {
        A2aCardVerifier v = verifierWith(card, jwks);
        assertNull(v.verifyRemote(null).kid());
        assertFalse(v.verifyRemote("not a url").verified());
    }
}
