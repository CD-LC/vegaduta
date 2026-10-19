package com.agenticai.core.agents.attestation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.agenticai.core.agents.a2a.A2aCardSigningService;
import com.agenticai.core.agents.a2a.A2aProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * KYA attestation JWTs: the compact-JWS wire shape, signature validity
 * against the deployment's own public key (the same key {@code
 * /.well-known/jwks.json} serves), and the "absent, never wrong" contract
 * when signing is off.
 */
class AgentAttestationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-24T10:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private A2aCardSigningService signer;
    private AgentAttestationService service;

    @BeforeEach
    void setUp() {
        signer = new A2aCardSigningService(new A2aProperties()); // signing on, ephemeral key
        service = new AgentAttestationService(
            providerOf(signer), Clock.fixed(NOW, ZoneOffset.UTC), "https://api.example.com", 300);
    }

    private static ObjectProvider<A2aCardSigningService> providerOf(A2aCardSigningService bean) {
        return new ObjectProvider<>() {
            @Override
            public A2aCardSigningService getObject(Object... args) {
                return bean;
            }

            @Override
            public A2aCardSigningService getIfAvailable() {
                return bean;
            }

            @Override
            public A2aCardSigningService getIfUnique() {
                return bean;
            }

            @Override
            public A2aCardSigningService getObject() {
                return bean;
            }
        };
    }

    @Test
    void mint_producesAVerifiableCompactJws_withKyaClaims() throws Exception {
        service = new AgentAttestationService(
            providerOf(signer), Clock.fixed(NOW, ZoneOffset.UTC), "https://api.example.com", 300);

        Optional<String> token = service.mint("tenant_acme", "user-1", "agent-9");

        assertTrue(token.isPresent());
        String[] parts = token.get().split("\\.");
        assertEquals(3, parts.length);

        // Signature verifies against the same public key JWKS serves.
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(signer.publicKey());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertTrue(verifier.verify(Base64.getUrlDecoder().decode(parts[2])));

        Map<?, ?> header = MAPPER.readValue(Base64.getUrlDecoder().decode(parts[0]), Map.class);
        assertEquals("EdDSA", header.get("alg"));
        assertEquals(signer.keyId(), header.get("kid"));
        assertEquals("JWT", header.get("typ"));

        Map<?, ?> claims = MAPPER.readValue(Base64.getUrlDecoder().decode(parts[1]), Map.class);
        assertEquals("https://api.example.com", claims.get("iss"));
        assertEquals("user-1", claims.get("sub"));
        assertEquals("tenant_acme", claims.get("tenant_id"));
        assertEquals("agent-9", claims.get("agent_id"));
        assertEquals("vegaduta", claims.get("agent_platform"));
        assertEquals(NOW.getEpochSecond(), ((Number) claims.get("iat")).longValue());
        assertEquals(NOW.getEpochSecond() + 300, ((Number) claims.get("exp")).longValue());
        assertNotNull(claims.get("jti"));
    }

    @Test
    void mint_whenSigningIsDisabled_returnsEmpty_neverABrokenToken() {
        A2aProperties props = new A2aProperties();
        props.getSigning().setEnabled(false);
        A2aCardSigningService disabled = new A2aCardSigningService(props);
        AgentAttestationService svc = new AgentAttestationService(
            providerOf(disabled), Clock.fixed(NOW, ZoneOffset.UTC), "https://api.example.com", 300);

        assertFalse(svc.mint("t", "u", "a").isPresent());
    }

    @Test
    void mint_withNoCallerContext_stillMintsAPlatformAttestation() throws Exception {
        service = new AgentAttestationService(
            providerOf(signer), Clock.fixed(NOW, ZoneOffset.UTC), "https://api.example.com", 300);

        Optional<String> token = service.mint(null, null, null);

        assertTrue(token.isPresent());
        Map<?, ?> claims = MAPPER.readValue(
            Base64.getUrlDecoder().decode(token.get().split("\\.")[1]), Map.class);
        assertFalse(claims.containsKey("tenant_id"));
        assertFalse(claims.containsKey("agent_id"));
        assertEquals("vegaduta", claims.get("agent_platform"));
    }
}
