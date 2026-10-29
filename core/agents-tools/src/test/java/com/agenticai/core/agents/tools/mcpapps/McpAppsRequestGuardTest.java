package com.agenticai.core.agents.tools.mcpapps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** The {@code ui://} scan over tool arguments and the sliding one-minute rate window. */
class McpAppsRequestGuardTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode tree(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    @Test
    void plainArgumentsMentionNoUiUri() throws Exception {
        assertFalse(McpAppsRequestGuard.mentionsUiUri(tree("{}"), 0));
        assertFalse(McpAppsRequestGuard.mentionsUiUri(tree("{\"a\":1,\"b\":[true,null]}"), 0));
        assertFalse(
                McpAppsRequestGuard.mentionsUiUri(tree("{\"url\":\"https://x.example.com/ui\"}"), 0));
    }

    @Test
    void uiUriInValuesKeysAndArraysIsFound() throws Exception {
        assertTrue(McpAppsRequestGuard.mentionsUiUri(tree("{\"a\":\"ui://app/x\"}"), 0));
        assertTrue(McpAppsRequestGuard.mentionsUiUri(tree("{\"UI://app/x\":1}"), 0));
        assertTrue(
                McpAppsRequestGuard.mentionsUiUri(
                        tree("{\"a\":{\"b\":{\"c\":[\"ui://app/deep\"]}}}"), 0));
        assertTrue(
                McpAppsRequestGuard.mentionsUiUri(
                        tree("{\"url\":\"https://x.example.com/ui://y\"}"), 0),
                "the scan is substring-based: ui:// anywhere in a string counts");
    }

    @Test
    void pathologicalNestingIsRefusedRatherThanWalked() throws Exception {
        // 13 levels of wrapping: deeper than the guard ever walks.
        String json = "\"leaf\"";
        for (int i = 0; i < 13; i++) {
            json = "[" + json + "]";
        }
        assertTrue(McpAppsRequestGuard.mentionsUiUri(tree(json), 0));
    }

    @Test
    void theWindowAllowsUpToPerMinuteCallsThenRefuses() {
        McpAppsRequestGuard guard = new McpAppsRequestGuard();
        guard.rateLimit("app-1", 2);
        guard.rateLimit("app-1", 2);
        McpAppsException e =
                assertThrows(McpAppsException.class, () -> guard.rateLimit("app-1", 2));
        assertEquals(McpAppsException.Code.RATE_LIMITED, e.code());

        // Windows are keyed: another key is unaffected.
        guard.rateLimit("app-2", 2);
    }
}
