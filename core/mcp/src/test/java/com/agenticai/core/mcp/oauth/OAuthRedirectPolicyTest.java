package com.agenticai.core.mcp.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OAuthRedirectPolicyTest {

    private final OAuthRedirectPolicy policy =
        new OAuthRedirectPolicy("https://app.example.com/tools", "https://staging.example.com, http://localhost:3000");

    @Test
    void noRequestedReturnUrlMeansTheConfiguredDefault() {
        assertEquals("https://app.example.com/tools", policy.resolveReturnUrl(null));
        assertEquals("https://app.example.com/tools", policy.resolveReturnUrl("  "));
    }

    @Test
    void aReturnUrlOnTheDefaultOriginOrAListedOriginIsHonoured() {
        assertEquals("https://app.example.com/integrations?x=1", policy.resolveReturnUrl("https://app.example.com/integrations?x=1"));
        assertEquals("https://staging.example.com/tools", policy.resolveReturnUrl("https://staging.example.com/tools"));
        assertEquals("http://localhost:3000/tools", policy.resolveReturnUrl("http://localhost:3000/tools"));
    }

    @Test
    void anyOtherOriginIsRejectedSoTheCallbackCanNeverBeAnOpenRedirect() {
        for (String bad : new String[] {
            "https://evil.example.com/tools",
            "https://app.example.com.evil.com/tools",
            "https://app.example.com@evil.com/tools",
            "http://app.example.com/tools",
            "https://app.example.com:8443/tools",
            "//evil.example.com/tools",
            "javascript:alert(1)",
            "/relative/path",
            "not a url",
        }) {
            assertThrows(IllegalArgumentException.class, () -> policy.resolveReturnUrl(bad), bad);
        }
    }

    @Test
    void redirectUrisMustBeHttpsOrLoopbackHttpWithNoCredentials() {
        assertTrue(OAuthRedirectPolicy.isAcceptableRedirectUri("https://api.example.com/oauth/mcp/github/callback"));
        assertTrue(OAuthRedirectPolicy.isAcceptableRedirectUri("http://localhost:8080/oauth/mcp/github/callback"));
        assertTrue(OAuthRedirectPolicy.isAcceptableRedirectUri("http://127.0.0.1:8080/cb"));
        assertFalse(OAuthRedirectPolicy.isAcceptableRedirectUri("http://api.example.com/cb"));
        assertFalse(OAuthRedirectPolicy.isAcceptableRedirectUri("https://user:pw@api.example.com/cb"));
        assertFalse(OAuthRedirectPolicy.isAcceptableRedirectUri("https://api.example.com/cb#frag"));
        assertFalse(OAuthRedirectPolicy.isAcceptableRedirectUri("ftp://api.example.com/cb"));
        assertFalse(OAuthRedirectPolicy.isAcceptableRedirectUri(""));
        assertFalse(OAuthRedirectPolicy.isAcceptableRedirectUri(null));
    }
}
