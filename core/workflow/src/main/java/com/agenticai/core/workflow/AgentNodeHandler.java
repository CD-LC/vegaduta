package com.agenticai.core.workflow;

import static com.agenticai.core.workflow.WorkflowNodeSupport.safeLength;
import static com.agenticai.core.workflow.WorkflowNodeSupport.truncate;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.agents.dto.ChatInlineAttachment;
import com.agenticai.core.agents.tools.AgentStepEventEmitter;
import com.agenticai.core.agents.tools.ToolCallLog;
import com.agenticai.core.agents.tools.ToolCallLogService;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.agents.turn.cache.LlmResponseCacheService;
import com.agenticai.core.agents.turn.quality.QueryComplexityClassifier;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.workflow.WorkflowNodeExecutor.NodeExecutionResult;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.agenticai.core.workflow.stream.NodeStreamContext;
import com.agenticai.core.workflow.stream.NodeStreamSink;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** "agent": one chat turn against a configured agent (plain, or structured via inputMapping). */
@Component
class AgentNodeHandler implements NodeHandler {

    static final Duration AGENT_NODE_TIMEOUT = Duration.ofSeconds(60);

    private static final Logger log = LoggerFactory.getLogger(AgentNodeHandler.class);

    private final AgentService agentService;
    private final AgentChatService agentChatService;
    private final LlmResponseCacheService llmResponseCacheService;
    private final QueryComplexityClassifier queryComplexityClassifier;
    private final ToolCallLogService toolCallLogService;
    private final EconomyModelCaller economyModelCaller;
    private final StructuredAgentRunner structuredAgentRunner;

    AgentNodeHandler(
            AgentService agentService,
            AgentChatService agentChatService,
            LlmResponseCacheService llmResponseCacheService,
            QueryComplexityClassifier queryComplexityClassifier,
            ToolCallLogService toolCallLogService,
            EconomyModelCaller economyModelCaller,
            StructuredAgentRunner structuredAgentRunner) {
        this.agentService = agentService;
        this.agentChatService = agentChatService;
        this.llmResponseCacheService = llmResponseCacheService;
        this.queryComplexityClassifier = queryComplexityClassifier;
        this.toolCallLogService = toolCallLogService;
        this.economyModelCaller = economyModelCaller;
        this.structuredAgentRunner = structuredAgentRunner;
    }

    @Override
    public Set<String> nodeTypes() {
        return Set.of("agent");
    }

    @Override
    public NodeExecutionResult handle(NodeInvocation inv) {
        return executeAgentNode(
                inv.node(),
                inv.input(),
                inv.runId(),
                inv.intelligenceSettings(),
                inv.attachments());
    }

    private NodeExecutionResult executeAgentNode(
            WorkflowNodeDto node,
            String input,
            String runId,
            WorkflowIntelligenceSettings settings,
            List<ChatInlineAttachment> attachments) {
        Object agentIdRaw = node.config().get("agentId");
        UUID agentId = UUID.fromString(agentIdRaw.toString());

        // Workflow -> Agent structured parameter passing - additive branch,
        // NOT a rewrite of anything below. Every workflow saved before this
        // feature existed has an Agent node config shaped exactly
        // {agentId: "..."}, with no "inputMapping" key at all - those nodes
        // must keep behaving identically to today, forever, so the
        // structured path only ever activates when a node's config
        // explicitly carries a non-empty "inputMapping" map (written by
        // NodeConfigDrawer's "Advanced: structured input" section once a
        // workflow author opts in). approval_required from that section is
        // NOT handled here - it's written into this node's top-level
        // "requiresApproval" config key instead, reusing the generic gate
        // executeNode already evaluates before any node type dispatches.
        Object inputMappingRaw = node.config().get("inputMapping");
        if (inputMappingRaw instanceof Map<?, ?> mapping && !mapping.isEmpty()) {
            return structuredAgentRunner.run(node, input, runId, agentId, mapping);
        }

        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        List<String> logLines = new ArrayList<>();

        try {
            AgentResponse agent = agentService.get(agentId);
            logLines.add(
                    "Agent: \""
                            + agent.name()
                            + "\" - model "
                            + agent.providerName()
                            + "/"
                            + agent.model()
                            + " (temperature "
                            + agent.temperature()
                            + ", max "
                            + agent.maxTokens()
                            + " tokens).");
            logLines.add(
                    "Memory: "
                            + (agent.knowledgeBaseEnabled()
                                    ? "long-term knowledge base search is ON for this agent."
                                    : "long-term knowledge base search is OFF for this agent -"
                                          + " answering from the model + this conversation only."));
            logLines.add(
                    "Tools: "
                            + (agent.allowedToolNames() == null
                                            || agent.allowedToolNames().isEmpty()
                                    ? "no manual allow-list - every currently available"
                                            + " MCP/knowledge/memory tool is a candidate, narrowed"
                                            + " automatically to what's relevant to this input."
                                    : "restricted to "
                                            + agent.allowedToolNames().size()
                                            + " allowed tool(s): "
                                            + String.join(", ", agent.allowedToolNames())
                                            + "."));
        } catch (RuntimeException e) {
            logLines.add(
                    "Agent "
                            + agentId
                            + " configuration could not be read for this transparency header: "
                            + e.getMessage());
        }

        logLines.add(
                "Calling agent "
                        + agentId
                        + " with input ("
                        + safeLength(input)
                        + " chars): "
                        + truncate(input, 200));

        // Step caching: return a previously cached response for identical input
        // before making any LLM call. Same exact-match-with-normalization posture
        // as LlmResponseCacheService's own class javadoc - this is the workflow
        // layer opt-in of the same cache AgentChatService uses per-agent.
        if (settings.stepCachingEnabled()) {
            Optional<String> cached =
                    llmResponseCacheService.get(tenantId, agentId, stepCachePrincipal(), input);
            if (cached.isPresent()) {
                logLines.add("Cache hit - returning cached response for this input.");
                return NodeExecutionResult.ok(
                        cached.get(), truncate(cached.get(), 300), null, logLines);
            }
        }

        // Cost routing: route simple queries to the cheaper economy model when
        // the workflow author has configured one. QueryComplexityClassifier's
        // conservative heuristic means only clearly simple turns (short, single
        // clause, no code/complex-signal words) get the discount - complex turns
        // always go through the primary agent path.
        boolean useEconomy =
                settings.costRoutingEnabled()
                        && settings.economyProviderName() != null
                        && settings.economyModel() != null
                        && queryComplexityClassifier.isSimple(input);

        String reply;
        if (useEconomy) {
            reply = economyModelCaller.callEconomyModel(input, settings);
        } else {
            // Stage 4 (Chat file upload) added trailing attachmentKey/attachmentMimeType
            // params to AgentChatService.chat() - null/null here, same reasoning as every
            // omnichannel inbound path (see DiscordWebhookController's comment): workflow
            // Agent-node runs never carry an uploaded chat-attachment reference.
            //
            // Fail-proof/learning-loop hardening: this node's own id is passed as
            // turnRef (the 8-arg overload) - without it, AgentChatService#beginRlEpisode
            // returns immediately (turnRef == null is its explicit "Playground-only,
            // skip RL" guard), so a workflow-driven agent turn NEVER became an
            // rl_episode and had nothing for a downstream Approval node's
            // approve/reject decision to reward (see FeedbackService
            // #recordApprovalDecision, and HumanApprovalGateService's new call to
            // it). runId doubles as this turn's sessionId (same as the chat call
            // itself, unchanged) - that is exactly the key
            // #recordApprovalDecision looks episodes up by.
            //
            // Live run stream (phase 3): when the engine bound a live sink for
            // this node, the same turn runs through the step-event overload so
            // tool calls surface as they happen, and each token is teed to the
            // sink. The sink is captured HERE, on the calling thread - Reactor
            // delivers chunks on its own threads where the ThreadLocal is empty.
            // No live sink = the exact call this node always made.
            NodeStreamSink sink = NodeStreamContext.current();
            if (sink.isLive()) {
                AgentStepEventEmitter steps = AgentStepEventEmitter.active();
                steps.asFlux().subscribe(sink::stepEvent, e -> {});
                ChatTurnRequest chatTurn =
                        new ChatTurnRequest(
                                agentId,
                                runId,
                                runId,
                                input,
                                null,
                                null,
                                attachments == null ? List.of() : attachments,
                                List.of(),
                                node.id(),
                                null,
                                false,
                                steps,
                                false);
                TurnContext turnContext =
                        TurnContext.forEntry(TenantContext.current().orElse(null), chatTurn);
                reply =
                        agentChatService
                                .chat(turnContext, chatTurn)
                                .doOnNext(sink::token)
                                .doFinally(signal -> steps.complete())
                                .collectList()
                                .map(chunks -> String.join("", chunks))
                                .blockOptional(AGENT_NODE_TIMEOUT)
                                .orElse("");
            } else {
                ChatTurnRequest chatTurn =
                        ChatTurnRequest.builder(agentId, runId, runId, input)
                                .inlineAttachments(attachments == null ? List.of() : attachments)
                                .turnRef(node.id())
                                .build();
                TurnContext turnContext =
                        TurnContext.forEntry(TenantContext.current().orElse(null), chatTurn);
                reply =
                        agentChatService
                                .chat(turnContext, chatTurn)
                                .collectList()
                                .map(chunks -> String.join("", chunks))
                                .blockOptional(AGENT_NODE_TIMEOUT)
                                .orElse("");
            }
        }

        List<ToolCallLog> toolCalls = toolCallLogService.findByAgentIdAndSessionId(agentId, runId);
        for (ToolCallLog call : toolCalls) {
            if (call.getErrorMessage() != null) {
                logLines.add(
                        "Tool call: "
                                + call.getToolName()
                                + "("
                                + truncate(call.getToolInput(), 150)
                                + ") -> ERROR: "
                                + call.getErrorMessage());
            } else {
                logLines.add(
                        "Tool call: "
                                + call.getToolName()
                                + "("
                                + truncate(call.getToolInput(), 150)
                                + ") -> "
                                + truncate(call.getToolOutput(), 200));
            }
        }
        logLines.add(
                "Agent replied ("
                        + reply.length()
                        + " chars)"
                        + (toolCalls.isEmpty() ? " - no tools called." : "."));

        // Cache the reply after a successful LLM call so future identical
        // inputs in the same workflow run (or subsequent runs within the TTL)
        // can be served without an extra LLM round-trip.
        if (settings.stepCachingEnabled() && !reply.isBlank()) {
            try {
                llmResponseCacheService.put(tenantId, agentId, stepCachePrincipal(), input, reply);
            } catch (RuntimeException e) {
                log.warn(
                        "Failed to write workflow step cache for agent {} - reply was still"
                                + " returned normally: {}",
                        agentId,
                        e.getMessage());
            }
        }

        return NodeExecutionResult.ok(reply, truncate(reply, 300), null, logLines);
    }

    /**
     * SEC-052: the workflow step cache is keyed per principal like the chat cache - the subject
     * this run executes as (the human who triggered it, or the stable synthetic subject of a
     * webhook/schedule trigger), in its own {@code workflow-step:} namespace so a step answer and a
     * chat answer can never be served for one another either.
     */
    private static String stepCachePrincipal() {
        return "workflow-step:" + TenantContext.current().map(TenantContext::getSubject).orElse("");
    }
}
