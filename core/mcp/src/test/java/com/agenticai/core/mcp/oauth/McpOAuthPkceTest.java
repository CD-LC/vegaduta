package com.agenticai.core.mcp.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class McpOAuthPkceTest {

    private static final Pattern BASE64URL = Pattern.compile("^[A-Za-z0-9_-]+$");

    @Test
    void challengeMatchesTheRfc7636AppendixBVector() {
        // RFC 7636 appendix B: S256 of this verifier is E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            McpOAuthPkce.challengeFor("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
    }

    @Test
    void generatedPairsAreUniqueUrlSafeAndWithinTheRfcLengthLimits() {
        McpOAuthPkce.Pair a = McpOAuthPkce.generate();
        McpOAuthPkce.Pair b = McpOAuthPkce.generate();

        assertNotEquals(a.verifier(), b.verifier());
        for (McpOAuthPkce.Pair p : new McpOAuthPkce.Pair[] {a, b}) {
            assertTrue(p.verifier().length() >= 43 && p.verifier().length() <= 128, "verifier length " + p.verifier().length());
            assertTrue(BASE64URL.matcher(p.verifier()).matches());
            assertEquals(43, p.challenge().length(), "S256 challenge is 32 bytes base64url without padding");
            assertTrue(BASE64URL.matcher(p.challenge()).matches());
            assertEquals(McpOAuthPkce.challengeFor(p.verifier()), p.challenge());
        }
    }
}
