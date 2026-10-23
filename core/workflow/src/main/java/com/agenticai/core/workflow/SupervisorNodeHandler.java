package com.agenticai.core.workflow;

import static com.agenticai.core.workflow.WorkflowNodeSupport.stringConfig;
import static com.agenticai.core.workflow.WorkflowNodeSupport.truncate;

import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptSlot;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.workflow.WorkflowNodeExecutor.NodeExecutionResult;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.agenticai.core.workflow.stream.NodeStreamContext;
import com.agenticai.core.workflow.stream.NodeStreamSink;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** "supervisor": asks an agent to pick one of the configured routes (branch = route handle). */
@Component
class SupervisorNodeHandler implements NodeHandler {

    /** Registry keys of the routing request (default text: {@code prompts/en/workflow.supervisor.*.md}). */
    static final String ROUTE_KEY = "workflow.supervisor.route";

    static final String INSTRUCTIONS_KEY = "workflow.supervisor.instructions";

    private final PromptSlot prompts = new PromptSlot();

    /** The prompt registry, so a tenant or agent override of the routing request applies; absent = shipped text. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPromptResolver(PromptResolver resolver) {
        prompts.use(resolver);
    }

    private final AgentChatService agentChatService;
    private final NodeConfigResolver configResolver;

    SupervisorNodeHandler(AgentChatService agentChatService, NodeConfigResolver configResolver) {
        this.agentChatService = agentChatService;
        this.configResolver = configResolver;
    }

    @Override
    public Set<String> nodeTypes() {
        return Set.of("supervisor");
    }

    @Override
    public NodeExecutionResult handle(NodeInvocation inv) {
        return executeSupervisorNode(inv.node(), inv.input(), inv.runId());
    }

    /**
     * Supervisor Routing node (Master Roadmap 2026 item #12, §E). See {@link WorkflowNodeType}'s
     * javadoc for the overall design; the key point repeated here: routes are a config-declared
     * {@code routes} list (each a {@code handle}/{@code label} pair), NOT read off this node's
     * outgoing edges, so this stays a plain single-node dispatch case exactly like {@link
     * #executeConditionNode} - the chosen route's {@code handle} becomes this result's {@code
     * branch()}, and {@link WorkflowExecutionEngine}'s existing edge {@code sourceHandle}
     * resolution (built for Condition's true/false ports) does the rest with zero engine changes.
     *
     * <p>Reuses {@link AgentChatService#chat} - the exact call {@link AgentNodeHandler} makes -
     * rather than new raw-{@code ChatClient} plumbing, so a routing decision goes through the same
     * provider/model/tool-call guardrails a normal Agent node already does. Trade-off accepted
     * deliberately: nothing stops a tool-enabled agent from calling a tool while making this
     * decision, so workflow authors should point Supervisor nodes at a lean, focused agent, the
     * same guidance that already applies to any Agent node used for a structured decision. Route
     * matching is best-effort text matching against the model's plain-text reply, not forced
     * JSON/tool output - defensible since route lists are small and this avoids new
     * structured-output plumbing for one node type. Any failure (routing call errors, no match)
     * falls open to the FIRST configured route rather than halting the run - the same
     * fail-open-to-safe-default posture {@code EntityExtractionService}/{@code
     * MemoryWriteArbitrationService}/{@code LlmQualityJudgeService} already established for their
     * own LLM-backed decision layers.
     */
    private NodeExecutionResult executeSupervisorNode(
            WorkflowNodeDto node, String input, String runId) {
        Object agentIdRaw = node.config().get("agentId");
        if (agentIdRaw == null || agentIdRaw.toString().isBlank()) {
            return NodeExecutionResult.skipped(
                    input, "No agent selected for this Supervisor node.");
        }
        List<Map<String, String>> routes = configResolver.resolveRoutes(node);
        if (routes.isEmpty()) {
            return NodeExecutionResult.skipped(
                    input, "No routes configured for this Supervisor node.");
        }
        UUID agentId;
        try {
            agentId = UUID.fromString(agentIdRaw.toString());
        } catch (IllegalArgumentException e) {
            return NodeExecutionResult.skipped(input, "Invalid agent id: " + agentIdRaw);
        }

        String instructions = stringConfig(node, "instructions", null);
        StringBuilder routesBlock = new StringBuilder();
        for (Map<String, String> route : routes) {
            routesBlock
                    .append("- ")
                    .append(route.get("handle"))
                    .append(": ")
                    .append(route.get("label"))
                    .append("\n");
        }
        String instructionsBlock =
                instructions != null
                        ? prompts.text(INSTRUCTIONS_KEY, agentId, Map.of("instructions", instructions))
                        : "";
        String routingPrompt =
                prompts.text(
                        ROUTE_KEY,
                        agentId,
                        Map.of(
                                "routes", routesBlock.toString(),
                                "instructions", instructionsBlock,
                                "input", input == null ? "null" : input));

        List<String> logLines = new ArrayList<>();
        logLines.add(
                "Supervisor routing among "
                        + routes.size()
                        + " route(s) via agent "
                        + agentId
                        + ".");
        String reply;
        try {
            // Captured on this thread - see AgentNodeHandler. NOOP when no live stream.
            NodeStreamSink sink = NodeStreamContext.current();
            ChatTurnRequest chatTurn =
                    ChatTurnRequest.builder(agentId, runId, runId, routingPrompt).build();
            TurnContext turnContext =
                    TurnContext.forEntry(TenantContext.current().orElse(null), chatTurn);
            reply =
                    agentChatService
                            .chat(turnContext, chatTurn)
                            .doOnNext(sink::token)
                            .collectList()
                            .map(chunks -> String.join("", chunks))
                            .blockOptional(AgentNodeHandler.AGENT_NODE_TIMEOUT)
                            .orElse("");
        } catch (RuntimeException e) {
            String fallbackHandle = routes.get(0).get("handle");
            logLines.add(
                    "Supervisor routing call failed ("
                            + e.getMessage()
                            + ") - defaulting to '"
                            + fallbackHandle
                            + "'.");
            return NodeExecutionResult.ok(
                    input,
                    "Supervisor defaulted to '" + fallbackHandle + "' (routing call failed).",
                    fallbackHandle,
                    logLines);
        }

        String chosen = matchRoute(reply, routes);
        logLines.add(
                "Supervisor reply: \""
                        + truncate(reply, 150)
                        + "\" -> routed to '"
                        + chosen
                        + "'.");
        return NodeExecutionResult.ok(
                input, "Supervisor routed to '" + chosen + "'.", chosen, logLines);
    }

    private static String matchRoute(String reply, List<Map<String, String>> routes) {
        String normalized = reply == null ? "" : reply.trim().toLowerCase(java.util.Locale.ROOT);
        for (Map<String, String> route : routes) {
            if (normalized.equals(route.get("handle").toLowerCase(java.util.Locale.ROOT))) {
                return route.get("handle");
            }
        }
        if (!normalized.isBlank()) {
            for (Map<String, String> route : routes) {
                if (normalized.contains(route.get("handle").toLowerCase(java.util.Locale.ROOT))) {
                    return route.get("handle");
                }
            }
        }
        return routes.get(0).get("handle");
    }
}
