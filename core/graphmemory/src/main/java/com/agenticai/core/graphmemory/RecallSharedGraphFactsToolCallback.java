package com.agenticai.core.graphmemory;

import com.agenticai.core.json.JsonMappers;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.ObjectMapper;

/**
 * Phase 2 of the Multi-Agent Graph Memory (MAG) plan — exposes {@link
 * GraphMemoryService#recallSharedRelated} as a Spring AI tool. The exact same pattern as {@link
 * GraphRecallToolCallback}, but reads from the tenant-shared graph tier rather than this agent's
 * private tier. Only offered when the agent has {@code shareMemoryWithTenant} enabled — see {@code
 * AgentToolProvider#resolveMemoryTools} — so an agent that never opted in to shared memory is never
 * told the shared tier exists and never queries it, maintaining the "no surprise cross-agent
 * visibility" principle from that flag's javadoc.
 */
public class RecallSharedGraphFactsToolCallback implements ToolCallback {

    private static final Logger log =
            LoggerFactory.getLogger(RecallSharedGraphFactsToolCallback.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final GraphMemoryService graphMemoryService;
    private final String subjectKey;

    public RecallSharedGraphFactsToolCallback(
            GraphMemoryService graphMemoryService, String subjectKey) {
        this.graphMemoryService = graphMemoryService;
        this.subjectKey = subjectKey;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("recall_shared_graph_facts")
                .description(
                        "Looks up relational facts about a named entity from the SHARED team memory — facts that "
                                + "other agents on this team have remembered about this user's connections and relationships. "
                                + "Use this to access knowledge that a colleague agent already discovered (e.g. \"Priya WORKS_AT "
                                + "Acme Corp\" written by the onboarding agent is now visible to the support agent). "
                                + "Only available when team memory sharing is enabled for this agent.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "entity": {
                            "type": "string",
                            "description": "The specific named entity to look up shared connections for (e.g. \\"Priya\\")"
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
                    graphMemoryService.recallSharedRelated(subjectKey, entityValue.toString(), 2);
            if (facts.isEmpty()) {
                return "{\"facts\": [], \"note\": \"No shared connected facts found for this entity.\"}";
            }
            List<String> sentences = facts.stream().map(GraphFact::asSentence).toList();
            return errorSafeJson(Map.of("facts", sentences, "source", "shared_team_graph"));
        } catch (Exception e) {
            log.warn("recall_shared_graph_facts tool call failed", e);
            return errorJson("Failed to recall shared graph facts: " + e.getMessage());
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
