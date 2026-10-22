package com.agenticai.core.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantContext;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.FilterChain;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The OAuth 2.1 option of {@code /mcp}: the authenticator's token checks (signature, issuer,
 * expiry, audience binding), the scope rules (exact match, no wildcards), and the filter behaviour
 * (challenge with {@code resource_metadata}, no token passthrough, {@code vmcp_} keys unaffected).
 * Tokens are minted with a throwaway RSA key and validated by the same validators production wires
 * up.
 */
class McpOAuthTest {

    private static final String RESOURCE = "https://mcp.example.com/mcp";
    private static final String ISSUER = "https://auth.example.com/realms/vega";
    private static final String METADATA =
            "https://mcp.example.com/.well-known/oauth-protected-resource/mcp";

    private KeyPair keys;
    private McpOAuthAuthenticator authenticator;
    private McpServerAccessGrantService grantService;
    private McpServerRateLimiter rateLimiter;
    private McpApiKeyAuthFilter filter;
    private final UUID grantId = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keys = gen.generateKeyPair();
        NimbusJwtDecoder decoder =
                NimbusJwtDecoder.withPublicKey((RSAPublicKey) keys.getPublic()).build();
        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(
                        JwtValidators.createDefaultWithIssuer(ISSUER),
                        McpOAuthAuthenticator.audienceValidator(RESOURCE)));
        authenticator = new McpOAuthAuthenticator(decoder, RESOURCE, "tenant_id", "mcp_grant_id");

        grantService = mock(McpServerAccessGrantService.class);
        rateLimiter = mock(McpServerRateLimiter.class);
        when(rateLimiter.allow(any())).thenReturn(true);
        McpServerAccessGrant grant =
                new McpServerAccessGrant("oauth-grant", "hash", "abcd", "[]", "[]", "[]");
        ReflectionTestUtils.setField(grant, "id", grantId);
        when(grantService.findEnabledByIdWithinCurrentTenant(grantId))
                .thenReturn(Optional.of(grant));
        filter = new McpApiKeyAuthFilter(grantService, rateLimiter);
        filter.enableOAuth(authenticator, METADATA);
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        McpCallerContext.clear();
    }

    // ---- token minting -------------------------------------------------------

    private JWTClaimsSet.Builder claims() {
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(RESOURCE)
                .subject("user-1")
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .claim("tenant_id", "tenant_a")
                .claim("mcp_grant_id", grantId.toString())
                .claim("scope", "mcp:agents:read mcp:agents:call");
    }

    private String sign(JWTClaimsSet claims) throws JOSEException {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        jwt.sign(new RSASSASigner((RSAPrivateKey) keys.getPrivate()));
        return jwt.serialize();
    }

    private record Outcome(MockHttpServletResponse response, boolean reachedChain) {}

    private Outcome call(String bearer, String body) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.setContent(body.getBytes());
        request.setContentType("application/json");
        if (bearer != null) {
            request.addHeader("Authorization", "Bearer " + bearer);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] reached = {false};
        FilterChain chain =
                (req, res) -> {
                    reached[0] = true;
                    capture(req);
                };
        filter.doFilter(request, response, chain);
        return new Outcome(response, reached[0]);
    }

    private final java.util.List<String> downstreamAuthorization = new java.util.ArrayList<>();
    private final java.util.List<String> downstreamTenant = new java.util.ArrayList<>();

    private void capture(jakarta.servlet.ServletRequest req) {
        downstreamAuthorization.add(
                ((jakarta.servlet.http.HttpServletRequest) req).getHeader("Authorization"));
        downstreamTenant.add(TenantContext.current().map(TenantContext::getTenantId).orElse(null));
    }

    private static final String LIST_AGENTS =
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"list_my_agents\"}}";
    private static final String CALL_AGENT =
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"call_agent\"}}";
    private static final String SEARCH =
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"search_knowledge\"}}";

    // ---- authenticator ------------------------------------------------------

    @Test
    void validTokenAuthenticatesToTenantGrantAndScopes() throws Exception {
        var result = authenticator.authenticate(sign(claims().build()));
        assertThat(result.rejection()).isNull();
        assertThat(result.principal().tenantId()).isEqualTo("tenant_a");
        assertThat(result.principal().grantId()).isEqualTo(grantId);
        assertThat(result.principal().scopes())
                .containsExactlyInAnyOrder("mcp:agents:read", "mcp:agents:call");
    }

    @Test
    void tokenIssuedForAnotherResourceIsRejected_wrongAudience() throws Exception {
        String forOtherServer =
                sign(claims().audience("https://other-mcp.example.org/mcp").build());
        var result = authenticator.authenticate(forOtherServer);
        assertThat(result.rejection().status()).isEqualTo(401);
        assertThat(result.rejection().error()).isEqualTo("invalid_token");
    }

    @Test
    void tokenWithoutAnyAudienceIsRejected() throws Exception {
        JWTClaimsSet noAud =
                new JWTClaimsSet.Builder()
                        .issuer(ISSUER)
                        .subject("u")
                        .expirationTime(Date.from(Instant.now().plusSeconds(60)))
                        .claim("tenant_id", "tenant_a")
                        .claim("mcp_grant_id", grantId.toString())
                        .build();
        assertThat(authenticator.authenticate(sign(noAud)).rejection().status()).isEqualTo(401);
    }

    @Test
    void audienceMatchIsExactNotAPrefix() throws Exception {
        assertThat(
                        authenticator
                                .authenticate(sign(claims().audience(RESOURCE + "/evil").build()))
                                .rejection())
                .isNotNull();
        assertThat(
                        authenticator
                                .authenticate(
                                        sign(claims().audience("https://mcp.example.com").build()))
                                .rejection())
                .isNotNull();
        // Trailing slash / host case are the same resource.
        assertThat(
                        authenticator
                                .authenticate(
                                        sign(
                                                claims().audience("https://MCP.example.com/mcp/")
                                                        .build()))
                                .rejection())
                .isNull();
    }

    @Test
    void wrongIssuerExpiredAndForgedTokensAreRejected() throws Exception {
        assertThat(
                        authenticator
                                .authenticate(
                                        sign(claims().issuer("https://attacker.example").build()))
                                .rejection()
                                .status())
                .isEqualTo(401);
        assertThat(
                        authenticator
                                .authenticate(
                                        sign(
                                                claims().expirationTime(
                                                                Date.from(
                                                                        Instant.now()
                                                                                .minusSeconds(
                                                                                        3600)))
                                                        .build()))
                                .rejection()
                                .status())
                .isEqualTo(401);

        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair attacker = gen.generateKeyPair();
        SignedJWT forged = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims().build());
        forged.sign(new RSASSASigner((RSAPrivateKey) attacker.getPrivate()));
        assertThat(authenticator.authenticate(forged.serialize()).rejection().status())
                .isEqualTo(401);
        assertThat(authenticator.authenticate("not-a-jwt").rejection().status()).isEqualTo(401);
    }

    @Test
    void tokenNotLinkedToAGrantIsRefused() throws Exception {
        JWTClaimsSet noGrant =
                new JWTClaimsSet.Builder()
                        .issuer(ISSUER)
                        .audience(RESOURCE)
                        .subject("u")
                        .expirationTime(Date.from(Instant.now().plusSeconds(60)))
                        .claim("tenant_id", "tenant_a")
                        .build();
        assertThat(authenticator.authenticate(sign(noGrant)).rejection().status()).isEqualTo(403);
        JWTClaimsSet badGrant =
                new JWTClaimsSet.Builder()
                        .issuer(ISSUER)
                        .audience(RESOURCE)
                        .subject("u")
                        .expirationTime(Date.from(Instant.now().plusSeconds(60)))
                        .claim("tenant_id", "tenant_a")
                        .claim("mcp_grant_id", "nope")
                        .build();
        assertThat(authenticator.authenticate(sign(badGrant)).rejection().status()).isEqualTo(403);
    }

    // ---- scopes -------------------------------------------------------------

    @Test
    void wildcardScopesGrantNothing() throws Exception {
        String wildcard = sign(claims().claim("scope", "* mcp:* mcp:agents:*").build());
        Outcome o = call(wildcard, CALL_AGENT);
        assertThat(o.response().getStatus()).isEqualTo(403);
        assertThat(o.response().getHeader("WWW-Authenticate"))
                .contains("error=\"insufficient_scope\"")
                .contains("scope=\"mcp:agents:call\"")
                .contains("resource_metadata=\"" + METADATA + "\"");
        assertThat(o.reachedChain()).isFalse();
    }

    @Test
    void scopeIsRequiredPerTool() throws Exception {
        String token = sign(claims().build()); // agents:read + agents:call only
        assertThat(call(token, LIST_AGENTS).reachedChain()).isTrue();
        assertThat(call(token, CALL_AGENT).reachedChain()).isTrue();
        Outcome knowledge = call(token, SEARCH);
        assertThat(knowledge.response().getStatus()).isEqualTo(403);
        assertThat(knowledge.response().getHeader("WWW-Authenticate"))
                .contains("scope=\"mcp:knowledge:search\"");
        // An unknown tool needs the generic fallback scope, which a stock token does not carry.
        Outcome unknown =
                call(
                        token,
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"mystery\"}}");
        assertThat(unknown.response().getStatus()).isEqualTo(403);
    }

    @Test
    void batchNeedsEveryScopeAndListingNeedsNone() throws Exception {
        String token = sign(claims().build());
        String batch = "[" + LIST_AGENTS + "," + SEARCH + "]";
        assertThat(call(token, batch).response().getStatus()).isEqualTo(403);
        String list = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";
        assertThat(call(sign(claims().claim("scope", "").build()), list).reachedChain()).isTrue();
    }

    @Test
    void advertisedScopesContainNoWildcard() {
        assertThat(McpOAuthScopes.SUPPORTED).isNotEmpty().noneMatch(s -> s.contains("*"));
    }

    // ---- filter behaviour ---------------------------------------------------

    @Test
    void missingTokenGets401WithTheResourceMetadataChallenge() throws Exception {
        Outcome o = call(null, LIST_AGENTS);
        assertThat(o.response().getStatus()).isEqualTo(401);
        assertThat(o.response().getHeader("WWW-Authenticate"))
                .isEqualTo("Bearer resource_metadata=\"" + METADATA + "\"");
    }

    @Test
    void invalidTokenGets401InvalidTokenChallengeAndNeverReachesTheChain() throws Exception {
        Outcome o = call(sign(claims().audience("https://other.example/mcp").build()), LIST_AGENTS);
        assertThat(o.response().getStatus()).isEqualTo(401);
        assertThat(o.response().getHeader("WWW-Authenticate"))
                .contains("error=\"invalid_token\"")
                .contains(METADATA);
        assertThat(o.reachedChain()).isFalse();
        verifyNoInteractions(rateLimiter);
    }

    @Test
    void tokenIsNeverPassedDownstream() throws Exception {
        String token = sign(claims().build());
        Outcome o = call(token, LIST_AGENTS);
        assertThat(o.reachedChain()).isTrue();
        assertThat(downstreamAuthorization).containsOnlyNulls();
        assertThat(downstreamTenant).containsExactly("tenant_a");
    }

    @Test
    void disabledOrMissingGrantIs401() throws Exception {
        when(grantService.findEnabledByIdWithinCurrentTenant(grantId)).thenReturn(Optional.empty());
        assertThat(call(sign(claims().build()), LIST_AGENTS).response().getStatus()).isEqualTo(401);
    }

    @Test
    void grantKeysKeepWorkingWhenOAuthIsOn() throws Exception {
        McpServerAccessGrant grant =
                new McpServerAccessGrant(
                        "key-grant",
                        McpApiKeyHasher.sha256Hex("vmcp_abc"),
                        "vmcp_abc",
                        "[]",
                        "[]",
                        "[]");
        UUID keyGrantId = UUID.randomUUID();
        ReflectionTestUtils.setField(grant, "id", keyGrantId);
        when(grantService.resolveTenantSchema("vmcp_abc")).thenReturn(Optional.of("tenant_b"));
        when(grantService.findByRawKeyWithinCurrentTenant("vmcp_abc"))
                .thenReturn(Optional.of(grant));
        when(grantService.parseExposedAgentIds(any())).thenReturn(java.util.Set.of());
        when(grantService.parseExposedWorkflowIds(any())).thenReturn(java.util.Set.of());
        when(grantService.parseExposedKnowledgeCollectionIds(any())).thenReturn(java.util.Set.of());

        Outcome o = call("vmcp_abc", LIST_AGENTS);

        assertThat(o.reachedChain()).isTrue();
        assertThat(downstreamTenant).containsExactly("tenant_b");
    }

    // ---- protected resource metadata ----------------------------------------

    @Test
    void metadataDocumentNamesTheResourceAuthorizationServerAndScopes() throws Exception {
        var prm = new McpProtectedResourceMetadataFilter(RESOURCE, List.of(ISSUER));
        MockHttpServletResponse response = new MockHttpServletResponse();
        prm.doFilter(
                new MockHttpServletRequest("GET", "/.well-known/oauth-protected-resource/mcp"),
                response,
                (a, b) -> {});
        assertThat(response.getStatus()).isEqualTo(200);
        var doc = new tools.jackson.databind.ObjectMapper().readTree(response.getContentAsString());
        assertThat(doc.path("resource").asString()).isEqualTo(RESOURCE);
        assertThat(doc.path("authorization_servers").get(0).asString()).isEqualTo(ISSUER);
        assertThat(doc.path("scopes_supported").toString())
                .doesNotContain("*")
                .contains("mcp:agents:call");
        MockHttpServletResponse post = new MockHttpServletResponse();
        prm.doFilter(
                new MockHttpServletRequest("POST", "/.well-known/oauth-protected-resource"),
                post,
                (a, b) -> {});
        assertThat(post.getStatus()).isEqualTo(405);
    }

    @Test
    void metadataUrlInsertsTheResourcePathAfterTheWellKnownSegment() {
        assertThat(McpProtectedResourceMetadataFilter.metadataUrl("https://mcp.example.com/mcp"))
                .isEqualTo("https://mcp.example.com/.well-known/oauth-protected-resource/mcp");
        assertThat(McpProtectedResourceMetadataFilter.metadataUrl("https://mcp.example.com"))
                .isEqualTo("https://mcp.example.com/.well-known/oauth-protected-resource");
    }

    @Test
    void configRefusesPlainHttpAndMissingValues() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> McpOAuthConfig.requireHttpsOrLocal("http://mcp.example.com/mcp", "p"));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> McpOAuthConfig.requireHttpsOrLocal("", "p"));
        McpOAuthConfig.requireHttpsOrLocal("https://mcp.example.com/mcp", "p");
        McpOAuthConfig.requireHttpsOrLocal("http://localhost:8080/mcp", "p");
    }
}
