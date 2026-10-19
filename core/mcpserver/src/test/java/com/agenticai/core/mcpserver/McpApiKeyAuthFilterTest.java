package com.agenticai.core.mcpserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantContext;
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
 * {@link McpApiKeyAuthFilter} is the single authentication gate for the
 * platform's outward-facing developer surface ({@code /mcp/*} and {@code
 * /api/dev/*}) - deliberately OUTSIDE Spring Security's chain (those paths
 * are {@code permitAll()} there), so nothing else rejects a bad key if this
 * filter doesn't. The suite pins the full contract: every rejection path
 * short-circuits before the chain, the happy path populates BOTH
 * {@link TenantContext} (grant-traceable subject + externalTriggerId) and
 * {@link McpCallerContext} (the per-grant capability sets), and both are
 * cleared afterwards even though the servlet thread is pooled and reused.
 *
 * <p>DB-free Mockito throughout ({@code McpServerAccessGrantService}/{@code
 * McpServerRateLimiter} mocked, servlet plumbing via Spring's mock web
 * types) - the two-step control_plane/tenant-schema resolution itself is
 * exercised in {@code McpServerAccessGrantServiceTest}.
 */
class McpApiKeyAuthFilterTest {

    private McpServerAccessGrantService grantService;
    private McpServerRateLimiter rateLimiter;
    private McpApiKeyAuthFilter filter;

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        grantService = mock(McpServerAccessGrantService.class);
        rateLimiter = mock(McpServerRateLimiter.class);
        filter = new McpApiKeyAuthFilter(grantService, rateLimiter);
        request = new MockHttpServletRequest("POST", "/mcp");
        response = new MockHttpServletResponse();
        TenantContext.clear();
        McpCallerContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        McpCallerContext.clear();
    }

    /** Real grant entity (house style: "entity stays a dumb persistence shape") with the JPA-generated id backfilled via reflection, same idiom as {@code WorkflowEventOutboxRelayTest}. */
    private McpServerAccessGrant grantWithId(UUID grantId) {
        McpServerAccessGrant grant = new McpServerAccessGrant(
            "ci-integration", McpApiKeyHasher.sha256Hex("vmcp_raw"), "rawrawra", "[]", "[]", "[]");
        ReflectionTestUtils.setField(grant, "id", grantId);
        return grant;
    }

    private FilterChain neverReachedChain() {
        return (req, res) -> {
            throw new AssertionError("filter chain must not run for a rejected request");
        };
    }

    // ---- rejection paths ----------------------------------------------------

    @Test
    void missingAuthorizationHeaderIs401AndNeverTouchesTheGrantService() {
        assertTrue(assertRejected(neverReachedChain(), 401).contains("Authorization"));
        // No lookup means no timing/side-channel work for anonymous probes.
        verifyNoInteractions(grantService, rateLimiter);
    }

    @Test
    void nonBearerAuthorizationHeaderIs401() {
        request.addHeader("Authorization", "Basic dXNlcjpwYXNz");
        assertRejected(neverReachedChain(), 401);
        verifyNoInteractions(grantService);
    }

    @Test
    void emptyBearerTokenIs401() {
        request.addHeader("Authorization", "Bearer   ");
        assertRejected(neverReachedChain(), 401);
        verifyNoInteractions(grantService);
    }

    @Test
    void unknownKeyIs401AndNeverReachesTheTenantSchemaLookup() {
        request.addHeader("Authorization", "Bearer vmcp_unknown");
        when(grantService.resolveTenantSchema("vmcp_unknown")).thenReturn(Optional.empty());

        String body = assertRejected(neverReachedChain(), 401);

        // Deliberately the same generic message as the disabled-grant case -
        // an attacker probing keys must not learn WHICH step failed.
        assertTrue(body.contains("Invalid API key"));
        verify(grantService, never()).findByRawKeyWithinCurrentTenant(anyString());
        assertTrue(TenantContext.current().isEmpty(), "no TenantContext may leak from a rejected request");
    }

    @Test
    void keyWhoseGrantWasDisabledIs401EvenThoughTheSchemaStillResolves() {
        // "Step 2 is not just a formality" (filter javadoc): a grant disabled
        // or deleted while its control_plane link still resolves must still
        // be rejected - this is the revoke/disable kill switch actually
        // working on the hot path.
        request.addHeader("Authorization", "Bearer vmcp_disabled");
        when(grantService.resolveTenantSchema("vmcp_disabled")).thenReturn(Optional.of("tenant_alpha"));
        when(grantService.findByRawKeyWithinCurrentTenant("vmcp_disabled")).thenReturn(Optional.empty());

        String body = assertRejected(neverReachedChain(), 401);

        assertTrue(body.contains("Invalid API key"));
        assertTrue(TenantContext.current().isEmpty(),
            "the interim TenantContext set for the step-2 lookup must be cleared on rejection");
        verifyNoInteractions(rateLimiter);
    }

    @Test
    void rateLimitedGrantIs429() {
        UUID grantId = UUID.randomUUID();
        request.addHeader("Authorization", "Bearer vmcp_raw");
        when(grantService.resolveTenantSchema("vmcp_raw")).thenReturn(Optional.of("tenant_alpha"));
        when(grantService.findByRawKeyWithinCurrentTenant("vmcp_raw")).thenReturn(Optional.of(grantWithId(grantId)));
        when(rateLimiter.allow(grantId)).thenReturn(false);

        String body = assertRejected(neverReachedChain(), 429);

        assertTrue(body.contains("Rate limit"));
        assertTrue(TenantContext.current().isEmpty());
        assertTrue(McpCallerContext.current().isEmpty());
    }

    @Test
    void rejectionBodyIsJsonWithQuotesNeutralized() throws Exception {
        request.addHeader("Authorization", "Bearer vmcp_unknown");
        when(grantService.resolveTenantSchema(anyString())).thenReturn(Optional.empty());

        filter.doFilter(request, response, neverReachedChain());

        assertEquals("application/json", response.getContentType());
        // The hand-built body must stay parseable JSON - a quote slipping
        // through the replace() would corrupt every client's error handling.
        new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getContentAsString());
    }

    // ---- happy path ---------------------------------------------------------

    @Test
    void validKeyRunsTheChainWithBothContextsPopulated() throws Exception {
        UUID grantId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();
        UUID collectionId = UUID.randomUUID();
        McpServerAccessGrant grant = grantWithId(grantId);
        request.addHeader("Authorization", "Bearer vmcp_raw");
        when(grantService.resolveTenantSchema("vmcp_raw")).thenReturn(Optional.of("tenant_alpha"));
        when(grantService.findByRawKeyWithinCurrentTenant("vmcp_raw")).thenReturn(Optional.of(grant));
        when(rateLimiter.allow(grantId)).thenReturn(true);
        when(grantService.parseExposedAgentIds(grant)).thenReturn(Set.of(agentId));
        when(grantService.parseExposedWorkflowIds(grant)).thenReturn(Set.of(workflowId));
        when(grantService.parseExposedKnowledgeCollectionIds(grant)).thenReturn(Set.of(collectionId));

        AtomicReference<TenantContext> tenantSeen = new AtomicReference<>();
        AtomicReference<McpCallerContext> callerSeen = new AtomicReference<>();
        AtomicBoolean chainRan = new AtomicBoolean(false);
        FilterChain chain = (req, res) -> {
            chainRan.set(true);
            tenantSeen.set(TenantContext.current().orElse(null));
            callerSeen.set(McpCallerContext.current().orElse(null));
        };

        filter.doFilter(request, response, chain);

        assertTrue(chainRan.get());
        assertEquals(200, response.getStatus());

        TenantContext tenant = tenantSeen.get();
        assertNotNull(tenant, "downstream must see a TenantContext (schema routing + kill-switch depend on it)");
        assertEquals("tenant_alpha", tenant.getTenantId());
        // Grant-traceable principal, not the generic bootstrap literal - the
        // 2026-07-20 data-governance contract: subject names the exact grant,
        // externalTriggerId is the typed seam tool_call_log attribution reads.
        assertEquals("mcp-external:" + grantId, tenant.getSubject());
        assertEquals(grantId.toString(), tenant.getExternalTriggerId());
        assertTrue(tenant.hasRole("ROLE_mcp-external"));

        McpCallerContext caller = callerSeen.get();
        assertNotNull(caller, "tool callbacks enforce their allow-lists via McpCallerContext");
        assertEquals(grantId, caller.grantId());
        assertTrue(caller.isAgentExposed(agentId));
        assertTrue(caller.isWorkflowExposed(workflowId));
        assertEquals(Set.of(collectionId), caller.exposedKnowledgeCollectionIds());
        // And nothing beyond this grant's curated sets - the tenant-B
        // isolation story at this layer.
        assertTrue(caller.exposedAgentIds().equals(Set.of(agentId)));
    }

    @Test
    void bothContextsAreClearedAfterTheChainReturns() throws Exception {
        arrangeValidKey(UUID.randomUUID());

        filter.doFilter(request, response, (req, res) -> { });

        // Servlet threads are pooled: anything left here would leak this
        // grant's tenant + capabilities into an unrelated later request.
        assertTrue(TenantContext.current().isEmpty());
        assertTrue(McpCallerContext.current().isEmpty());
    }

    @Test
    void bothContextsAreClearedEvenWhenTheChainThrows() {
        arrangeValidKey(UUID.randomUUID());

        try {
            filter.doFilter(request, response, (req, res) -> {
                throw new jakarta.servlet.ServletException("downstream blew up");
            });
        } catch (Exception expected) {
            // the exception itself propagates - only the cleanup is under test
        }

        assertTrue(TenantContext.current().isEmpty());
        assertTrue(McpCallerContext.current().isEmpty());
    }

    @Test
    void bearerPrefixIsCaseInsensitive() throws Exception {
        // RFC 7235 auth-scheme comparison is case-insensitive and real MCP
        // clients do send "bearer" - the filter uses regionMatches(ignoreCase)
        // for exactly this reason.
        UUID grantId = UUID.randomUUID();
        McpServerAccessGrant grant = grantWithId(grantId);
        request.addHeader("Authorization", "bearer vmcp_raw");
        when(grantService.resolveTenantSchema("vmcp_raw")).thenReturn(Optional.of("tenant_alpha"));
        when(grantService.findByRawKeyWithinCurrentTenant("vmcp_raw")).thenReturn(Optional.of(grant));
        when(rateLimiter.allow(grantId)).thenReturn(true);
        stubEmptyExposedSets(grant);

        AtomicBoolean chainRan = new AtomicBoolean(false);
        filter.doFilter(request, response, (req, res) -> chainRan.set(true));

        assertTrue(chainRan.get());
    }

    // ---- helpers ------------------------------------------------------------

    private void arrangeValidKey(UUID grantId) {
        McpServerAccessGrant grant = grantWithId(grantId);
        request.addHeader("Authorization", "Bearer vmcp_raw");
        when(grantService.resolveTenantSchema("vmcp_raw")).thenReturn(Optional.of("tenant_alpha"));
        when(grantService.findByRawKeyWithinCurrentTenant("vmcp_raw")).thenReturn(Optional.of(grant));
        when(rateLimiter.allow(grantId)).thenReturn(true);
        stubEmptyExposedSets(grant);
    }

    private void stubEmptyExposedSets(McpServerAccessGrant grant) {
        when(grantService.parseExposedAgentIds(grant)).thenReturn(Set.of());
        when(grantService.parseExposedWorkflowIds(grant)).thenReturn(Set.of());
        when(grantService.parseExposedKnowledgeCollectionIds(grant)).thenReturn(Set.of());
    }

    /** Runs the filter, asserts the expected status, that the chain never ran (enforced by the chain itself), and returns the JSON body for message assertions. */
    private String assertRejected(FilterChain chain, int expectedStatus) {
        try {
            filter.doFilter(request, response, chain);
            assertEquals(expectedStatus, response.getStatus());
            assertEquals("application/json", response.getContentType());
            return response.getContentAsString();
        } catch (Exception e) {
            throw new AssertionError("filter threw instead of writing a " + expectedStatus + " response", e);
        }
    }

    // ---- get_task poll bucket -------------------------------------------------

    private void enablePollBucket() {
        filter = new McpApiKeyAuthFilter(grantService, rateLimiter, false, true);
    }

    private void setJsonBody(String json) {
        request.setContent(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        request.setContentType("application/json");
    }

    @Test
    void getTaskPollsDrawFromThePollBucketAndTheBodyStillReachesTheChain() throws Exception {
        enablePollBucket();
        UUID grantId = UUID.randomUUID();
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"get_task\",\"arguments\":{\"task_id\":\"x\"}}}";
        arrangeValidKey(grantId);
        when(rateLimiter.allowPoll(grantId)).thenReturn(true);
        setJsonBody(body);

        AtomicReference<String> seen = new AtomicReference<>();
        filter.doFilter(request, response, (req, res) ->
            seen.set(new String(req.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)));

        assertEquals(body, seen.get(), "the transport must still be able to read the body the filter inspected");
        verify(rateLimiter).allowPoll(grantId);
        verify(rateLimiter, never()).allow(grantId);
    }

    @Test
    void otherToolCallsStillUseTheGeneralBucket() throws Exception {
        enablePollBucket();
        UUID grantId = UUID.randomUUID();
        arrangeValidKey(grantId);
        setJsonBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"start_agent_task\"}}");

        filter.doFilter(request, response, (req, res) -> { });

        verify(rateLimiter).allow(grantId);
        verify(rateLimiter, never()).allowPoll(grantId);
    }

    @Test
    void exhaustedPollBucketIs429() {
        enablePollBucket();
        UUID grantId = UUID.randomUUID();
        arrangeValidKey(grantId);
        when(rateLimiter.allowPoll(grantId)).thenReturn(false);
        setJsonBody("{\"method\":\"tools/call\",\"params\":{\"name\":\"get_task\"}}");

        assertRejected(neverReachedChain(), 429);
    }

    @Test
    void flagOffNeverSniffsOrWrapsTheBody() throws Exception {
        UUID grantId = UUID.randomUUID();
        arrangeValidKey(grantId);
        setJsonBody("{\"method\":\"tools/call\",\"params\":{\"name\":\"get_task\"}}");
        AtomicReference<jakarta.servlet.ServletRequest> seen = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> seen.set(req));

        org.junit.jupiter.api.Assertions.assertSame(request, seen.get(), "request must reach the chain unwrapped");
        verify(rateLimiter).allow(grantId);
        verify(rateLimiter, never()).allowPoll(grantId);
    }

    @Test
    void getTaskDetectionIsStrict() {
        assertTrue(McpApiKeyAuthFilter.isGetTaskCall(
            "{\"method\":\"tools/call\",\"params\":{\"name\":\"get_task\"}}".getBytes()));
        org.junit.jupiter.api.Assertions.assertFalse(McpApiKeyAuthFilter.isGetTaskCall(
            "{\"method\":\"tools/list\",\"params\":{\"name\":\"get_task\"}}".getBytes()));
        org.junit.jupiter.api.Assertions.assertFalse(McpApiKeyAuthFilter.isGetTaskCall(
            "[{\"method\":\"tools/call\",\"params\":{\"name\":\"get_task\"}}]".getBytes()));
        org.junit.jupiter.api.Assertions.assertFalse(McpApiKeyAuthFilter.isGetTaskCall("not json".getBytes()));
    }
}
