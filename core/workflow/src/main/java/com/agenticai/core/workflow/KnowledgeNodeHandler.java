package com.agenticai.core.workflow;

import com.agenticai.core.guardrails.taint.Spotlight;
import com.agenticai.core.guardrails.taint.TaintMode;
import com.agenticai.core.knowledge.KnowledgeSearchService;
import com.agenticai.core.knowledge.dto.SearchResultResponse;
import com.agenticai.core.workflow.WorkflowNodeExecutor.NodeExecutionResult;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * "knowledge": similarity search over the knowledge base, prepended to the input as context.
 *
 * <p><b>Untrusted data.</b> Retrieved chunks are stored content that may be attacker-authored, so
 * in {@link TaintMode#ENFORCE} the combined chunks are wrapped in the same unforgeable {@link
 * Spotlight} delimiters the tool path uses - the downstream model sees them as data, not
 * instructions. A downstream Agent node detects the wrapper and seeds its chat turn's taint
 * (AgentNodeHandler), so sink tool calls in that turn are gated like any tainted turn.
 */
@Component
class KnowledgeNodeHandler implements NodeHandler {

    private final ObjectProvider<KnowledgeSearchService> knowledgeSearchService;
    private final TaintMode taintMode;

    KnowledgeNodeHandler(
            ObjectProvider<KnowledgeSearchService> knowledgeSearchService,
            @Value("${app.agents.taint.mode:observe}") String taintMode) {
        this.knowledgeSearchService = knowledgeSearchService;
        this.taintMode = TaintMode.parse(taintMode);
    }

    @Override
    public Set<String> nodeTypes() {
        return Set.of("knowledge");
    }

    @Override
    public NodeExecutionResult handle(NodeInvocation inv) {
        return executeKnowledgeNode(inv.node(), inv.input());
    }

    private NodeExecutionResult executeKnowledgeNode(WorkflowNodeDto node, String input) {
        KnowledgeSearchService service = knowledgeSearchService.getIfAvailable();
        if (service == null) {
            return NodeExecutionResult.skipped(
                    input, "Knowledge search unavailable - the 'knowledge' profile is not active.");
        }
        Object collectionIdRaw = node.config().get("collectionId");
        Object topKRaw = node.config().get("topK");
        int topK = topKRaw instanceof Number n ? n.intValue() : 5;
        Set<UUID> collectionIds =
                collectionIdRaw != null && !collectionIdRaw.toString().isBlank()
                        ? Set.of(UUID.fromString(collectionIdRaw.toString()))
                        : Set.of();
        List<String> logLines = new ArrayList<>();
        logLines.add(
                "Searching knowledge base (topK="
                        + topK
                        + (collectionIds.isEmpty() ? ", all collections" : ", 1 collection")
                        + ").");
        List<SearchResultResponse> results = service.search(input, topK, collectionIds);
        logLines.add(results.size() + " chunk(s) retrieved.");
        String combined =
                results.stream()
                        .map(SearchResultResponse::content)
                        .reduce((a, b) -> a + "\n---\n" + b)
                        .orElse("");
        // ENFORCE: delimit the chunks as untrusted data (same wrapper as the tool path), so a
        // poisoned chunk is data, not instructions, for the downstream model.
        if (!combined.isBlank() && taintMode == TaintMode.ENFORCE) {
            combined = Spotlight.wrap("knowledge", combined);
        }
        String output =
                combined.isBlank()
                        ? input
                        : "Context from knowledge base:\n"
                                + combined
                                + "\n\nOriginal input: "
                                + input;
        return NodeExecutionResult.ok(
                output, results.size() + " chunk(s) retrieved.", null, logLines);
    }
}
