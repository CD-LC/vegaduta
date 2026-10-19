package com.agenticai.core.mcp.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.mcp.McpRemoteUrlGuard;
import java.util.List;
import org.junit.jupiter.api.Test;

class McpOAuthClientMetadataTest {

    private static final String ID = "https://api.example.com/oauth/mcp/remote/client-metadata.json";
    private static final String CB = "https://api.example.com/oauth/mcp/remote/callback";
    private static final String PATH = McpOAuthClientMetadata.DEFAULT_PATH;
    private final McpRemoteUrlGuard guard = new McpRemoteUrlGuard();

    private McpOAuthClientMetadata make(boolean enabled, String id, String path, String redirect) {
        return new McpOAuthClientMetadata(guard, enabled, id, path, "VegaDuta", "https://vegaduta.ai", redirect);
    }

    @Test
    void offWhenNotEnabled() {
        McpOAuthClientMetadata off = make(false, ID, PATH, CB);
        assertFalse(off.active());
        assertEquals("disabled", off.inactiveReason().orElseThrow());
    }

    @Test
    void activeWithAValidConfigurationAndPublishesExactlyOneRedirectUri() {
        McpOAuthClientMetadata m = make(true, ID, PATH, CB);
        assertTrue(m.active());
        assertEquals(ID, m.document().get("client_id"));
        assertEquals(List.of(CB), m.document().get("redirect_uris"));
        assertEquals("none", m.document().get("token_endpoint_auth_method"));
        assertEquals("https://vegaduta.ai", m.document().get("client_uri"));
        assertFalse(m.document().containsKey("client_secret"));
    }

    @Test
    void everyMisconfigurationKeepsItInactive() {
        List<McpOAuthClientMetadata> bad = List.of(
            make(true, "", PATH, CB),
            make(true, "http://api.example.com/oauth/mcp/remote/client-metadata.json", PATH, CB),
            make(true, "https://localhost/oauth/mcp/remote/client-metadata.json", PATH, CB),
            make(true, "https://127.0.0.1/oauth/mcp/remote/client-metadata.json", PATH, CB),
            make(true, "https://10.0.0.5/oauth/mcp/remote/client-metadata.json", PATH, CB),
            make(true, "https://u:p@api.example.com/oauth/mcp/remote/client-metadata.json", PATH, CB),
            make(true, ID + "?x=1", PATH, CB),
            make(true, ID + "#frag", PATH, CB),
            make(true, "https://api.example.com", PATH, CB),
            make(true, "https://api.example.com/oauth/mcp/remote/../x.json", PATH, CB),
            // path not under the public prefix / not equal to the client id's path
            make(true, "https://api.example.com/cimd.json", "/cimd.json", CB),
            make(true, ID, "/api/anything.json", CB),
            make(true, "https://api.example.com/oauth/mcp/remote/other.json", PATH, CB),
            // redirect URI must be strict: https, public, no wildcard
            make(true, ID, PATH, ""),
            make(true, ID, PATH, "http://api.example.com/oauth/mcp/remote/callback"),
            make(true, ID, PATH, "https://localhost/oauth/mcp/remote/callback"),
            make(true, ID, PATH, "https://*.example.com/cb"),
            make(true, ID, PATH, "https://api.example.com/cb#x"));
        for (McpOAuthClientMetadata m : bad) {
            assertFalse(m.active(), m.inactiveReason().orElse("?"));
            assertTrue(m.inactiveReason().isPresent());
        }
    }

    @Test
    void strictRedirectUriRule() {
        assertTrue(McpOAuthClientMetadata.isStrictRedirectUri(CB, guard));
        assertFalse(McpOAuthClientMetadata.isStrictRedirectUri("http://api.example.com/cb", guard));
        assertFalse(McpOAuthClientMetadata.isStrictRedirectUri("https://127.0.0.1/cb", guard));
        assertFalse(McpOAuthClientMetadata.isStrictRedirectUri("https://[::1]/cb", guard));
        assertFalse(McpOAuthClientMetadata.isStrictRedirectUri("https://2130706433/cb", guard));
        assertFalse(McpOAuthClientMetadata.isStrictRedirectUri("myapp://callback", guard));
        assertFalse(McpOAuthClientMetadata.isStrictRedirectUri(null, guard));
    }
}
