package com.agenticai.core.mcp.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.mcp.McpRemoteUrlGuard;
import com.agenticai.core.mcp.oauth.McpOAuthDiscovery.Discovered;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpOAuthDiscoveryTest {

    private static final String MCP = "https://mcp.example.com/v1/mcp";
    private static final String ISSUER = "https://auth.example.com";
    private static final String PRM_PATH = "https://mcp.example.com/.well-known/oauth-protected-resource/v1/mcp";
    private static final String AS_URL = "https://auth.example.com/.well-known/oauth-authorization-server";

    private static final String PRM = "{\"resource\":\"" + MCP + "\",\"authorization_servers\":[\"" + ISSUER
        + "\"],\"scopes_supported\":[\"read\",\"write\"]}";
    private static final String AS = "{\"issuer\":\"" + ISSUER + "\",\"authorization_endpoint\":\"" + ISSUER
        + "/authorize\",\"token_endpoint\":\"" + ISSUER + "/token\",\"registration_endpoint\":\"" + ISSUER
        + "/register\",\"code_challenge_methods_supported\":[\"S256\"]}";

    private final FakeMcpOAuthHttp http = new FakeMcpOAuthHttp();
    private final McpOAuthDiscovery discovery = new McpOAuthDiscovery(http, new ObjectMapper());

    @Test
    void discoversViaPathAwareWellKnownWhenNoChallenge() {
        http.on("GET", PRM_PATH, 200, PRM).on("GET", AS_URL, 200, AS);
        Discovered d = discovery.discover(MCP);
        assertEquals(MCP, d.resource().resource());
        assertEquals(List.of("read", "write"), d.resource().scopesSupported());
        assertEquals(ISSUER + "/token", d.authServer().tokenEndpoint());
        assertEquals(ISSUER + "/register", d.authServer().registrationEndpoint());
    }

    @Test
    void prefersResourceMetadataFromWwwAuthenticateChallenge() {
        String custom = "https://mcp.example.com/custom-prm.json";
        http.on("POST", MCP, 401, Map.of("www-authenticate", "Bearer resource_metadata=\"" + custom + "\""), "")
            .on("GET", custom, 200, PRM)
            .on("GET", AS_URL, 200, AS);
        Discovered d = discovery.discover(MCP);
        assertEquals(ISSUER, d.authServer().issuer());
        assertEquals(0, http.count("GET", PRM_PATH));
    }

    @Test
    void fallsBackToOpenIdConfigurationForAuthServer() {
        http.on("GET", PRM_PATH, 200, PRM).on("GET", ISSUER + "/.well-known/openid-configuration", 200, AS);
        assertEquals(ISSUER + "/authorize", discovery.discover(MCP).authServer().authorizationEndpoint());
    }

    @Test
    void failsWhenNoProtectedResourceMetadata() {
        McpOAuthException e = assertThrows(McpOAuthException.class, () -> discovery.discover(MCP));
        assertTrue(e.getMessage().contains("RFC 9728"));
    }

    @Test
    void rejectsResourceOnDifferentOrigin() {
        http.on("GET", PRM_PATH, 200,
            "{\"resource\":\"https://evil.example.net/mcp\",\"authorization_servers\":[\"" + ISSUER + "\"]}");
        assertThrows(McpOAuthException.class, () -> discovery.discover(MCP));
    }

    @Test
    void rejectsIssuerMismatch() {
        http.on("GET", PRM_PATH, 200, PRM)
            .on("GET", AS_URL, 200, AS.replace("\"issuer\":\"" + ISSUER + "\"", "\"issuer\":\"https://other.example.com\""));
        assertThrows(McpOAuthException.class, () -> discovery.discover(MCP));
    }

    @Test
    void rejectsAuthServerWithoutS256() {
        http.on("GET", PRM_PATH, 200, PRM)
            .on("GET", AS_URL, 200, AS.replace("[\"S256\"]", "[\"plain\"]"));
        McpOAuthException e = assertThrows(McpOAuthException.class, () -> discovery.discover(MCP));
        assertTrue(e.getMessage().contains("S256"));
    }

    @Test
    void rejectsNonHttpsEndpoints() {
        http.on("GET", PRM_PATH, 200, PRM)
            .on("GET", AS_URL, 200, AS.replace(ISSUER + "/token", "http://auth.example.com/token"));
        assertThrows(McpOAuthException.class, () -> discovery.discover(MCP));
        assertThrows(McpOAuthException.class, () -> discovery.discover("http://mcp.example.com/mcp"));
    }

    @Test
    void wellKnownInsertsSuffixBetweenHostAndPath() {
        assertEquals("https://a.example.com/.well-known/oauth-authorization-server/tenant1",
            McpOAuthDiscovery.wellKnown("https://a.example.com/tenant1/", "oauth-authorization-server", true));
        assertEquals("https://a.example.com:8443/.well-known/x",
            McpOAuthDiscovery.wellKnown("https://a.example.com:8443/tenant1", "x", false));
    }

    @Test
    void productionHttpRefusesInternalTargetsBeforeAnyNetworkCall() {
        JdkMcpOAuthHttp real = new JdkMcpOAuthHttp(McpRemoteUrlGuard.withDenylist(List.of("postgres")), 2, 4096);
        assertThrows(McpOAuthException.class, () -> real.send("GET", "https://127.0.0.1/x", Map.of(), null));
        assertThrows(McpOAuthException.class, () -> real.send("GET", "https://169.254.169.254/latest", Map.of(), null));
        assertThrows(McpOAuthException.class, () -> real.send("GET", "https://postgres/x", Map.of(), null));
        assertThrows(McpOAuthException.class, () -> real.send("GET", "http://mcp.example.com/x", Map.of(), null));
    }

    @Test
    void blockedProbeSurfacesAsBlockedNotAsMissingMetadata() {
        McpOAuthHttp blocking = (m, u, h, b) -> {
            throw new McpOAuthBlockedException("MCP OAuth request blocked: private address");
        };
        McpOAuthDiscovery d = new McpOAuthDiscovery(blocking, new ObjectMapper());
        assertThrows(McpOAuthBlockedException.class, () -> d.discover(MCP));
    }

    @Test
    void nonBlockedProbeFailureFallsThroughToWellKnown() {
        McpOAuthHttp flaky = new McpOAuthHttp() {
            @Override
            public Response send(String method, String url, Map<String, String> headers, String body) {
                if ("POST".equals(method)) {
                    throw new McpOAuthException("Could not reach " + url + " (connection reset)");
                }
                return url.equals(PRM_PATH) ? new Response(200, Map.of(), PRM)
                    : url.equals(AS_URL) ? new Response(200, Map.of(), AS) : new Response(404, Map.of(), "");
            }
        };
        assertEquals(MCP, new McpOAuthDiscovery(flaky, new ObjectMapper()).discover(MCP).resource().resource());
    }

    @Test
    void probeReportsStatusChallengeScopeAndResourceMetadata() {
        http.on("POST", MCP, 401, Map.of("www-authenticate",
            "Bearer realm=\"x\", resource_metadata=\"https://mcp.example.com/prm.json\", scope=\"files:read files:write\""), "");
        McpOAuthDiscovery.Probe probe = discovery.probe(MCP);
        assertTrue(probe.authRequired());
        assertEquals("https://mcp.example.com/prm.json", probe.resourceMetadataUrl());
        assertEquals("files:read files:write", probe.challengeScope());

        FakeMcpOAuthHttp open = new FakeMcpOAuthHttp().on("POST", MCP, 200, "{}");
        McpOAuthDiscovery.Probe noAuth = new McpOAuthDiscovery(open, new ObjectMapper()).probe(MCP);
        assertEquals(200, noAuth.status());
        assertEquals(false, noAuth.authRequired());
    }

    @Test
    void challengeScopeBeatsScopesSupportedAndAnOtherParameterNamedLikeScopeIsIgnored() {
        http.on("POST", MCP, 401, Map.of("www-authenticate", "Bearer error_scope=\"nope\", scope=\"a b\""), "")
            .on("GET", PRM_PATH, 200, PRM).on("GET", AS_URL, 200, AS);
        assertEquals(List.of("a", "b"), discovery.discover(MCP).resource().allowedScopes());
        FakeMcpOAuthHttp plain = new FakeMcpOAuthHttp().on("GET", PRM_PATH, 200, PRM).on("GET", AS_URL, 200, AS);
        assertEquals(List.of("read", "write"), new McpOAuthDiscovery(plain, new ObjectMapper()).discover(MCP).resource().allowedScopes());
    }

    @Test
    void parsesCimdAndIssParameterSupportFlags() {
        http.on("GET", PRM_PATH, 200, PRM).on("GET", AS_URL, 200, AS);
        assertEquals(false, discovery.discover(MCP).authServer().clientIdMetadataDocumentSupported());
        assertEquals(false, discovery.discover(MCP).authServer().issParameterSupported());
        http.on("GET", AS_URL, 200, AS.replace("\"code_challenge", "\"client_id_metadata_document_supported\":true,"
            + "\"authorization_response_iss_parameter_supported\":true,\"code_challenge"));
        assertEquals(true, discovery.discover(MCP).authServer().clientIdMetadataDocumentSupported());
        assertEquals(true, discovery.discover(MCP).authServer().issParameterSupported());
    }

    @Test
    void resourceMustCoverTheServerUrlNotMerelyShareItsOrigin() {
        http.on("GET", PRM_PATH, 200, PRM.replace(MCP, "https://mcp.example.com/v1/other"));
        assertThrows(McpOAuthException.class, () -> discovery.discover(MCP));
        http.on("GET", PRM_PATH, 200, PRM.replace(MCP, "https://mcp.example.com"));
        http.on("GET", AS_URL, 200, AS);
        assertEquals("https://mcp.example.com", discovery.discover(MCP).resource().resource());
    }

    @Test
    void endpointsOnInternalAddressesAreRefusedAtDiscoveryWhenTheTransportVetsUrls() {
        McpOAuthHttp vetting = new McpOAuthHttp() {
            @Override
            public Response send(String method, String url, Map<String, String> headers, String body) {
                return url.equals(PRM_PATH) ? new Response(200, Map.of(), PRM)
                    : url.equals(AS_URL) ? new Response(200, Map.of(), AS.replace(ISSUER + "/token", "https://10.0.0.9/token"))
                    : new Response(404, Map.of(), "");
            }

            @Override
            public java.util.Optional<String> checkUrl(String url) {
                return new McpRemoteUrlGuard().check(url);
            }
        };
        McpOAuthBlockedException e = assertThrows(McpOAuthBlockedException.class,
            () -> new McpOAuthDiscovery(vetting, new ObjectMapper()).discover(MCP));
        assertTrue(e.getMessage().contains("token_endpoint"));
    }
}
