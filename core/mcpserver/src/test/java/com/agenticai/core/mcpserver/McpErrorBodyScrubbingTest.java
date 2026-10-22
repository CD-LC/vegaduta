package com.agenticai.core.mcpserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpError;
import jakarta.servlet.FilterChain;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * B11: the MCP transport answers e.g. a missing Mcp-Session-Id with a 400 whose body is a
 * Jackson-serialized Java exception - stackTrace, class names, line numbers. Through the real auth
 * filter that body must now be a plain JSON-RPC error.
 */
class McpErrorBodyScrubbingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private McpApiKeyAuthFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        McpServerAccessGrantService grants = mock(McpServerAccessGrantService.class);
        McpServerRateLimiter limiter = mock(McpServerRateLimiter.class);
        UUID grantId = UUID.randomUUID();
        McpServerAccessGrant grant =
                new McpServerAccessGrant(
                        "ci", McpApiKeyHasher.sha256Hex("vmcp_raw"), "rawrawra", "[]", "[]", "[]");
        ReflectionTestUtils.setField(grant, "id", grantId);
        when(grants.resolveTenantSchema("vmcp_raw")).thenReturn(Optional.of("tenant_alpha"));
        when(grants.findByRawKeyWithinCurrentTenant("vmcp_raw")).thenReturn(Optional.of(grant));
        when(limiter.allow(grantId)).thenReturn(true);
        when(grants.parseExposedAgentIds(grant)).thenReturn(Set.of());
        when(grants.parseExposedWorkflowIds(grant)).thenReturn(Set.of());
        when(grants.parseExposedKnowledgeCollectionIds(grant)).thenReturn(Set.of());
        filter = new McpApiKeyAuthFilter(grants, limiter);
        request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("Authorization", "Bearer vmcp_raw");
        response = new MockHttpServletResponse();
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
        McpCallerContext.clear();
    }

    /** What the transport does: status 400 and the exception object serialized as the body. */
    private FilterChain transportAnswering400WithSerializedException() {
        return (req, res) -> {
            ((jakarta.servlet.http.HttpServletResponse) res).setStatus(400);
            res.setContentType("application/json");
            res.getOutputStream()
                    .write(MAPPER.writeValueAsBytes(new McpError("Session ID missing")));
        };
    }

    @Test
    void aSerializedExceptionBodyIsReplacedByAPlainJsonRpcError() throws Exception {
        // sanity: the transport's body really does carry the trace this fix exists to remove
        assertTrue(
                new String(MAPPER.writeValueAsBytes(new McpError("x")), StandardCharsets.UTF_8)
                        .contains("stackTrace"));

        filter.doFilter(request, response, transportAnswering400WithSerializedException());

        assertEquals(400, response.getStatus());
        String body = response.getContentAsString();
        assertFalse(body.contains("stackTrace"), body);
        assertFalse(body.contains("McpError"), body);
        assertFalse(body.contains("java."), body);
        assertFalse(body.contains("lineNumber"), body);
        JsonNode json = MAPPER.readTree(body);
        assertEquals("2.0", json.path("jsonrpc").asText());
        assertEquals(-32600, json.path("error").path("code").asInt());
        assertEquals("Session ID missing", json.path("error").path("message").asText());
        assertEquals(response.getContentAsByteArray().length, response.getContentLength());
    }

    @Test
    void aSuccessResponseIsNotBufferedOrAltered() throws Exception {
        FilterChain chain =
                (req, res) -> {
                    res.setContentType("application/json");
                    res.getOutputStream()
                            .write(
                                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}"
                                            .getBytes(StandardCharsets.UTF_8));
                };
        filter.doFilter(request, response, chain);
        assertEquals(200, response.getStatus());
        assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}", response.getContentAsString());
    }

    @Test
    void anErrorBodyThatIsNotASerializedExceptionIsForwardedAsIs() throws Exception {
        FilterChain chain =
                (req, res) -> {
                    ((jakarta.servlet.http.HttpServletResponse) res).setStatus(429);
                    res.getOutputStream()
                            .write("{\"error\":\"rate limited\"}".getBytes(StandardCharsets.UTF_8));
                };
        filter.doFilter(request, response, chain);
        assertEquals(429, response.getStatus());
        assertEquals("{\"error\":\"rate limited\"}", response.getContentAsString());
    }

    @Test
    void aMessageNamingAJavaClassIsNotEchoed() {
        byte[] body =
                "{\"message\":\"java.lang.IllegalStateException: boom\",\"stackTrace\":[]}"
                        .getBytes(StandardCharsets.UTF_8);
        String out = new String(McpErrorScrubbingResponse.scrub(body, 400), StandardCharsets.UTF_8);
        assertFalse(out.contains("IllegalStateException"), out);
        assertTrue(out.contains("Bad request"), out);
    }

    @Test
    void aServerErrorNeverEchoesItsMessage() {
        byte[] body =
                "{\"message\":\"NPE at Foo.bar\",\"stackTrace\":[]}"
                        .getBytes(StandardCharsets.UTF_8);
        String out = new String(McpErrorScrubbingResponse.scrub(body, 500), StandardCharsets.UTF_8);
        assertTrue(out.contains("Internal error"), out);
        assertFalse(out.contains("Foo"), out);
    }
}
