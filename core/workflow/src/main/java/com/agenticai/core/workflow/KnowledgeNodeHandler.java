package com.agenticai.core.workflow;


import com.agenticai.core.workflow.WorkflowNodeExecutor.NodeExecutionResult;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.agenticai.core.guardrails.taint.Spotlight;
import com.agenticai.core.knowledge.KnowledgeSearchService;
import com.agenticai.core.knowledge.dto.SearchResultResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * "knowledge": similarity search over the knowledge base, prepended to the input as context. The
 * chunks are spotlighted as untrusted data with numbered source citations; an invalid collectionId
 * skips the node and topK is clamped to 1..20.
 */
@Component
class KnowledgeNodeHandler implements NodeHandler {

    static final int DEFAULT_TOP_K = 5;
    static final int MIN_TOP_K = 1;
    static final int MAX_TOP_K = 20;

    private final ObjectProvider<KnowledgeSearchService> knowledgeSearchService;
    KnowledgeNodeHandler(ObjectProvider<KnowledgeSearchService> knowledgeSearchService) {
        this.knowledgeSearchService = knowledgeSearchService;
    }

    @Override
    public Set<String> nodeTypes() {
        return Set.of("knowledge");
    }

    @Override
    public NodeExecutionResult handle(NodeInvocation inv) {
        return executeKnowledgeNode(inv.node(), inv.input());
    }

    NodeExecutionResult executeKnowledgeNode(WorkflowNodeDto node, String input) {
        KnowledgeSearchService service = knowledgeSearchService.getIfAvailable();
        if (service == null) {
            return NodeExecutionResult.skipped(
                    input, "Knowledge search unavailable - the 'knowledge' profile is not active.");
        }
        Object collectionIdRaw = node.config().get("collectionId");
        List<String> logLines = new ArrayList<>();
        int topK = clampTopK(node.config().get("topK"), logLines);
        Set<UUID> collectionIds = Set.of();
        if (collectionIdRaw != null && !collectionIdRaw.toString().isBlank()) {
            try {
                collectionIds = Set.of(UUID.fromString(collectionIdRaw.toString().trim()));
            } catch (IllegalArgumentException e) {
                return NodeExecutionResult.skipped(
                        input,
                        "Knowledge node skipped: collectionId '"
                                + abbreviate(collectionIdRaw.toString())
                                + "' is not a valid UUID.");
            }
        }
        logLines.add(
                "Searching knowledge base (topK="
                        + topK
                        + (collectionIds.isEmpty() ? ", all collections" : ", 1 collection")
                        + ").");
        List<SearchResultResponse> results = service.search(input, topK, collectionIds);
        logLines.add(results.size() + " chunk(s) retrieved.");
        StringBuilder chunks = new StringBuilder();
        int n = 0;
        for (SearchResultResponse r : results) {
            if (r.content() == null || r.content().isBlank()) {
                continue;
            }
            n++;
            if (n > 1) {
                chunks.append("\n---\n");
            }
            chunks.append('[').append(n).append("] ").append(citation(r)).append('\n').append(r.content());
        }
        // Retrieved chunks are user-supplied documents: framed as untrusted data (same banner and
        // unforgeable delimiters as tool results), with numbered sources the model can cite.
        String output =
                n == 0
                        ? input
                        : "Context from knowledge base (cite sources as [n]):\n"
                                + Spotlight.wrap("knowledge", chunks.toString())
                                + "\n\nOriginal input: "
                                + input;
        return NodeExecutionResult.ok(
                output, results.size() + " chunk(s) retrieved.", null, logLines);
    }

    static int clampTopK(Object raw, List<String> logLines) {
        int requested = DEFAULT_TOP_K;
        if (raw instanceof Number num) {
            requested = num.intValue();
        } else if (raw != null && !raw.toString().isBlank()) {
            try {
                requested = Integer.parseInt(raw.toString().trim());
            } catch (NumberFormatException e) {
                logLines.add("topK '" + abbreviate(raw.toString()) + "' is not a number; using " + DEFAULT_TOP_K + ".");
            }
        }
        int clamped = Math.max(MIN_TOP_K, Math.min(MAX_TOP_K, requested));
        if (clamped != requested) {
            logLines.add("topK " + requested + " is outside " + MIN_TOP_K + ".." + MAX_TOP_K + "; using " + clamped + ".");
        }
        return clamped;
    }

    private static String citation(SearchResultResponse r) {
        StringBuilder c = new StringBuilder("Source: ");
        c.append(r.source() == null || r.source().isBlank() ? "unknown" : oneLine(r.source()));
        if (r.sourceUrl() != null && !r.sourceUrl().isBlank()) {
            c.append(" (").append(oneLine(r.sourceUrl())).append(')');
        }
        return c.append(", chunk ").append(r.chunkIndex()).toString();
    }

    private static String oneLine(String s) {
        return abbreviate(s.replaceAll("[\\r\\n]+", " ").trim());
    }

    private static String abbreviate(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
