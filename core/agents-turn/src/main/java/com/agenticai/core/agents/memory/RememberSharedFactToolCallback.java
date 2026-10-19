package com.agenticai.core.agents.memory;

import com.agenticai.core.graphmemory.EntityExtractionService;
import com.agenticai.core.graphmemory.GraphMemoryService;
import com.agenticai.core.graphmemory.GraphTriple;
import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Shared cross-agent memory tier (#847) - exposes {@link
 * TenantMemoryService#remember} as a native Spring AI tool, only ever
 * offered to an agent whose own config has {@code shareMemoryWithTenant}
 * turned on (see {@code AgentToolProvider#resolveMemoryTools}). Deliberately
 * a separate tool from {@link RememberFactToolCallback}, not a flag on it -
 * the model needs to make an explicit, visible choice between "remember
 * this just for me" (private, {@code remember_fact}) and "remember this for
 * every agent on this team" (shared, {@code remember_shared_fact}), and a
 * tenant-admin who never enabled sharing for an agent should never see that
 * agent's private-memory tool quietly start writing tenant-wide instead.
 */
public class RememberSharedFactToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(RememberSharedFactToolCallback.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final TenantMemoryService tenantMemoryService;
    private final UUID agentId;
    private final String subjectKey;
    // Phase 2 MAG: optional — when present, graph triples are extracted and written to the
    // shared graph tier alongside the vector write. Null-safe: absence degrades to vector-only,
    // identical to the original constructor's behavior.
    private final GraphMemoryService graphMemoryService;
    private final EntityExtractionService entityExtractionService;
    private final ChatClient chatClient;

    public RememberSharedFactToolCallback(TenantMemoryService tenantMemoryService, UUID agentId, String subjectKey) {
        this(tenantMemoryService, agentId, subjectKey, null, null, null);
    }

    public RememberSharedFactToolCallback(
        TenantMemoryService tenantMemoryService, UUID agentId, String subjectKey,
        GraphMemoryService graphMemoryService, EntityExtractionService entityExtractionService, ChatClient chatClient
    ) {
        this.tenantMemoryService = tenantMemoryService;
        this.agentId = agentId;
        this.subjectKey = subjectKey;
        this.graphMemoryService = graphMemoryService;
        this.entityExtractionService = entityExtractionService;
        this.chatClient = chatClient;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
            .name("remember_shared_fact")
            .description(
                "Saves a fact, preference, or correction about the current user so EVERY agent on this team can "
                    + "recall it, not just you - use this for facts genuinely useful across agents (e.g. \"this "
                    + "customer's account is on the Enterprise plan\", \"prefers to be called by their first "
                    + "name\"). For something only relevant to your own conversations with this user, use "
                    + "remember_fact instead."
            )
            .inputSchema("""
                {
                    "type": "object",
                    "properties": {
                        "fact": {
                            "type": "string",
                            "description": "The fact to remember, written as a short, self-contained statement (e.g. \\"On the Enterprise plan since March\\")"
                        }
                    },
                    "required": ["fact"]
                }
                """)
            .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public String call(String toolInput) {
        try {
            Map<String, Object> args = MAPPER.readValue(toolInput, Map.class);
            Object factValue = args.get("fact");
            if (factValue == null || factValue.toString().isBlank()) {
                return "{\"error\": \"fact is required\"}";
            }
            UUID memoryId = tenantMemoryService.remember(subjectKey, factValue.toString(), agentId);
            writeToSharedGraphIfAvailable(factValue.toString());
            return "{\"remembered\": true, \"shared\": true, \"memoryId\": \"" + memoryId + "\"}";
        } catch (Exception e) {
            log.warn("remember_shared_fact tool call failed", e);
            return errorJson("Failed to remember shared fact: " + e.getMessage());
        }
    }

    /**
     * Phase 2 MAG: best-effort, entirely optional — writes graph triples to the shared graph tier
     * when the "graph-memory" profile is active and a chat context is available. Never throws —
     * a failure here must never affect {@code remember_shared_fact}'s already-successful primary
     * result. Mirrors {@link RememberFactToolCallback#writeToGraphIfAvailable} but calls
     * {@link GraphMemoryService#rememberShared} so the triples land in the shared tier, not the
     * calling agent's private tier.
     */
    private void writeToSharedGraphIfAvailable(String fact) {
        if (graphMemoryService == null || entityExtractionService == null || chatClient == null) {
            return;
        }
        try {
            List<GraphTriple> triples = entityExtractionService.extractTriples(chatClient, fact);
            graphMemoryService.rememberShared(subjectKey, triples);
        } catch (Exception e) {
            log.warn("Shared graph memory enrichment failed for remember_shared_fact - vector memory is unaffected", e);
        }
    }

    // Same bug class fixed in KnowledgeSearchToolCallback - hand-built
    // "{\"error\": \"...\"}" strings break if the exception message
    // contains a raw '"'.
    private static String errorJson(String message) {
        try {
            return MAPPER.writeValueAsString(Map.of("error", message));
        } catch (Exception e) {
            return "{\"error\": \"error\"}";
        }
    }
}
