package com.agenticai.core.mcpserver;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.mcpserver.expose.McpExposureRpcHandler;
import com.agenticai.core.security.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import java.nio.charset.StandardCharsets;
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
 * The auth filter's agents-as-MCP-tools hook (app.mcp-server.expose-agents): with no handler the
 * request is passed through byte-for-byte as before (the flag-off identity); with one, the body is
 * read once under a cap, the hook only ever runs AFTER authentication and the rate limit, and a
 * body the hook did not answer is replayed intact to the MCP transport.
 */
class McpApiKeyAuthFilterExposureTest {

    private static final String TOOLS_LIST = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";

    private McpServerAccessGrantService grantService;
    private McpServerRateLimiter rateLimiter;
    private McpExposureRpcHandler handler;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private final UUID grantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        grantService = mock(McpServerAccessGrantService.class);
        rateLimiter = mock(McpServerRateLimiter.class);
        handler = mock(McpExposureRpcHandler.class);
        request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("Authorization", "Bearer vmcp_raw");
        request.setContent(TOOLS_LIST.getBytes(StandardCharsets.UTF_8));
        response = new MockHttpServletResponse();
        TenantContext.clear();
        McpCallerContext.clear();

        McpServerAccessGrant grant = new McpServerAccessGrant(
            "ci", McpApiKeyHasher.sha256Hex("vmcp_raw"), "rawrawra", "[]", "[]", "[]");
        ReflectionTestUtils.setField(grant, "id", grantId);
        when(grantService.resolveTenantSchema("vmcp_raw")).thenReturn(Optional.of("tenant_alpha"));
        when(grantService.findByRawKeyWithinCurrentTenant("vmcp_raw")).thenReturn(Optional.of(grant));
        when(grantService.parseExposedAgentIds(grant)).thenReturn(Set.of());
        when(grantService.parseExposedWorkflowIds(grant)).thenReturn(Set.of());
        when(grantService.parseExposedKnowledgeCollectionIds(grant)).thenReturn(Set.of());
        when(rateLimiter.allow(grantId)).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        McpCallerContext.clear();
    }

    private McpApiKeyAuthFilter withHandler(int maxBody) {
        return new McpApiKeyAuthFilter(grantService, rateLimiter, false, false, handler, maxBody);
    }

    @Test
    void withoutAHandlerTheRequestReachesTheChainUntouched() throws Exception {
        AtomicReference<ServletRequest> seen = new AtomicReference<>();
        FilterChain chain = (req, res) -> seen.set(req);

        new McpApiKeyAuthFilter(grantService, rateLimiter).doFilter(request, response, chain);

        // Same instance: no wrapper, no body read - exactly the pre-feature behaviour.
        assertSame(request, seen.get());
        verifyNoInteractions(handler);
    }

    @Test
    void aNullHandlerInTheSixArgConstructorIsTheSameAsNoFeature() throws Exception {
        AtomicReference<ServletRequest> seen = new AtomicReference<>();
        FilterChain chain = (req, res) -> seen.set(req);

        new McpApiKeyAuthFilter(grantService, rateLimiter, false, false, null, 262144).doFilter(request, response, chain);

        assertSame(request, seen.get());
    }

    @Test
    void anAnsweredRequestNeverReachesTheTransportAndContextsAreCleared() throws Exception {
        AtomicReference<McpCallerContext> callerSeen = new AtomicReference<>();
        AtomicReference<TenantContext> tenantSeen = new AtomicReference<>();
        AtomicReference<byte[]> bodySeen = new AtomicReference<>();
        when(handler.tryHandle(any())).thenAnswer(invocation -> {
            callerSeen.set(McpCallerContext.current().orElse(null));
            tenantSeen.set(TenantContext.current().orElse(null));
            bodySeen.set(invocation.getArgument(0));
            return Optional.of("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[]}}");
        });
        AtomicBoolean chainRan = new AtomicBoolean(false);

        withHandler(262144).doFilter(request, response, (req, res) -> chainRan.set(true));

        assertFalse(chainRan.get());
        assertEquals(200, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/json"));
        assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[]}}", response.getContentAsString());
        assertArrayEquals(TOOLS_LIST.getBytes(StandardCharsets.UTF_8), bodySeen.get());
        // The hook sees the authenticated caller and tenant - never anything the client sent.
        assertNotNull(callerSeen.get());
        assertEquals(grantId, callerSeen.get().grantId());
        assertEquals("tenant_alpha", tenantSeen.get().getTenantId());
        assertTrue(McpCallerContext.current().isEmpty());
        assertTrue(TenantContext.current().isEmpty());
    }

    @Test
    void aRequestTheHookDeclinesIsReplayedIntactToTheTransport() throws Exception {
        when(handler.tryHandle(any())).thenReturn(Optional.empty());
        AtomicReference<byte[]> bodyAtTransport = new AtomicReference<>();
        AtomicReference<ServletRequest> seen = new AtomicReference<>();
        FilterChain chain = (req, res) -> {
            seen.set(req);
            bodyAtTransport.set(req.getInputStream().readAllBytes());
        };

        withHandler(262144).doFilter(request, response, chain);

        assertArrayEquals(TOOLS_LIST.getBytes(StandardCharsets.UTF_8), bodyAtTransport.get());
        assertEquals(200, response.getStatus());
    }

    @Test
    void anOversizedBodyIs413AndNeverReachesTheHookOrTheTransport() throws Exception {
        AtomicBoolean chainRan = new AtomicBoolean(false);

        withHandler(10).doFilter(request, response, (req, res) -> chainRan.set(true));

        assertEquals(413, response.getStatus());
        assertFalse(chainRan.get());
        verify(handler, never()).tryHandle(any());
        assertTrue(TenantContext.current().isEmpty());
    }

    @Test
    void aRateLimitedKeyNeverReachesTheHook() throws Exception {
        when(rateLimiter.allow(grantId)).thenReturn(false);

        withHandler(262144).doFilter(request, response, (req, res) -> {
            throw new AssertionError("chain must not run");
        });

        assertEquals(429, response.getStatus());
        verify(handler, never()).tryHandle(any());
    }

    @Test
    void aBadKeyNeverReachesTheHook() throws Exception {
        when(grantService.resolveTenantSchema("vmcp_raw")).thenReturn(Optional.empty());

        withHandler(262144).doFilter(request, response, (req, res) -> {
            throw new AssertionError("chain must not run");
        });

        assertEquals(401, response.getStatus());
        verifyNoInteractions(handler);
    }

    @Test
    void nonPostRequestsAreNotInspected() throws Exception {
        MockHttpServletRequest get = new MockHttpServletRequest("GET", "/mcp");
        get.addHeader("Authorization", "Bearer vmcp_raw");
        AtomicBoolean chainRan = new AtomicBoolean(false);

        withHandler(262144).doFilter(get, response, (req, res) -> chainRan.set(true));

        assertTrue(chainRan.get());
        verifyNoInteractions(handler);
    }
}
