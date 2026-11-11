package com.agenticai.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

/**
 * The delegation principal: the agent acts AS the delegating user (the principal IS the user, so
 * existing ACL checks apply unchanged), scopes arrive as {@code SCOPE_*} authorities, and no {@code
 * ROLE_*} authority is ever minted for a delegation token.
 */
class DelegationAuthenticationTokenTest {

    private static final UUID AGENT_ID = UUID.randomUUID();

    private static DelegationAuthenticationToken token(List<String> scopes) {
        return new DelegationAuthenticationToken(
                "tenant_acme", "user-1", AGENT_ID, "vegaduta", scopes, "jti-1");
    }

    @Test
    void thePrincipalIsTheDelegatingUserNotTheAgent() {
        DelegationAuthenticationToken token = token(List.of("chat"));

        assertThat(token.getPrincipal()).isEqualTo("user-1");
        assertThat(token.getName()).isEqualTo("user-1");
    }

    @Test
    void scopesBecomeScopePrefixedAuthoritiesOnly() {
        DelegationAuthenticationToken token = token(List.of("chat", "tools:run"));

        java.util.Set<String> authorities =
                token.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .collect(Collectors.toSet());

        assertThat(authorities).containsExactlyInAnyOrder("SCOPE_chat", "SCOPE_tools:run");
        assertThat(authorities.stream().noneMatch(a -> a.startsWith("ROLE_"))).isTrue();
    }

    @Test
    void nullScopesMeanNoAuthorities() {
        assertThat(token(null).getAuthorities()).isEmpty();
    }

    @Test
    void provenanceFieldsAreCarriedForAuditAndTheTokenIsPreAuthenticated() {
        DelegationAuthenticationToken token = token(List.of());

        assertThat(token.isAuthenticated()).isTrue();
        assertThat(token.getTenantId()).isEqualTo("tenant_acme");
        assertThat(token.getAgentId()).isEqualTo(AGENT_ID);
        assertThat(token.getAudience()).isEqualTo("vegaduta");
        assertThat(token.getJti()).isEqualTo("jti-1");
        assertThat(token.getCredentials()).isEqualTo("");
    }
}
