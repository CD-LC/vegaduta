package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.tools.ToolCallLogService;
import com.agenticai.core.agents.turn.AgentChatService.StructuredAgentInput;
import com.agenticai.core.agents.turn.AgentChatService.StructuredAgentOutput;
import com.agenticai.core.agents.turn.AgentChatService.ToolCallSummary;
import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Workflow -> Agent structured parameter passing, extracted verbatim from {@link AgentChatService}
 * (extract-only split). It drives the same {@link AgentChatService#chat} pipeline every other
 * caller uses and wraps the reply into a {@link StructuredAgentOutput}.
 */
final class ChatStructuredCall {

    private static final Logger log = LoggerFactory.getLogger(AgentChatService.class);

    /**
     * Thread-safe for reads; kept separate from the RL arm mapper since it serves another feature.
     */
    private static final ObjectMapper STRUCTURED_PARAMS_MAPPER =
            com.agenticai.core.json.JsonMappers.plain();

    private final AgentChatService service;
    private final AgentService agentService;
    private final ToolCallLogService toolCallLogService;

    ChatStructuredCall(
            AgentChatService service,
            AgentService agentService,
            ToolCallLogService toolCallLogService) {
        this.service = service;
        this.agentService = agentService;
        this.toolCallLogService = toolCallLogService;
    }

    StructuredAgentOutput chatWithParams(
            UUID agentId,
            String sessionId,
            String subjectKey,
            StructuredAgentInput input,
            String turnRef,
            Duration timeout) {
        if (input.query() == null || input.query().isBlank()) {
            throw new IllegalArgumentException("'query' is required and cannot be blank.");
        }
        // A/B experiments on agent versions (migration V265) - resolved before the agent config is
        // loaded. The nested chat(...) call below re-resolves against this already-routed id, which
        // is a safe no-op (a treatment clone never itself has a RUNNING experiment).
        agentId = service.agentRouting.resolveExperimentAgentId(agentId, sessionId);
        Agent agent = agentService.getEntity(agentId);
        agent =
                service.versionResolver == null
                        ? agent
                        : service.versionResolver.getIfAvailable() == null
                                ? agent
                                : service.agentRouting.pinAndApplyEffectiveAgent(agentId, agent);
        if (input.knowledgeCollectionIds() != null
                && !input.knowledgeCollectionIds().isEmpty()
                && !agent.getAllowedKnowledgeCollectionIds().isEmpty()) {
            Set<UUID> allowed = agent.getAllowedKnowledgeCollectionIds();
            List<UUID> disallowed =
                    input.knowledgeCollectionIds().stream()
                            .filter(id -> !allowed.contains(id))
                            .toList();
            if (!disallowed.isEmpty()) {
                throw new IllegalArgumentException(
                        "knowledge_collection_ids "
                                + disallowed
                                + " are not in this agent's allowed collection list.");
            }
        }

        String userMessage = buildStructuredUserMessage(input);
        Instant since = Instant.now();
        ChatTurnRequest turn =
                ChatTurnRequest.builder(agentId, sessionId, subjectKey, userMessage)
                        .turnRef(turnRef)
                        .build();
        // ADR-0022 stage 4: chatWithParams is the entry for structured callers (workflow nodes,
        // evals); the tenant is the one its caller runs this thread under.
        String reply =
                service.chat(TurnContext.forEntry(TenantContext.current().orElse(null), turn), turn)
                        .collectList()
                        .map(chunks -> String.join("", chunks))
                        .blockOptional(timeout)
                        .orElse("");

        List<ToolCallSummary> toolCalls =
                toolCallLogService.findByAgentIdAndSessionId(agentId, sessionId).stream()
                        .filter(entry -> entry.getStartedAt().isAfter(since))
                        .map(
                                entry ->
                                        new ToolCallSummary(
                                                entry.getToolName(),
                                                entry.getToolInput(),
                                                entry.getToolOutput()))
                        .toList();
        boolean anyToolFailed =
                toolCallLogService.findByAgentIdAndSessionId(agentId, sessionId).stream()
                        .anyMatch(
                                entry ->
                                        entry.getStartedAt().isAfter(since)
                                                && entry.getErrorMessage() != null
                                                && !entry.getErrorMessage().isBlank());
        double confidence = anyToolFailed ? 0.5 : 1.0;

        return new StructuredAgentOutput(reply, confidence, List.of(), toolCalls, Map.of());
    }

    /**
     * Same context-prefixing convention {@code WorkflowNodeExecutor#executeKnowledgeNode} uses for
     * retrieved knowledge context, applied to caller-supplied {@code context_data}.
     */
    private String buildStructuredUserMessage(StructuredAgentInput input) {
        if (input.contextData() == null || input.contextData().isEmpty()) {
            return input.query();
        }
        try {
            String contextJson = STRUCTURED_PARAMS_MAPPER.writeValueAsString(input.contextData());
            return "Context: " + contextJson + "\n\nOriginal input: " + input.query();
        } catch (Exception e) {
            log.warn(
                    "Failed to serialize context_data for structured agent call - proceeding with"
                            + " query only",
                    e);
            return input.query();
        }
    }
}
