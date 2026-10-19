package com.agenticai.core.workflow;

import static com.agenticai.core.workflow.WorkflowNodeSupport.safeLength;
import static com.agenticai.core.workflow.WorkflowNodeSupport.stringConfig;
import static com.agenticai.core.workflow.WorkflowNodeSupport.truncate;

import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.workflow.WorkflowNodeExecutor.NodeExecutionResult;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The structured (inputMapping) Agent-node path: builds a StructuredAgentInput, calls
 * chatWithParams, records a SubagentRun audit row either way and applies the node's outputMapping.
 */
@Component
class StructuredAgentRunner {

    private final AgentChatService agentChatService;
    private final SubagentRunRepository subagentRunRepository;
    private final NodeConfigResolver configResolver;
    private final NodeOutputMapper outputMapper;

    StructuredAgentRunner(
            AgentChatService agentChatService,
            SubagentRunRepository subagentRunRepository,
            NodeConfigResolver configResolver,
            NodeOutputMapper outputMapper) {
        this.agentChatService = agentChatService;
        this.subagentRunRepository = subagentRunRepository;
        this.configResolver = configResolver;
        this.outputMapper = outputMapper;
    }

    /**
     * Structured counterpart to the plain-text {@link AgentNodeHandler} path above - only reached
     * when this node's config carries a non-empty "inputMapping" map (see that method's javadoc for
     * the backward- compatibility reasoning). Builds an {@link
     * com.agenticai.core.agents.turn.AgentChatService.StructuredAgentInput} from the mapping + this
     * node's upstream {@code input}, calls {@link AgentChatService#chatWithParams}, records a
     * {@link SubagentRun} audit row either way, and applies this node's "outputMapping" config (a
     * single output-field name, default {@code result_text}) to decide what string flows to the
     * next node - defaulting to {@code result_text} means an unconfigured outputMapping behaves
     * exactly like the legacy path's reply text.
     */
    NodeExecutionResult run(
            WorkflowNodeDto node, String input, String runId, UUID agentId, Map<?, ?> mapping) {
        // "query" arrives already resolved (ExpressionFields: agent inputMapping.query); blank
        // means "the input".
        Object queryRaw = mapping.get("query");
        String query =
                queryRaw == null || queryRaw.toString().isBlank()
                        ? (input == null ? "" : input)
                        : queryRaw.toString();

        Map<String, Object> contextData = configResolver.resolveContextData(mapping.get("context_data"));
        List<UUID> knowledgeCollectionIds =
                configResolver.resolveKnowledgeCollectionIds(mapping.get("knowledge_collection_ids"));
        boolean approvalRequired = Boolean.TRUE.equals(node.config().get("requiresApproval"));

        AgentChatService.StructuredAgentInput structuredInput =
                new AgentChatService.StructuredAgentInput(
                        query, contextData, knowledgeCollectionIds, approvalRequired);

        List<String> logLines = new ArrayList<>();
        logLines.add(
                "Calling agent "
                        + agentId
                        + " with structured input (query "
                        + safeLength(query)
                        + " chars"
                        + (contextData.isEmpty() ? "" : ", context_data present")
                        + (knowledgeCollectionIds.isEmpty()
                                ? ""
                                : ", " + knowledgeCollectionIds.size() + " knowledge collection(s)")
                        + ").");

        long startedAt = System.currentTimeMillis();
        String inputJson =
                outputMapper.writeJsonQuietly(
                        Map.of(
                                "query",
                                query,
                                "context_data",
                                contextData,
                                "knowledge_collection_ids",
                                knowledgeCollectionIds,
                                "approval_required",
                                approvalRequired));

        AgentChatService.StructuredAgentOutput output;
        try {
            output =
                    agentChatService.chatWithParams(
                            agentId, runId, runId, structuredInput, node.id(), AgentNodeHandler.AGENT_NODE_TIMEOUT);
        } catch (IllegalArgumentException e) {
            long latencyMs = System.currentTimeMillis() - startedAt;
            subagentRunRepository.save(
                    new SubagentRun(
                            UUID.fromString(runId),
                            node.id(),
                            agentId,
                            inputJson,
                            null,
                            "VALIDATION_FAILED",
                            latencyMs,
                            e.getMessage()));
            logLines.add("Agent input validation failed: " + e.getMessage());
            return NodeExecutionResult.skipped(
                    input, "Agent input validation failed: " + e.getMessage(), logLines);
        }
        long latencyMs = System.currentTimeMillis() - startedAt;

        for (AgentChatService.ToolCallSummary call : output.toolCalls()) {
            logLines.add(
                    "Tool call: "
                            + call.toolName()
                            + "("
                            + truncate(call.input(), 150)
                            + ") -> "
                            + truncate(call.output(), 200));
        }
        logLines.add(
                "Agent replied ("
                        + safeLength(output.resultText())
                        + " chars, confidence "
                        + output.confidence()
                        + ")"
                        + (output.toolCalls().isEmpty() ? " - no tools called." : "."));

        String outputJson =
                outputMapper.writeJsonQuietly(
                        Map.of(
                                "result_text",
                                output.resultText(),
                                "confidence",
                                output.confidence(),
                                "sources",
                                output.sources(),
                                "tool_calls",
                                output.toolCalls(),
                                "metadata",
                                output.metadata()));
        subagentRunRepository.save(
                new SubagentRun(
                        UUID.fromString(runId),
                        node.id(),
                        agentId,
                        inputJson,
                        outputJson,
                        "OK",
                        latencyMs,
                        null));

        String outputField = stringConfig(node, "outputMapping", "result_text");
        String mappedOutput = outputMapper.applyOutputMapping(outputField, output);
        return NodeExecutionResult.ok(mappedOutput, truncate(mappedOutput, 300), null, logLines);
    }
}
