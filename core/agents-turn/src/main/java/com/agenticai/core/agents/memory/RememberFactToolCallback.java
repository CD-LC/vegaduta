package com.agenticai.core.agents.memory;

import com.agenticai.core.graphmemory.EntityExtractionService;
import com.agenticai.core.graphmemory.GraphMemoryService;
import com.agenticai.core.graphmemory.GraphTriple;
import com.agenticai.core.graphmemory.MemoryWriteArbitrationService;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.knowledge.KnowledgeWriteScreen;
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
 * Exposes {@link AgentMemoryService#remember} as a native Spring AI tool, the same "plain
 * in-process ToolCallback, wrapped by AgentToolProvider exactly like every other tool" pattern
 * {@code KnowledgeSearchToolCallback} established for Stage 6. Deliberately agent-initiated rather
 * than automatic: the model decides, in the moment, whether something the user said is worth
 * remembering past this conversation (a stated preference, a correction, a fact about their
 * business) - see {@link AgentMemoryService}'s javadoc for why an automatic background extraction
 * pass is out of scope here, not silently assumed away.
 *
 * <p>After a successful {@code remember}, also (best-effort, optional) extracts (subject, relation,
 * object) triples from the fact via {@link EntityExtractionService} and writes them to {@link
 * GraphMemoryService} - the same fact ends up in both the vector store (similarity recall) and the
 * graph (connection recall). {@code graphMemoryService}/{@code entityExtractionService}/{@code
 * chatClient} are all nullable - when any is absent (the "graph-memory" profile is off, or no chat
 * context), this degrades to exactly {@link AgentMemoryService}'s original, unchanged behavior.
 */
public class RememberFactToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(RememberFactToolCallback.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final AgentMemoryService memoryService;
    private final UUID agentId;
    private final String subjectKey;
    private final GraphMemoryService graphMemoryService;
    private final EntityExtractionService entityExtractionService;
    private final MemoryWriteArbitrationService memoryWriteArbitrationService;
    private final ChatClient chatClient;

    public RememberFactToolCallback(
            AgentMemoryService memoryService, UUID agentId, String subjectKey) {
        this(memoryService, agentId, subjectKey, null, null, null, null);
    }

    public RememberFactToolCallback(
            AgentMemoryService memoryService,
            UUID agentId,
            String subjectKey,
            GraphMemoryService graphMemoryService,
            EntityExtractionService entityExtractionService,
            MemoryWriteArbitrationService memoryWriteArbitrationService,
            ChatClient chatClient) {
        this.memoryService = memoryService;
        this.agentId = agentId;
        this.subjectKey = subjectKey;
        this.graphMemoryService = graphMemoryService;
        this.entityExtractionService = entityExtractionService;
        this.memoryWriteArbitrationService = memoryWriteArbitrationService;
        this.chatClient = chatClient;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("remember_fact")
                .description(
                        "Saves a fact, preference, or correction about the current user so you'll still know it in "
                                + "future conversations, not just this one. Use this when the user states a durable "
                                + "preference (\"I prefer email over WhatsApp\"), a fact about themselves or their business "
                                + "(\"my shop is only open on weekends\"), or corrects something you got wrong. Do not use "
                                + "this for one-off details that only matter to the current message.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "fact": {
                            "type": "string",
                            "description": "The fact to remember, written as a short, self-contained statement (e.g. \\"Prefers to be contacted in the evening after 6pm\\")"
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
            // Recalled facts are injected into every later turn's context
            // unsanitized - screen the write the same way knowledge-base
            // writes already are (stored prompt injection / pasted
            // credentials; OWASP ASI06, LLM02).
            java.util.Optional<String> refusal = KnowledgeWriteScreen.refusal(factValue.toString());
            if (refusal.isPresent()) {
                return errorJson(refusal.get());
            }
            UUID memoryId = memoryService.remember(agentId, subjectKey, factValue.toString());
            writeToGraphIfAvailable(factValue.toString());
            return "{\"remembered\": true, \"memoryId\": \"" + memoryId + "\"}";
        } catch (Exception e) {
            log.warn("remember_fact tool call failed", e);
            return errorJson("Failed to remember fact: " + e.getMessage());
        }
    }

    /**
     * Best-effort, entirely optional: extracts and writes graph triples for this fact if the
     * "graph-memory" profile is active and a chat context is available. Never throws - a failure or
     * absence here must never affect {@code remember_fact}'s already-successful primary result, see
     * this class's javadoc.
     *
     * <p>Master Roadmap 2026 item #11 (Memory write arbitration): the actual write goes through
     * {@link MemoryWriteArbitrationService#arbitrateAndWrite} rather than calling {@code
     * graphMemoryService.remember} directly - that service internally falls straight through to the
     * exact same unconditional write whenever arbitration is disabled ({@code
     * app.graph-memory.arbitration.enabled=false}, the default) or {@code
     * memoryWriteArbitrationService} itself is null (matches this class's own older constructor),
     * so this call is safe regardless of which optional pieces are wired up.
     */
    private void writeToGraphIfAvailable(String fact) {
        if (graphMemoryService == null || entityExtractionService == null || chatClient == null) {
            return;
        }
        try {
            List<GraphTriple> triples =
                    entityExtractionService.extractTriples(chatClient, fact, agentId);
            if (memoryWriteArbitrationService != null) {
                memoryWriteArbitrationService.arbitrateAndWrite(
                        chatClient, graphMemoryService, agentId, subjectKey, triples);
            } else {
                graphMemoryService.remember(agentId, subjectKey, triples);
            }
        } catch (Exception e) {
            log.warn(
                    "Graph memory enrichment failed for remember_fact - vector memory is unaffected",
                    e);
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
