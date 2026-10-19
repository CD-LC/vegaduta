package com.agenticai.core.agents.attestation;

import com.agenticai.core.agents.a2a.A2aCardSigningService;
import com.agenticai.core.agents.a2a.A2aProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * KYA-style ("Know Your Agent") outbound identity attestation: a short-lived
 * Ed25519 JWT, signed with the deployment's A2A operator key ({@link
 * A2aCardSigningService} - so the SAME {@code kid} resolves at {@code
 * /.well-known/jwks.json}), asserting which agent/tenant initiated an
 * outbound call.
 *
 * <p>Wire contract: callers attach {@link #HEADER_NAME} on outbound HTTP
 * requests ({@code http_request} tool, remote MCP transports). A receiver
 * verifies against this deployment's JWKS and learns: which platform issued
 * the call ({@code iss}), which tenant's agent made it ({@code tenant_id},
 * {@code agent_id} when known), and that it was minted moments ago ({@code
 * exp} = {@code iat} + {@value #DEFAULT_TTL_SECONDS}s default - short enough
 * that a captured token cannot be replayed meaningfully later).
 *
 * <p>Absent, never wrong: when card signing is disabled ({@code
 * app.a2a.signing.enabled=false}) or no key is configured, {@link #mint}
 * returns empty and callers send no header - attestation is an additive trust
 * signal, never a request blocker.
 */
@Service
public class AgentAttestationService {

    /** The outbound header receivers verify against this deployment's JWKS. */
    public static final String HEADER_NAME = "X-Agent-Attestation";

    static final long DEFAULT_TTL_SECONDS = 300;

    private final ObjectProvider<A2aCardSigningService> signingService;
    private final Clock clock;
    private final String issuer;
    private final long ttlSeconds;

    @Autowired
    public AgentAttestationService(
        ObjectProvider<A2aCardSigningService> signingService,
        A2aProperties a2aProperties,
        @Value("${app.attestation.issuer:}") String issuer,
        @Value("${app.attestation.ttl-seconds:300}") long ttlSeconds
    ) {
        this.signingService = signingService;
        this.clock = Clock.systemUTC();
        // The attestation issuer defaults to the same public origin the A2A
        // card advertises - the verifier's JWKS lookup is against that origin,
        // so a different iss would point receivers at the wrong key set.
        this.issuer = StringUtils.hasText(issuer) ? issuer : blankToNull(a2aProperties.getPublicBaseUrl());
        this.ttlSeconds = ttlSeconds > 0 ? ttlSeconds : DEFAULT_TTL_SECONDS;
    }

    /** Test seam - fixed clock. */
    AgentAttestationService(
        ObjectProvider<A2aCardSigningService> signingService, Clock clock, String issuer, long ttlSeconds) {
        this.signingService = signingService;
        this.clock = clock;
        this.issuer = issuer;
        this.ttlSeconds = ttlSeconds;
    }

    /**
     * Mint a fresh attestation for one outbound call. {@code agentId} may be
     * null when the caller has no agent context (e.g. a workflow http node) -
     * the claim is simply omitted.
     *
     * @return the compact JWS, or empty when signing is unavailable
     */
    public Optional<String> mint(String tenantId, String subject, String agentId) {
        A2aCardSigningService signer = signingService.getIfAvailable();
        if (signer == null || !signer.isEnabled()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("typ", "JWT");
        Map<String, Object> claims = new LinkedHashMap<>();
        if (issuer != null) {
            claims.put("iss", issuer);
        }
        if (StringUtils.hasText(subject)) {
            claims.put("sub", subject);
        }
        if (StringUtils.hasText(tenantId)) {
            claims.put("tenant_id", tenantId);
        }
        if (StringUtils.hasText(agentId)) {
            claims.put("agent_id", agentId);
        }
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", now.getEpochSecond() + ttlSeconds);
        claims.put("jti", UUID.randomUUID().toString());
        // KYA marker receivers can key policy on - "this call came from a
        // governed agent runtime", not just a random bearer of the key.
        claims.put("agent_platform", "vegaduta");
        return Optional.ofNullable(signer.signCompactJws(header, claims));
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
