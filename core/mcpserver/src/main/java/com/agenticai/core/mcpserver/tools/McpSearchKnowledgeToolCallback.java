package com.agenticai.core.mcpserver.tools;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.knowledge.KnowledgeSearchService;
import com.agenticai.core.knowledge.dto.SearchResultResponse;
import com.agenticai.core.mcpserver.McpCallerContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.util.StringUtils;

/**
 * Third MCP tool this server exposes - a read-only hybrid (vector + keyword,
 * RRF-fused, optionally reranked) search over the caller's own Knowledge
 * Collections, routed straight into {@link KnowledgeSearchService#search},
 * the exact same entry point {@code core/knowledge}'s own internal {@code
 * KnowledgeSearchToolCallback} (used by in-process agent tool calls) and
 * {@code KnowledgeIngestionJobRunner}-adjacent callers use - no parallel
 * search implementation.
 *
 * <p><b>Deny-by-default, INVERTED from {@code core/knowledge}'s own
 * convention - read this carefully before touching this class:</b> inside
 * {@code core/knowledge}, an empty {@code allowedCollectionIds} passed to
 * {@code KnowledgeSearchService#search} means "no restriction, search every
 * collection the tenant has" ({@code KnowledgeSearchToolCallback}'s own
 * javadoc documents this explicitly) - safe there because that allow-list
 * is an internal, per-agent scoping knob on top of an already-tenant-scoped
 * caller. This module's own {@link McpServerAccessGrant} is different: it
 * is an EXTERNAL-facing grant, and its {@code exposedKnowledgeCollectionIds}
 * is a tenant-admin's explicit opt-in list, not a scoping refinement. An
 * empty set on the grant means the admin has shared ZERO collections with
 * this API key - this class must never fall back to "search everything" in
 * that case, or every MCP grant would silently get tenant-wide knowledge
 * access the moment it existed, regardless of what was actually configured.
 * See {@link McpCallerContext}'s javadoc for the same note from the other
 * side.
 *
 * <p><b>Per-collection ACL (V710):</b> {@code KnowledgeSearchService#search} also narrows the exposed
 * collections to those the caller may read. An MCP key carries no user identity (its context holds a
 * synthetic subject that owns nothing and holds no grants), so it can only ever reach collections
 * with {@code TENANT} visibility: a PRIVATE or SHARED collection listed in the grant's exposed set
 * is silently skipped and the denial is audited by {@code KnowledgeAccessService}. Fails closed.
 *
 * <p>{@code top_k} is capped server-side at {@link #MAX_TOP_K} regardless of
 * what the caller requests - an external, rate-limited-but-otherwise-
 * unsupervised caller asking for an enormous top_k is an easy way to run up
 * embedding/rerank cost or scrape a disproportionate slice of a collection
 * in one call.
 *
 * <p>Same coarse, one-row-per-invocation {@code audit_log_entry} posture as
 * {@code McpCallAgentToolCallback}/{@code McpCallWorkflowToolCallback} (see
 * {@link #recordAudit}) - a read-only search is still an external caller
 * reading tenant knowledge, so it goes through the same audit trail every
 * other MCP tool call does, including the deny-by-default rejection above.
 */
public class McpSearchKnowledgeToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(McpSearchKnowledgeToolCallback.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();
    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_TOP_K = 20;

    private final KnowledgeSearchService knowledgeSearchService;
    private final AuditLogService auditLogService;

    public McpSearchKnowledgeToolCallback(KnowledgeSearchService knowledgeSearchService, AuditLogService auditLogService) {
        this.knowledgeSearchService = knowledgeSearchService;
        this.auditLogService = auditLogService;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
            .name("search_knowledge")
            .description(
                "Runs a read-only hybrid (semantic + keyword) search over the Knowledge Collections your "
                    + "tenant admin has shared with this API key, and returns the most relevant chunks. If no "
                    + "collections have been shared with this key, this tool returns an explanatory error "
                    + "instead of results - ask your tenant admin to update this grant's exposed knowledge "
                    + "collections."
            )
            .inputSchema("""
                {
                    "type": "object",
                    "properties": {
                        "query": {"type": "string", "description": "What to search for."},
                        "top_k": {"type": "integer", "description": "Optional - maximum number of results to return (default 5, hard-capped at 20)."}
                    },
                    "required": ["query"]
                }
                """)
            .build();
    }

    @Override
    public String call(String toolInput) {
        McpCallerContext caller = McpCallerContext.current()
            .orElseThrow(() -> new IllegalStateException(
                "search_knowledge invoked with no McpCallerContext set - McpApiKeyAuthFilter should have "
                    + "rejected this request before it ever reached tool dispatch."
            ));
        try {
            JsonNode input = MAPPER.readTree(toolInput);
            JsonNode queryNode = input.get("query");
            String query = queryNode == null || queryNode.isNull() ? null : queryNode.asText();
            if (!StringUtils.hasText(query)) {
                return errorJson("query is required.");
            }

            int topK = DEFAULT_TOP_K;
            JsonNode topKNode = input.get("top_k");
            if (topKNode != null && !topKNode.isNull()) {
                topK = topKNode.asInt(DEFAULT_TOP_K);
            }
            if (topK < 1) {
                topK = 1;
            } else if (topK > MAX_TOP_K) {
                topK = MAX_TOP_K;
            }

            // Deny-by-default per this module's own established posture - see this class's own
            // javadoc for why an empty set here must NOT fall back to "search everything", unlike
            // core/knowledge's internal convention.
            Set<UUID> exposedCollectionIds = caller.exposedKnowledgeCollectionIds();
            if (exposedCollectionIds.isEmpty()) {
                log.warn("MCP grant {} called search_knowledge with zero exposed knowledge collections - rejected", caller.grantId());
                recordAudit(caller.grantId(), AuditOutcome.FAILURE,
                    "MCP grant " + caller.grantId() + " called search_knowledge with zero exposed knowledge collections.");
                return errorJson(
                    "No knowledge collections have been shared with this API key. Ask your tenant admin to "
                        + "update this grant's exposed knowledge collections."
                );
            }

            List<SearchResultResponse> results = knowledgeSearchService.search(query, topK, exposedCollectionIds);
            recordAudit(caller.grantId(), AuditOutcome.SUCCESS,
                "MCP grant " + caller.grantId() + " ran search_knowledge (" + results.size() + " result(s)).");
            return MAPPER.writeValueAsString(results);
        } catch (Exception e) {
            log.warn("search_knowledge tool call failed for grant {}", caller.grantId(), e);
            recordAudit(caller.grantId(), AuditOutcome.FAILURE,
                "MCP grant " + caller.grantId() + " search_knowledge invocation failed: " + e.getMessage());
            return errorJson("Knowledge search failed: " + e.getMessage());
        }
    }

    /**
     * Best-effort {@link AuditLogService#record} wrapper - same "never let logging itself break
     * the real call" posture {@code McpCallAgentToolCallback}'s own {@code recordAudit} already
     * takes. A failure here must never propagate and change what {@link #call(String)} was
     * already about to return. No single target id here (a search spans this grant's whole
     * exposed-collection set, not one entity), so {@code targetType}/{@code targetId} name the
     * grant itself, same as {@code McpCallAgentToolCallback}'s own null-target failure branches.
     */
    private void recordAudit(UUID grantId, AuditOutcome outcome, String detail) {
        try {
            auditLogService.record("mcp-external:" + grantId, "mcp.grant.search_knowledge", "McpServerAccessGrant", grantId.toString(), outcome, detail);
        } catch (RuntimeException auditFailure) {
            log.warn("Failed to record audit log entry for MCP grant {} search_knowledge invocation", grantId, auditFailure);
        }
    }

    private String errorJson(String message) {
        try {
            return MAPPER.writeValueAsString(Map.of("error", message));
        } catch (Exception e) {
            return "{\"error\": \"" + message.replace("\"", "'") + "\"}";
        }
    }
}
