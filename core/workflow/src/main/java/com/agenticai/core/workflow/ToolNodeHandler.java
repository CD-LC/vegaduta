package com.agenticai.core.workflow;

import static com.agenticai.core.workflow.WorkflowNodeSupport.safeLength;
import static com.agenticai.core.workflow.WorkflowNodeSupport.stringConfig;
import static com.agenticai.core.workflow.WorkflowNodeSupport.truncate;

import com.agenticai.core.workflow.WorkflowNodeExecutor.NodeExecutionResult;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.agenticai.core.agents.tools.AgentToolProvider;
import com.agenticai.core.workflow.output.WorkflowOutputDedupGuard;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** "tool": invokes exactly one named tool from an agent's resolvable tool catalog. */
@Component
class ToolNodeHandler implements NodeHandler {

    private final AgentToolProvider agentToolProvider;
    // Null in slim test contexts: the node then runs without idempotency (a
    // retry may duplicate the tool's side effect) - same posture as before.
    private final WorkflowOutputDedupGuard dedupGuard;

    ToolNodeHandler(AgentToolProvider agentToolProvider) {
        this(agentToolProvider, null);
    }

    @Autowired
    ToolNodeHandler(AgentToolProvider agentToolProvider, WorkflowOutputDedupGuard dedupGuard) {
        this.agentToolProvider = agentToolProvider;
        this.dedupGuard = dedupGuard;
    }

    @Override
    public Set<String> nodeTypes() {
        return Set.of("tool");
    }

    @Override
    public NodeExecutionResult handle(NodeInvocation inv) {
        return executeToolNode(inv.node(), inv.input(), inv.runId(), inv.scope());
    }

    /**
     * Tool / MCP Call node - invokes exactly one named tool from a chosen agent's resolvable tool
     * catalog, without routing through a full LLM turn just to make one known call. Reuses {@link
     * AgentToolProvider} (the same resolution path a real chat turn takes) rather than any new
     * tool-invocation machinery, so this stays subject to the exact same guardrails (step ceiling,
     * output sanitization, per-tool approval) and logging (LoggingToolCallback -> ToolCallLog)
     * every other tool call in this platform already goes through.
     */
    private NodeExecutionResult executeToolNode(
            WorkflowNodeDto node, String input, String runId, ExpressionScope scope) {
        Object agentIdRaw = node.config().get("agentId");
        if (agentIdRaw == null || agentIdRaw.toString().isBlank()) {
            return NodeExecutionResult.skipped(input, "No agent selected for this Tool node.");
        }
        String toolName = stringConfig(node, "toolName", null);
        if (toolName == null) {
            return NodeExecutionResult.skipped(input, "No tool selected for this Tool node.");
        }
        UUID agentId;
        try {
            agentId = UUID.fromString(agentIdRaw.toString());
        } catch (IllegalArgumentException e) {
            return NodeExecutionResult.skipped(input, "Invalid agent id: " + agentIdRaw);
        }
        // argsJson is optional free text passed straight through as this
        // tool's call input; blank means "use this node's own input" so a
        // Tool node can sit downstream of another node without the author
        // hand-writing JSON that just repeats what's already flowing
        // through the graph.
        String toolInput = stringConfig(node, "argsJson", input);
        List<String> logLines = new ArrayList<>();
        try {
            // resolveToolCandidatesFor (not resolveToolsFor) - see that
            // method's own javadoc: this node names one exact tool by name
            // and never hands the list to an LLM, so ToolRelevanceSelector's
            // per-turn relevance cap has nothing to contribute and would
            // otherwise risk silently dropping the exact tool this node
            // names as "not relevant" (or, worse, appear to work only for
            // whichever tools happen to land in the capped subset). RIGOROUS
            // -mode tool-call verification still fails open with no chat
            // context (chatClient is null either way), which is correct
            // here: there is no model turn deciding to call this tool, the
            // workflow author wired the call up directly.
            List<ToolCallback> tools =
                    agentToolProvider.resolveToolCandidatesFor(agentId, runId, runId);
            ToolCallback match =
                    tools.stream()
                            .filter(t -> toolName.equals(t.getToolDefinition().name()))
                            .findFirst()
                            .orElse(null);
            if (match == null) {
                return NodeExecutionResult.skipped(
                        input,
                        "Tool '"
                                + toolName
                                + "' is not currently available to this agent (check its allowed-tools list and enabled MCP servers).");
            }
            logLines.add("Calling tool '" + toolName + "' with input: " + truncate(toolInput, 300));
            // Idempotency: workflow execution is at-least-once (a node retry, a Temporal
            // activity retry, a recovered run), and the tool's side effect is not necessarily
            // idempotent - claim (runId, nodeId, hash of the resolved call input) before the
            // call, the same last-hop contract the output/bulk_email dedup guard uses. Loop /
            // fan-out iterations land in the key via the scope index plus the per-item args.
            String dedupKey = dedupGuard == null
                    ? null
                    : dedupGuard.identityKey(
                            "tool",
                            runId,
                            node.id(),
                            (scope != null && scope.index() != null ? scope.index() + "|" : "")
                                    + toolInput);
            if (dedupKey != null && !dedupGuard.tryClaim(dedupKey)) {
                logLines.add("Tool '" + toolName + "' already ran for this run - replay suppressed.");
                return NodeExecutionResult.ok(
                        "{\"deduplicated\":true,\"tool\":\"" + toolName + "\"}",
                        "Tool '" + toolName + "' already ran for this run (idempotent replay suppressed).",
                        null,
                        logLines);
            }
            String result;
            try {
                result = match.call(toolInput);
            } catch (RuntimeException callFailure) {
                // Release so a legitimate retry can still reach the tool - the send may never
                // have happened (same release-on-failure convention as the output sends).
                if (dedupKey != null) {
                    dedupGuard.release(dedupKey);
                }
                throw callFailure;
            }
            logLines.add(
                    "Tool '"
                            + toolName
                            + "' returned ("
                            + safeLength(result)
                            + " chars): "
                            + truncate(result, 300));
            return NodeExecutionResult.ok(result, truncate(result, 300), null, logLines);
        } catch (RuntimeException e) {
            logLines.add("Tool call failed: " + e.getMessage());
            return NodeExecutionResult.skipped(
                    input, "Tool '" + toolName + "' call failed: " + e.getMessage(), logLines);
        }
    }
}
