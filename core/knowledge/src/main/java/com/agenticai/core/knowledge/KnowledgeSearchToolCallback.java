package com.agenticai.core.knowledge;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.knowledge.dto.SearchResultResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Stage 6: exposes KnowledgeSearchService as a native Spring AI tool, so an
 * agent can call it mid-conversation exactly like an MCP-provided tool
 * (AgentToolProvider wraps this in LoggingToolCallback the same way it
 * wraps MCP tools, so tool-call logging is identical either way). This is
 * intentionally NOT an MCP server itself - it's a plain in-process
 * ToolCallback - "reuses the registry, not a hardcoded RAG path" per the
 * Build Plan is satisfied by gating this on the per-agent
 * knowledgeBaseEnabled flag (Stage 6's equivalent of Stage 4's registry
 * enabled flag) rather than by routing through the MCP client machinery,
 * which is for external subprocess/HTTP tool servers and doesn't fit an
 * in-process Java service.
 *
 * <p>Knowledge Stage F2 - gained an optional {@code collection_id}
 * parameter, enforced against {@code allowedCollectionIds} (captured at
 * construction time from {@code Agent.allowedKnowledgeCollectionIds}, same
 * pattern as {@link ListKnowledgeCollectionsToolCallback}). A model that
 * hasn't called {@code list_knowledge_collections} yet can still omit
 * {@code collection_id} and get the pre-F2 "search everything this agent
 * may access" behavior - the parameter is a scoping option, not a
 * requirement.
 */
public class KnowledgeSearchToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSearchToolCallback.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    /**
     * Chunks one search returns when the caller sets no top-k: the value the tool always used. The agent's
     * own setting ({@code retrievalTopK} of the runtime settings) replaces it through the four-argument
     * constructor.
     */
    public static final int DEFAULT_TOP_K = 3;

    private final KnowledgeSearchService searchService;
    private final Set<UUID> allowedCollectionIds;
    private final int topK;
    private final double minScore;

    /**
     * @param allowedCollectionIds empty means "no restriction, this agent
     *     may search every collection its tenant has" - same semantics as
     *     {@code Agent.allowedKnowledgeCollectionIds}.
     */
    public KnowledgeSearchToolCallback(KnowledgeSearchService searchService, Set<UUID> allowedCollectionIds) {
        this(searchService, allowedCollectionIds, DEFAULT_TOP_K, 0.0);
    }

    /**
     * @param topK chunks one search asks for; below 1 falls back to {@link #DEFAULT_TOP_K}
     * @param minScore least relevance a chunk needs to be returned, where relevance is {@code -distance}
     *     (the hybrid search reports the negated fused / reranked score as the distance, so a better chunk has
     *     a larger relevance; fused scores are small, a few hundredths, and a reranker's follow its own
     *     scale). {@code 0} (or less) returns every hit.
     */
    public KnowledgeSearchToolCallback(
        KnowledgeSearchService searchService, Set<UUID> allowedCollectionIds, int topK, double minScore
    ) {
        this.searchService = searchService;
        this.allowedCollectionIds = allowedCollectionIds;
        this.topK = topK > 0 ? topK : DEFAULT_TOP_K;
        this.minScore = minScore;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
            .name("search_knowledge_base")
            .description(
                "Searches this agent's uploaded knowledge base for content relevant to a query and returns "
                    + "results with citations (source document name and document id). Use this whenever the "
                    + "user's question might be answered by something they've uploaded, rather than relying on "
                    + "general knowledge alone. If you're unsure which knowledge collection is relevant, call "
                    + "list_knowledge_collections first and pass its id as collection_id to scope the search; "
                    + "omit collection_id to search every collection you have access to. When you answer using "
                    + "these results, cite the source document by name so the user knows which upload the "
                    + "answer came from - do not present retrieved content as general knowledge. If results "
                    + "look garbled or you suspect they came from OCR'd images (misread SKUs, prices, or numbers) "
                    + "rather than clean source text, retry with exclude_image_sourced=true."
            )
            .inputSchema("""
                {
                    "type": "object",
                    "properties": {
                        "query": {
                            "type": "string",
                            "description": "What to search for in the knowledge base"
                        },
                        "collection_id": {
                            "type": "string",
                            "description": "Optional - the id of a specific knowledge collection to search (see list_knowledge_collections). Omit to search every collection you have access to."
                        },
                        "exclude_image_sourced": {
                            "type": "boolean",
                            "description": "Optional, default false. Set true to exclude chunks extracted via OCR from uploaded images, which can be noisy or garbled (misread SKUs, prices, ratings)."
                        }
                    },
                    "required": ["query"]
                }
                """)
            .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public String call(String toolInput) {
        try {
            Map<String, Object> args = MAPPER.readValue(toolInput, Map.class);
            Object queryValue = args.get("query");
            if (queryValue == null || queryValue.toString().isBlank()) {
                return "{\"error\": \"query is required\"}";
            }

            Object collectionIdValue = args.get("collection_id");
            Set<UUID> scopedCollectionIds;
            if (collectionIdValue == null || collectionIdValue.toString().isBlank()) {
                // No specific collection requested - default scope is
                // "everything this agent may access" (empty allow-list =
                // every collection the tenant has, the pre-F2 default,
                // unchanged).
                scopedCollectionIds = allowedCollectionIds;
            } else {
                UUID requested;
                try {
                    requested = UUID.fromString(collectionIdValue.toString());
                } catch (IllegalArgumentException e) {
                    return "{\"error\": \"collection_id is not a valid id\"}";
                }
                if (!allowedCollectionIds.isEmpty() && !allowedCollectionIds.contains(requested)) {
                    return "{\"error\": \"This agent does not have access to that knowledge collection\"}";
                }
                scopedCollectionIds = Set.of(requested);
            }

            boolean excludeImageSourced = Boolean.parseBoolean(String.valueOf(args.getOrDefault("exclude_image_sourced", false)));

            List<SearchResultResponse> results =
                searchService.search(queryValue.toString(), topK, scopedCollectionIds, excludeImageSourced);
            if (minScore > 0.0) {
                results = results.stream().filter(r -> -r.distance() >= minScore).toList();
            }
            return MAPPER.writeValueAsString(results);
        } catch (Exception e) {
            log.warn("search_knowledge_base tool call failed", e);
            return errorJson("Knowledge base search failed: " + e.getMessage());
        }
    }

    /**
     * Direct feedback ("Tool output from 'search_knowledge_base' failed
     * validation... output looked like JSON but failed to parse") - root
     * cause: the old code hand-built the error JSON via string
     * concatenation ("{\"error\": \"" + e.getMessage() + "\"}"), so any
     * exception message containing a raw '"' broke the JSON. That's not
     * rare here - SidecarClient.describe()'s own connection-refused message
     * quotes the target URL (Cause: ...I/O error on POST request for
     * "http://localhost:8001/extract": Connection refused), so a sidecar
     * outage reliably produced malformed output that {@link
     * com.agenticai.core.guardrails.ToolCallOutputValidator} correctly
     * rejected. Routing through Jackson guarantees valid JSON regardless of
     * what the message contains.
     */
    private static String errorJson(String message) {
        try {
            return MAPPER.writeValueAsString(Map.of("error", message));
        } catch (Exception e) {
            return "{\"error\": \"error\"}";
        }
    }
}
