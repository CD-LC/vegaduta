package com.agenticai.core.agents.trust;

import com.agenticai.core.agents.trust.DelegationTokenService.VerifiedDelegation;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.security.DelegatedTokenAuthenticator;
import com.agenticai.core.security.DelegationAuthenticationToken;
import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * The {@code core/agents} half of delegation-token API authentication - implements the {@link
 * DelegatedTokenAuthenticator} SPI declared in {@code core/security} (the interface lives there
 * because agents already depends on security, and the reverse would be a module cycle).
 *
 * <p>A bearer token is a candidate only when its payload carries {@code token_use:"delegation"} -
 * Keycloak JWTs are skipped before any crypto runs. Verification is {@link
 * DelegationTokenService#verifyStateless} bound to the platform API audience ({@code
 * app.delegation.api-audience}), plus the recorded-revocation check executed inside the token's own
 * tenant schema - this filter runs before {@code TenantContextFilter} populates one.
 */
@Component
public class DelegationAuthenticatorAdapter implements DelegatedTokenAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(DelegationAuthenticatorAdapter.class);

    private final DelegationTokenService delegationService;
    private final ObjectMapper objectMapper = JsonMappers.plain();
    private final String apiAudience;

    public DelegationAuthenticatorAdapter(
            DelegationTokenService delegationService,
            @Value("${app.delegation.api-audience:vegaduta-api}") String apiAudience) {
        this.delegationService = delegationService;
        this.apiAudience = apiAudience;
    }

    @Override
    public Optional<Authentication> authenticate(String bearerToken) {
        try {
            if (!isDelegationToken(bearerToken)) {
                return Optional.empty();
            }
            VerifiedDelegation v =
                    delegationService.verifyStateless(bearerToken, apiAudience, null);
            // The recorded check reads tenant tables - run it pinned to the
            // token's own tenant, since no TenantContext exists yet this early.
            TenantContext previous = TenantContext.current().orElse(null);
            try {
                TenantContext.set(
                        TenantContext.of(
                                v.tenantId(),
                                "agent:" + v.agentId(),
                                Set.of(),
                                "delegation:" + v.jti()));
                delegationService.verifyRecorded(v);
            } finally {
                if (previous != null) {
                    TenantContext.set(previous);
                } else {
                    TenantContext.clear();
                }
            }
            return Optional.of(
                    new DelegationAuthenticationToken(
                            v.tenantId(),
                            v.delegator(),
                            v.agentId(),
                            v.audience(),
                            v.scopes(),
                            v.jti()));
        } catch (RuntimeException e) {
            log.debug("Delegation token rejected: {}", e.toString());
            return Optional.empty();
        }
    }

    /**
     * Cheap discriminator before any signature work: decode the payload and require {@code
     * token_use: "delegation"}. Malformed tokens and every Keycloak JWT exit here without an
     * Ed25519 verify.
     */
    private boolean isDelegationToken(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return false;
            }
            return DelegationTokenService.TOKEN_USE.equals(
                    objectMapper
                            .readTree(Base64.getUrlDecoder().decode(parts[1]))
                            .path("token_use")
                            .asText(""));
        } catch (Exception e) {
            return false;
        }
    }
}
