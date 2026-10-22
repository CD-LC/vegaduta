package com.agenticai.core.knowledge;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.knowledge.dto.KnowledgeCollectionResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Knowledge Stage F2 - the "progressive disclosure" half of scenario-based knowledge selection (the
 * Stage F Build Plan's F2 decision): lets a model see what collections exist - name, description,
 * parent - before deciding which one, if any, to pass as {@code search_knowledge_base}'s optional
 * {@code collection_id}. Cheap: no embedding call, just a listing.
 *
 * <p>{@code allowedCollectionIds} is captured once, at construction time in {@code
 * AgentToolProvider.resolveKnowledgeTools} (from {@code Agent.allowedKnowledgeCollectionIds}),
 * rather than looked up fresh inside {@link #call} - same "resolve on the known-correct thread
 * once, not per-call" shape {@code GuardedToolCallback} already uses for its own constructor
 * params. This callback still runs under the correctly- propagated {@code TenantContext} regardless
 * of which thread Spring AI's tool-calling loop invokes it on, via the outer {@code
 * TenantContextPropagatingToolCallback} wrapper every tool gets.
 */
public class ListKnowledgeCollectionsToolCallback implements ToolCallback {

    private static final Logger log =
            LoggerFactory.getLogger(ListKnowledgeCollectionsToolCallback.class);
    // KnowledgeCollectionResponse carries Instant fields (createdAt/updatedAt) -
    // a bare ObjectMapper has no serializer for java.time types and throws.
    private static final ObjectMapper MAPPER = JsonMappers.withJavaTime();

    private final KnowledgeCollectionService collectionService;
    private final Set<UUID> allowedCollectionIds;

    /**
     * @param allowedCollectionIds empty means "no restriction, list every collection this tenant
     *     has" - same semantics as {@code Agent.allowedKnowledgeCollectionIds}.
     */
    public ListKnowledgeCollectionsToolCallback(
            KnowledgeCollectionService collectionService, Set<UUID> allowedCollectionIds) {
        this.collectionService = collectionService;
        this.allowedCollectionIds = allowedCollectionIds;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("list_knowledge_collections")
                .description(
                        "Lists the knowledge collections available to search, each with a name and description "
                                + "explaining what it contains. Call this FIRST when you're not sure which knowledge "
                                + "collection is relevant to the user's question, then pass the collection's id as the "
                                + "optional collection_id parameter to search_knowledge_base to scope your search to just "
                                + "that collection.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {}
                }
                """)
                .build();
    }

    @Override
    public String call(String toolInput) {
        try {
            List<KnowledgeCollectionResponse> all = collectionService.listAll();
            List<KnowledgeCollectionResponse> visible =
                    allowedCollectionIds.isEmpty()
                            ? all
                            : all.stream()
                                    .filter(c -> allowedCollectionIds.contains(c.id()))
                                    .toList();
            return MAPPER.writeValueAsString(visible);
        } catch (Exception e) {
            log.warn("list_knowledge_collections tool call failed", e);
            return "{\"error\": \"Could not list knowledge collections: " + e.getMessage() + "\"}";
        }
    }
}
