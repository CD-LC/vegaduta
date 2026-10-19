package com.agenticai.core.agents.tools.mcpapps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class McpAppCspTest {

    private static final String CLOSED =
            "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; "
                    + "img-src data: https:; font-src data:; media-src data:; connect-src 'none'; frame-src 'none'; "
                    + "object-src 'none'; base-uri 'none'; form-action 'none'";

    private static McpAppUiMeta.DeclaredCsp ask(
            List<String> connect, List<String> resource, List<String> frame, List<String> base) {
        return new McpAppUiMeta.DeclaredCsp(connect, resource, frame, base);
    }

    @Test
    void theDefaultPolicyIsClosed() {
        assertEquals(CLOSED, McpAppCsp.build(null, List.of(), true));
        assertEquals(CLOSED, McpAppCsp.build(McpAppUiMeta.DeclaredCsp.NONE, null, true));
    }

    @Test
    void httpsImagesCanBeTurnedOff() {
        assertTrue(McpAppCsp.build(null, List.of(), false).contains("img-src data:;"));
        assertFalse(McpAppCsp.build(null, List.of(), false).contains("https:"));
    }

    @Test
    void aDeclaredCspIsNotWidenedWithoutAnAllowlist() {
        McpAppUiMeta.DeclaredCsp greedy =
                ask(
                        List.of("https://api.evil.example"),
                        List.of("https://cdn.evil.example"),
                        List.of("https://frame.evil.example"),
                        List.of("https://base.evil.example"));
        assertEquals(CLOSED, McpAppCsp.build(greedy, List.of(), true));
        assertEquals(CLOSED, McpAppCsp.build(greedy, List.of("other.example"), true));
    }

    @Test
    void onlyAllowlistedHttpsOriginsAreGranted() {
        McpAppUiMeta.DeclaredCsp d =
                ask(
                        List.of(
                                "https://api.good.example",
                                "https://api.evil.example",
                                "http://api.good.example",
                                "https://*.good.example"),
                        List.of("https://cdn.good.example"),
                        List.of(),
                        List.of());
        String csp = McpAppCsp.build(d, List.of("api.good.example", "*.good.example"), true);
        assertTrue(csp.contains("connect-src https://api.good.example;"), csp);
        assertFalse(csp.contains("evil"), csp);
        assertFalse(csp.contains("http://"), csp);
        assertTrue(csp.contains("script-src 'unsafe-inline' https://cdn.good.example;"), csp);
        // untouched directives stay closed
        assertTrue(csp.contains("frame-src 'none'"));
        assertTrue(csp.contains("base-uri 'none'"));
        assertTrue(csp.contains("form-action 'none'"));
        assertTrue(csp.contains("object-src 'none'"));
    }

    @Test
    void wildcardSuffixDoesNotMatchTheBareDomainOrALookalike() {
        McpAppUiMeta.DeclaredCsp d =
                ask(
                        List.of(
                                "https://good.example",
                                "https://notgood.example",
                                "https://x.good.example"),
                        List.of(),
                        List.of(),
                        List.of());
        String csp = McpAppCsp.build(d, List.of("*.good.example"), true);
        assertEquals("connect-src https://x.good.example", directive(csp, "connect-src"));
    }

    @Test
    void injectionAttemptsInDeclaredDomainsAreDropped() {
        McpAppUiMeta.DeclaredCsp d =
                ask(
                        List.of(
                                "https://a.good.example; script-src *",
                                "https://a.good.example 'unsafe-eval'",
                                "https://a.good.example/path",
                                "https://user@a.good.example",
                                "'self'",
                                "*",
                                "data:"),
                        List.of(),
                        List.of(),
                        List.of());
        String csp = McpAppCsp.build(d, List.of("a.good.example", "*.good.example"), true);
        assertEquals("connect-src 'none'", directive(csp, "connect-src"), csp);
        assertFalse(csp.contains("unsafe-eval"));
        assertFalse(csp.contains("script-src *"));
    }

    @Test
    void invalidAllowlistEntriesAreIgnored() {
        assertEquals(
                List.of("good.example", "*.good.example"),
                McpAppCsp.normalizeAllowlist(
                        List.of(
                                " Good.Example ",
                                "*.good.example",
                                "*",
                                "localhost",
                                "https://x.example",
                                "a b.example",
                                "")));
    }

    private static String directive(String csp, String name) {
        for (String part : csp.split("; ")) {
            if (part.startsWith(name)) {
                return part;
            }
        }
        return "";
    }
}
