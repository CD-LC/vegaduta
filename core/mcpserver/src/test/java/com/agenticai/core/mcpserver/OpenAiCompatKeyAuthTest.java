package com.agenticai.core.mcpserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.openai.OpenAiCompatCallerScope;
import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link McpApiKeyAuthFilter} in OpenAI-compat mode (registered for {@code /v1/*} by {@link
 * OpenAiCompatKeyAuthConfig}): a {@code vmcp_} key is authenticated exactly as on {@code /mcp}, a
 * platform JWT passes through untouched, and rejections use OpenAI's error envelope.
 */
class OpenAiCompatKeyAuthTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private McpServerAccessGrantService grantService;
    private McpServerRateLimiter rateLimiter;
    private McpApiKeyAuthFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        grantService = mock(McpServerAccessGrantService.class);
        rateLimiter = mock(McpServerRateLimiter.class);
        filter = new McpApiKeyAuthFilter(grantService, rateLimiter, true, false);
        request = new MockHttpServletRequest("POST", "/v1/chat/completions");
        response = new MockHttpServletResponse();
        TenantContext.clear();
        McpCallerContextTestAccess.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        McpCallerContextTestAccess.clear();
    }

    private McpServerAccessGrant grantWithId(UUID id) {
        McpServerAccessGrant grant =
                new McpServerAccessGrant(
                        "sdk", McpApiKeyHasher.sha256Hex("vmcp_raw"), "rawrawra", "[]", "[]", "[]");
        ReflectionTestUtils.setField(grant, "id", id);
        return grant;
    }

    @Test
    void jwtBearerPassesThroughWithoutTouchingTheGrantService() throws Exception {
        request.addHeader("Authorization", "Bearer eyJhbGciOi.payload.sig");
        AtomicBoolean ran = new AtomicBoolean();
        filter.doFilter(request, response, (req, res) -> ran.set(true));
        assertTrue(ran.get());
        assertEquals(200, response.getStatus());
        verifyNoInteractions(grantService, rateLimiter);
    }

    @Test
    void noAuthorizationHeaderPassesThroughBecauseSpringSecurityOwnsThatRejection()
            throws Exception {
        AtomicBoolean ran = new AtomicBoolean();
        filter.doFilter(request, response, (req, res) -> ran.set(true));
        assertTrue(ran.get());
        verifyNoInteractions(grantService);
    }

    @Test
    void unknownKeyIsOpenAiShaped401() throws Exception {
        request.addHeader("Authorization", "Bearer vmcp_nope");
        when(grantService.resolveTenantSchema("vmcp_nope")).thenReturn(Optional.empty());
        filter.doFilter(
                request,
                response,
                (req, res) -> {
                    throw new AssertionError("must not reach the controller");
                });
        assertEquals(401, response.getStatus());
        JsonNode err = mapper.readTree(response.getContentAsString()).get("error");
        assertEquals("invalid_api_key", err.get("code").asText());
        assertEquals("invalid_request_error", err.get("type").asText());
        assertTrue(err.has("param") && err.has("message"));
    }

    @Test
    void rateLimitedKeyIs429WithRetryAfterInOpenAiShape() throws Exception {
        UUID grantId = UUID.randomUUID();
        request.addHeader("Authorization", "Bearer vmcp_raw");
        when(grantService.resolveTenantSchema("vmcp_raw")).thenReturn(Optional.of("tenant_alpha"));
        when(grantService.findByRawKeyWithinCurrentTenant("vmcp_raw"))
                .thenReturn(Optional.of(grantWithId(grantId)));
        when(rateLimiter.allow(grantId)).thenReturn(false);
        filter.doFilter(
                request,
                response,
                (req, res) -> {
                    throw new AssertionError("must not reach the controller");
                });
        assertEquals(429, response.getStatus());
        assertNotNull(response.getHeader("Retry-After"));
        assertEquals(
                "rate_limit_exceeded",
                mapper.readTree(response.getContentAsString()).get("error").get("code").asText());
        assertTrue(TenantContext.current().isEmpty());
    }

    @Test
    void validKeyExposesItsGrantScopeAndGrantSubjectToTheControllerThenClears() throws Exception {
        UUID grantId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        McpServerAccessGrant grant = grantWithId(grantId);
        request.addHeader("Authorization", "Bearer vmcp_raw");
        when(grantService.resolveTenantSchema("vmcp_raw")).thenReturn(Optional.of("tenant_alpha"));
        when(grantService.findByRawKeyWithinCurrentTenant("vmcp_raw"))
                .thenReturn(Optional.of(grant));
        when(rateLimiter.allow(grantId)).thenReturn(true);
        when(grantService.parseExposedAgentIds(grant)).thenReturn(Set.of(agentId));
        when(grantService.parseExposedWorkflowIds(grant)).thenReturn(Set.of());
        when(grantService.parseExposedKnowledgeCollectionIds(grant)).thenReturn(Set.of());

        OpenAiCompatCallerScope scope = new OpenAiCompatKeyAuthConfig().openAiCompatCallerScope();
        assertTrue(
                scope.exposedAgentIds().isEmpty(),
                "outside a key-authenticated request there is no grant scope");

        AtomicReference<Optional<Set<UUID>>> seenScope = new AtomicReference<>();
        AtomicReference<String> seenSubject = new AtomicReference<>();
        FilterChain chain =
                (req, res) -> {
                    seenScope.set(scope.exposedAgentIds());
                    seenSubject.set(
                            TenantContext.current().map(TenantContext::getSubject).orElse(null));
                };
        filter.doFilter(request, response, chain);

        assertEquals(Set.of(agentId), seenScope.get().orElseThrow());
        assertEquals("mcp-external:" + grantId, seenSubject.get());
        assertFalse(scope.exposedAgentIds().isPresent());
        assertTrue(TenantContext.current().isEmpty());
    }

    @Test
    void registrationCoversV1AndRunsAfterSpringSecurity() {
        var registration =
                new OpenAiCompatKeyAuthConfig()
                        .openAiCompatKeyAuthFilterRegistration(grantService, rateLimiter);
        assertTrue(registration.getUrlPatterns().contains("/v1/*"));
        assertEquals(100, registration.getOrder());
    }
}
