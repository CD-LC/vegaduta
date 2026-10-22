package com.agenticai.core.agents.core;

import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Generates the JSON Schema (2020-12) pair describing what a caller may pass into, and should
 * expect back from, an {@link Agent} when it's invoked with structured parameters (Workflow ->
 * Agent structured parameter passing). The generated shapes are deliberately fixed/small rather
 * than a fully dynamic introspection of every tool's own parameter schema - {@code query}/ {@code
 * context_data}/{@code knowledge_collection_ids}/{@code approval_required} in, {@code
 * result_text}/{@code confidence}/{@code sources}/{@code tool_calls}/{@code metadata} out - since
 * {@code AgentChatService#chatWithParams} itself only ever consumes/produces these fields; per-tool
 * argument schemas stay internal to the agent's own tool resolution (see {@link
 * com.agenticai.core.agents.tools.AgentToolProvider}), not something a workflow author sets
 * directly.
 *
 * <p>Called by {@link AgentService} right before every {@code repository.save} that could change
 * what this agent accepts (creation, and any update that touches tools/knowledge/model scope) - see
 * {@link #refresh} - so {@link Agent#getInputSchemaJson()}/{@link Agent#getOutputSchemaJson()}
 * always reflect the agent's current configuration without a caller ever having to remember to
 * regenerate them separately.
 */
@Service
public class AgentSchemaService {

    private final ObjectMapper objectMapper;

    public AgentSchemaService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Regenerates and sets both schemas on {@code agent}. Does not save - the caller's existing
     * save covers it.
     */
    public void refresh(Agent agent) {
        agent.setGeneratedSchema(generateInputSchema(agent), generateOutputSchema(agent));
    }

    /**
     * @return a JSON Schema 2020-12 object string describing the {@code StructuredAgentInput} shape
     *     this agent accepts. {@code knowledge_collection_ids} is only described when the agent can
     *     actually use it (knowledge base on, or a non-empty allow-list) - otherwise it's omitted
     *     so a caller/UI doesn't offer a field this agent would just ignore.
     */
    public String generateInputSchema(Agent agent) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        root.put("type", "object");
        root.put("additionalProperties", false);

        ObjectNode properties = root.putObject("properties");

        ObjectNode query = properties.putObject("query");
        query.put("type", "string");
        query.put("description", "The user message / task for this agent to act on.");

        ObjectNode contextData = properties.putObject("context_data");
        contextData.put("type", "object");
        contextData.put(
                "description",
                "Optional structured context prepended to the query, e.g. {type, content}.");
        contextData.putObject("properties").putObject("type").put("type", "string");
        ((ObjectNode) contextData.path("properties")).putObject("content");

        boolean knowledgeCapable =
                agent.isKnowledgeBaseEnabled()
                        || !agent.getAllowedKnowledgeCollectionIds().isEmpty();
        if (knowledgeCapable) {
            ObjectNode collectionIds = properties.putObject("knowledge_collection_ids");
            collectionIds.put("type", "array");
            collectionIds.put(
                    "description",
                    "Optional subset of this agent's allowed knowledge collection ids to search.");
            collectionIds.putObject("items").put("type", "string");
        }

        ObjectNode approvalRequired = properties.putObject("approval_required");
        approvalRequired.put("type", "boolean");
        approvalRequired.put(
                "description",
                "When true, this call must be approved by a human before the agent runs.");

        ArrayNode required = root.putArray("required");
        required.add("query");

        return writeString(root);
    }

    /**
     * @return a JSON Schema 2020-12 object string describing {@code StructuredAgentOutput} - fixed
     *     for every agent today, since this platform doesn't yet vary an agent's output CONTRACT by
     *     configuration, only its content.
     */
    public String generateOutputSchema(Agent agent) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        root.put("type", "object");

        ObjectNode properties = root.putObject("properties");
        properties.putObject("result_text").put("type", "string");
        ObjectNode confidence = properties.putObject("confidence");
        confidence.put("type", "number");
        confidence.put("minimum", 0);
        confidence.put("maximum", 1);
        properties
                .putObject("sources")
                .put("type", "array")
                .putObject("items")
                .put("type", "string");

        ObjectNode toolCalls = properties.putObject("tool_calls");
        toolCalls.put("type", "array");
        ObjectNode toolCallItem = toolCalls.putObject("items");
        toolCallItem.put("type", "object");
        ObjectNode toolCallProps = toolCallItem.putObject("properties");
        toolCallProps.putObject("tool_name").put("type", "string");
        toolCallProps.putObject("input").put("type", "string");
        toolCallProps.putObject("output").put("type", "string");

        properties.putObject("metadata").put("type", "object");

        ArrayNode required = root.putArray("required");
        required.add("result_text");

        return writeString(root);
    }

    private String writeString(ObjectNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            // Generation is pure in-memory JSON construction - a failure here
            // would mean Jackson itself is broken, not a data problem. Falls
            // back to an empty object rather than propagating, consistent
            // with this codebase's fail-open posture for non-critical
            // enrichment (see WorkflowNodeExecutor's cache-write catch
            // blocks) - a missing schema shouldn't block agent save/update.
            return "{}";
        }
    }
}
