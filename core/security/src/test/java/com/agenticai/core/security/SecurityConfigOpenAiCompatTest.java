package com.agenticai.core.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** The two decisions the {@code /v1/**} rule in {@link SecurityConfig} is built from. */
class SecurityConfigOpenAiCompatTest {

    private static MockHttpServletRequest withAuth(String header) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/v1/chat/completions");
        if (header != null) {
            r.addHeader("Authorization", header);
        }
        return r;
    }

    @Test
    void grantKeyBearerIsRecognisedByPrefixOnly() {
        assertTrue(SecurityConfig.hasGrantKeyBearer(withAuth("Bearer vmcp_abc123")));
        assertTrue(SecurityConfig.hasGrantKeyBearer(withAuth("bearer vmcp_abc123")));
        assertFalse(SecurityConfig.hasGrantKeyBearer(withAuth("Bearer eyJhbGciOiJSUzI1NiJ9.e30.sig")));
        assertFalse(SecurityConfig.hasGrantKeyBearer(withAuth("Bearer  vmcp_abc")));
        assertFalse(SecurityConfig.hasGrantKeyBearer(withAuth("Basic dXNlcjpwYXNz")));
        assertFalse(SecurityConfig.hasGrantKeyBearer(withAuth(null)));
    }

    @Test
    void onlyARealAuthenticatedPrincipalCountsAsAuthenticated() {
        assertFalse(SecurityConfig.isAuthenticatedUser(null));
        assertFalse(SecurityConfig.isAuthenticatedUser(new AnonymousAuthenticationToken(
            "k", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")))));
        assertTrue(SecurityConfig.isAuthenticatedUser(new UsernamePasswordAuthenticationToken(
            "u", "n/a", List.of(new SimpleGrantedAuthority("ROLE_user")))));
        assertFalse(SecurityConfig.isAuthenticatedUser(new UsernamePasswordAuthenticationToken("u", "n/a")));
    }
}
