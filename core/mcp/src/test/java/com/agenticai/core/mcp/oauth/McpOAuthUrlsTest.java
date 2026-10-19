package com.agenticai.core.mcp.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class McpOAuthUrlsTest {

    @Test
    void canonicalFormLowercasesHostDropsDefaultPortAndTrailingSlash() {
        assertEquals("https://mcp.example.com/mcp", McpOAuthUrls.canonical("https://MCP.Example.com:443/mcp/"));
        assertEquals("https://mcp.example.com", McpOAuthUrls.canonical("https://mcp.example.com/"));
        assertEquals("https://mcp.example.com:8443/a/b", McpOAuthUrls.canonical(" https://mcp.example.com:8443/a/b "));
        assertEquals("https://h.example.com/mcp?x=1", McpOAuthUrls.canonical("https://h.example.com/mcp/?x=1"));
    }

    @Test
    void canonicalRejectsNonHttpsCredentialsFragmentsAndGarbage() {
        for (String bad : new String[] {null, "", "  ", "http://a.example.com/x", "ftp://a.example.com", "https://u:p@a.example.com/x",
            "https://a.example.com/x#f", "not a url", "https:///x", "//a.example.com/x"}) {
            assertThrows(McpOAuthException.class, () -> McpOAuthUrls.canonical(bad), String.valueOf(bad));
        }
    }

    @Test
    void coversMatchesSameOriginAndPathSegmentPrefixOnly() {
        assertTrue(McpOAuthUrls.covers("https://h.example.com/mcp", "https://h.example.com/mcp"));
        assertTrue(McpOAuthUrls.covers("https://h.example.com", "https://h.example.com/mcp"));
        assertTrue(McpOAuthUrls.covers("https://h.example.com/", "https://h.example.com/mcp/sse"));
        assertTrue(McpOAuthUrls.covers("https://h.example.com/mcp", "https://H.EXAMPLE.com:443/mcp/"));
        assertFalse(McpOAuthUrls.covers("https://h.example.com/mcp", "https://h.example.com/mcp-evil"));
        assertFalse(McpOAuthUrls.covers("https://h.example.com/mcp", "https://h.example.com/other"));
        assertFalse(McpOAuthUrls.covers("https://h.example.com/mcp", "https://h.example.com"));
        assertFalse(McpOAuthUrls.covers("https://h.example.com", "https://h.example.net/mcp"));
        assertFalse(McpOAuthUrls.covers("https://h.example.com", "https://h.example.com:8443/mcp"));
        assertFalse(McpOAuthUrls.covers("https://h.example.com", "http://h.example.com/mcp"));
        assertFalse(McpOAuthUrls.covers("https://h.example.com/mcp?tenant=a", "https://h.example.com/mcp?tenant=b"));
        assertFalse(McpOAuthUrls.covers("garbage", "https://h.example.com/mcp"));
    }

    @Test
    void candidateResourcesWalkUpToTheOrigin() {
        assertEquals(List.of("https://h.example.com/a/b", "https://h.example.com/a", "https://h.example.com"),
            McpOAuthUrls.candidateResources("https://H.example.com/a/b/"));
        assertEquals(List.of("https://h.example.com"), McpOAuthUrls.candidateResources("https://h.example.com"));
    }
}
