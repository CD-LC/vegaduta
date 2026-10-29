package com.agenticai.core.agents.tools.mcpapps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.tools.mcpapps.McpAppUiMeta.Declaration;
import com.agenticai.core.agents.tools.mcpapps.McpAppUiMeta.DeclaredCsp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The {@code _meta.ui} declaration parse: ui:// only, never a fetched URL, CSP lists screened. */
class McpAppUiMetaTest {

    @Test
    void isUiUriAcceptsOnlyTheUiScheme() {
        assertTrue(McpAppUiMeta.isUiUri("ui://app/main.html"));
        assertTrue(McpAppUiMeta.isUiUri("ui://a"));
        assertTrue(McpAppUiMeta.isUiUri("ui://app/x-y_z.w/~seg-1"));

        assertFalse(McpAppUiMeta.isUiUri(null));
        assertFalse(McpAppUiMeta.isUiUri(42));
        assertFalse(McpAppUiMeta.isUiUri("http://app/x"));
        assertFalse(McpAppUiMeta.isUiUri("https://app/x"));
        assertFalse(McpAppUiMeta.isUiUri("file:///etc/passwd"));
        assertFalse(McpAppUiMeta.isUiUri("javascript:alert(1)"));
        assertFalse(McpAppUiMeta.isUiUri("UI://app/x"), "the scheme is lowercase-exact");
        assertFalse(McpAppUiMeta.isUiUri("ui://"));
        assertFalse(McpAppUiMeta.isUiUri("ui://app/ spaces "));
        assertFalse(McpAppUiMeta.isUiUri("ui://" + "a".repeat(400)), "a length bound applies");
    }

    @Test
    void declarationOfReadsMetaUi() {
        Optional<Declaration> d =
                McpAppUiMeta.declarationOf(Map.of("ui", Map.of("resourceUri", "ui://app/main")));
        assertTrue(d.isPresent());
        assertEquals("ui://app/main", d.get().resourceUri());
        assertSame(DeclaredCsp.NONE, d.get().csp());
    }

    @Test
    void declarationOfReadsTheFlatLegacyKey() {
        Optional<Declaration> d =
                McpAppUiMeta.declarationOf(Map.of("ui/resourceUri", "ui://app/legacy"));
        assertTrue(d.isPresent());
        assertEquals("ui://app/legacy", d.get().resourceUri());
    }

    @Test
    void declarationOfIsEmptyForMissingOrMalformedMeta() {
        assertTrue(McpAppUiMeta.declarationOf(null).isEmpty());
        assertTrue(McpAppUiMeta.declarationOf(Map.of()).isEmpty());
        assertTrue(McpAppUiMeta.declarationOf(Map.of("ui", Map.of())).isEmpty());
        // A non-ui:// URI never produces a declaration: no descriptor can fetch an arbitrary URL.
        assertTrue(
                McpAppUiMeta.declarationOf(Map.of("ui", Map.of("resourceUri", "https://evil/x")))
                        .isEmpty());
        assertTrue(
                McpAppUiMeta.declarationOf(Map.of("ui/resourceUri", "data:text/html,x")).isEmpty());
    }

    @Test
    void cspListsAreParsedAndScreened() {
        Map<String, Object> meta =
                Map.of(
                        "ui",
                        Map.of(
                                "resourceUri",
                                "ui://app/main",
                                "csp",
                                Map.of(
                                        "connectDomains",
                                                List.of("https://api.example.com", " ", 7),
                                        "resourceDomains", List.of("cdn.example.com"))));
        Declaration d = McpAppUiMeta.declarationOf(meta).orElseThrow();

        assertEquals(List.of("https://api.example.com"), d.csp().connectDomains());
        assertEquals(List.of("cdn.example.com"), d.csp().resourceDomains());
        assertEquals(List.of(), d.csp().frameDomains());
        assertEquals(List.of(), d.csp().baseUriDomains());
    }

    @Test
    void aNonMapCspYieldsTheLockedDownDefault() {
        Map<String, Object> meta =
                Map.of("ui", Map.of("resourceUri", "ui://app/main", "csp", "malformed"));
        Declaration d = McpAppUiMeta.declarationOf(meta).orElseThrow();
        assertSame(DeclaredCsp.NONE, d.csp());
    }

    @Test
    void declaredCspNormalisesNullListsToEmpty() {
        DeclaredCsp csp = new DeclaredCsp(null, List.of("x"), null, null);
        assertEquals(List.of(), csp.connectDomains());
        assertEquals(List.of("x"), csp.resourceDomains());
        assertEquals(List.of(), csp.frameDomains());
        assertEquals(List.of(), csp.baseUriDomains());
    }
}
