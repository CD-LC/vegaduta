package com.agenticai.core.mcpserver.tools;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.knowledge.KnowledgeSearchService;
import com.agenticai.core.mcpserver.McpCallerContextTestAccess;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code search_knowledge}'s one load-bearing subtlety is the INVERTED empty-set convention (its
 * class javadoc walks through it): inside core/knowledge an empty allow-list means "search
 * everything", but on this external grant surface an empty set means the admin shared NOTHING - so
 * a regression that forwards an empty set into {@link KnowledgeSearchService} would silently hand
 * every MCP key tenant-wide knowledge access. That deny-by-default branch is the headline test
 * here, alongside the top_k clamping that stops an external caller running up embedding/rerank
 * cost.
 */
class McpSearchKnowledgeToolCallbackTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private KnowledgeSearchService knowledgeSearchService;
    private AuditLogService auditLogService;
    private McpSearchKnowledgeToolCallback callback;

    private final UUID grantId = UUID.randomUUID();
    private final UUID exposedCollectionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        knowledgeSearchService = mock(KnowledgeSearchService.class);
        auditLogService = mock(AuditLogService.class);
        callback = new McpSearchKnowledgeToolCallback(knowledgeSearchService, auditLogService);
        McpCallerContextTestAccess.set(grantId, Set.of(), Set.of(), Set.of(exposedCollectionId));
    }

    @AfterEach
    void tearDown() {
        McpCallerContextTestAccess.clear();
    }

    private JsonNode call(String toolInput) throws Exception {
        return MAPPER.readTree(callback.call(toolInput));
    }

    @Test
    void missingCallerContextIsAnIllegalState() {
        McpCallerContextTestAccess.clear();
        assertThrows(IllegalStateException.class, () -> callback.call("{\"query\": \"q\"}"));
    }

    @Test
    void missingQueryReturnsAnErrorWithoutSearching() throws Exception {
        assertTrue(call("{}").has("error"));
        assertTrue(call("{\"query\": \"   \"}").has("error"));
        verify(knowledgeSearchService, never()).search(anyString(), anyInt(), anySet());
    }

    @Test
    void zeroSharedCollectionsMeansAnExplanatoryErrorNeverSearchEverything() throws Exception {
        // THE deny-by-default test - see class javadoc. The dangerous
        // regression is not an exception, it's a quiet
        // search(query, topK, emptySet) call that core/knowledge would
        // happily interpret as "no restriction".
        McpCallerContextTestAccess.set(grantId, Set.of(), Set.of(), Set.of());

        JsonNode result = call("{\"query\": \"quarterly report\"}");

        assertTrue(result.get("error").asText().contains("tenant admin"));
        verify(knowledgeSearchService, never()).search(anyString(), anyInt(), anySet());
        // A deny-by-default rejection is itself compliance-relevant, same posture as the
        // other MCP tool callbacks' rejection branches.
        verify(auditLogService)
                .record(
                        eq("mcp-external:" + grantId),
                        eq("mcp.grant.search_knowledge"),
                        eq("McpServerAccessGrant"),
                        eq(grantId.toString()),
                        eq(AuditOutcome.FAILURE),
                        anyString());
    }

    @Test
    void searchIsScopedToExactlyTheGrantsExposedCollections() throws Exception {
        when(knowledgeSearchService.search(
                        eq("quarterly report"), anyInt(), eq(Set.of(exposedCollectionId))))
                .thenReturn(List.of());

        call("{\"query\": \"quarterly report\"}");

        // Never a superset, never a tenant-wide wildcard.
        verify(knowledgeSearchService).search("quarterly report", 5, Set.of(exposedCollectionId));
        verify(auditLogService)
                .record(
                        eq("mcp-external:" + grantId),
                        eq("mcp.grant.search_knowledge"),
                        eq("McpServerAccessGrant"),
                        eq(grantId.toString()),
                        eq(AuditOutcome.SUCCESS),
                        anyString());
    }

    @Test
    void topKDefaultsToFive() throws Exception {
        when(knowledgeSearchService.search(anyString(), anyInt(), anySet())).thenReturn(List.of());

        call("{\"query\": \"q\"}");

        verify(knowledgeSearchService).search("q", 5, Set.of(exposedCollectionId));
    }

    @Test
    void topKIsHardCappedAtTwentyRegardlessOfWhatTheCallerAsksFor() throws Exception {
        // The cost/scrape ceiling: an external caller must not be able to
        // pull an arbitrarily large slice of a collection in one call.
        when(knowledgeSearchService.search(anyString(), anyInt(), anySet())).thenReturn(List.of());

        call("{\"query\": \"q\", \"top_k\": 5000}");

        verify(knowledgeSearchService).search("q", 20, Set.of(exposedCollectionId));
    }

    @Test
    void nonPositiveTopKIsRaisedToOneNotPassedThrough() throws Exception {
        when(knowledgeSearchService.search(anyString(), anyInt(), anySet())).thenReturn(List.of());

        call("{\"query\": \"q\", \"top_k\": -3}");

        verify(knowledgeSearchService).search("q", 1, Set.of(exposedCollectionId));
    }

    @Test
    void emptyResultsSerializeAsAnEmptyArrayNotAnError() throws Exception {
        when(knowledgeSearchService.search(anyString(), anyInt(), anySet())).thenReturn(List.of());

        JsonNode result = call("{\"query\": \"q\"}");

        assertTrue(result.isArray());
        // "Nothing matched" and "you have no access" are deliberately
        // different responses - conflating them hides misconfiguration.
    }

    @Test
    void aSearchFailureBecomesAnErrorPayloadNotAThrownException() throws Exception {
        when(knowledgeSearchService.search(anyString(), anyInt(), anySet()))
                .thenThrow(new RuntimeException("embedding sidecar down"));

        JsonNode result = call("{\"query\": \"q\"}");

        assertTrue(result.get("error").asText().contains("Knowledge search failed"));
        verify(auditLogService)
                .record(
                        eq("mcp-external:" + grantId),
                        eq("mcp.grant.search_knowledge"),
                        eq("McpServerAccessGrant"),
                        eq(grantId.toString()),
                        eq(AuditOutcome.FAILURE),
                        anyString());
    }

    @Test
    void anAuditLoggingFailureNeverChangesTheSuccessfulResult() throws Exception {
        // "Never let logging break the real call" - same posture McpCallAgentToolCallback pins.
        when(knowledgeSearchService.search(anyString(), anyInt(), anySet())).thenReturn(List.of());
        doThrow(new RuntimeException("audit table unavailable"))
                .when(auditLogService)
                .record(anyString(), anyString(), anyString(), any(), any(), anyString());

        JsonNode result = call("{\"query\": \"q\"}");

        assertTrue(result.isArray());
    }
}
