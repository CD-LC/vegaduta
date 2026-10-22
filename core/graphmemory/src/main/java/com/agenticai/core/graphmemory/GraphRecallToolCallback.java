package com.agenticai.core.graphmemory;

import com.agenticai.core.json.JsonMappers;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.ObjectMapper;

/**
 * Exposes {@link GraphMemoryService#recallRelated} as a native Spring AI tool, the same "plain
 * in-process ToolCallback wrapped by AgentToolProvider" pattern {@code RememberFactToolCallback}
 * established. Deliberately a separate, explicitly-callable tool rather than folded into {@code
 * AgentChatService}'s automatic recall block: multi-hop graph traversal only pays off when the
 * model has a specific named entity in mind ("is Priya's manager available?"), unlike vector
 * recall's blanket "recall anything relevant to this whole message" - the model deciding to look up
 * a specific name is a better trigger than always running a traversal against every message.
 */
public class GraphRecallToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(GraphRecallToolCallback.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final GraphMemoryService graphMemoryService;
    private final UUID agentId;
    private final String subjectKey;

    public GraphRecallToolCallback(
            GraphMemoryService graphMemoryService, UUID agentId, String subjectKey) {
        this.graphMemoryService = graphMemoryService;
        this.agentId = agentId;
        this.subjectKey = subjectKey;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("recall_related_facts")
                .description(
                        "Looks up facts CONNECTED to a named entity (a person, place, company, or thing) from what "
                                + "you've remembered about this user - not just facts similar in wording, but facts "
                                + "relationally linked (e.g. asking about \"Priya\" can surface \"Priya's manager is Raj\" "
                                + "even if the current message never mentions Raj). Use this when the user asks about a "
                                + "specific named thing and a simple keyword match might miss an indirect connection.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "entity": {
                            "type": "string",
                            "description": "The specific named entity to look up connections for (e.g. \\"Priya\\")"
                        }
                    },
                    "required": ["entity"]
                }
                """)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public String call(String toolInput) {
        try {
            Map<String, Object> args = MAPPER.readValue(toolInput, Map.class);
            Object entityValue = args.get("entity");
            if (entityValue == null || entityValue.toString().isBlank()) {
                return "{\"error\": \"entity is required\"}";
            }
            List<GraphFact> facts =
                    graphMemoryService.recallRelated(
                            agentId, subjectKey, entityValue.toString(), 2);
            if (facts.isEmpty()) {
                return "{\"facts\": [], \"note\": \"No connected facts found for this entity.\"}";
            }
            // Was hand-joining fact sentences into a JSON array literal
            // ("[\"" + ... + "\"]") - any fact sentence containing a raw
            // '"' (an entity/attribute value quoted in the sentence itself,
            // e.g. a job title or product name) produced invalid JSON that
            // ToolCallOutputValidator would reject as "looks like JSON but
            // failed to parse" (same bug class fixed in
            // KnowledgeSearchToolCallback - see its errorJson() javadoc).
            // MAPPER.writeValueAsString escapes correctly regardless of
            // content.
            List<String> sentences = facts.stream().map(GraphFact::asSentence).toList();
            return errorSafeJson(Map.of("facts", sentences));
        } catch (Exception e) {
            log.warn("recall_related_facts tool call failed", e);
            return errorJson("Failed to recall related facts: " + e.getMessage());
        }
    }

    private static String errorSafeJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return errorJson("Failed to serialize result: " + e.getMessage());
        }
    }

    private static String errorJson(String message) {
        try {
            return MAPPER.writeValueAsString(Map.of("error", message));
        } catch (Exception e) {
            return "{\"error\": \"error\"}";
        }
    }
}
