package com.agenticai.core.workflow;

import com.agenticai.core.guardrails.taint.Spotlight;
import com.agenticai.core.knowledge.KnowledgeSearchService;
import com.agenticai.core.knowledge.dto.SearchResultResponse;
import com.agenticai.core.workflow.WorkflowNodeExecutor.NodeExecutionResult;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * "knowledge": similarity search over the knowledge base, prepended to the input as context.
 *
 * <p>Retrieved chunks are attacker-authored content (anyone who can write to a collection can plant
 * instructions), so the injected block is treated exactly like an {@code UNTRUSTED_EXTERNAL} tool
 * result: each chunk carries its source citation, and the combined block is {@link
 * Spotlight}-delimited - the same "data, not instructions" banner and unforgeable delimiters a
 * guarded tool wraps its output in. The delimiters are also the taint carrier: a downstream agent
 * node receives this output as its turn input, and the prompt assembler marks the turn tainted when
 * it sees them, so sink tools gate the same way they would had the chunks arrived through {@code
 * search_knowledge_base}.
 */
@Component
class KnowledgeNodeHandler implements NodeHandler {

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
        String combined = combineWithCitations(results);
        String output =
                combined.isBlank()
                        ? input
                        : "Context from knowledge base:\n"
                                + Spotlight.wrap("knowledge_base", combined)
                                + "\n\nOriginal input: "
                                + input;
        return NodeExecutionResult.ok(
                output, results.size() + " chunk(s) retrieved.", null, logLines);
    }

    /**
     * One numbered block per chunk, each followed by its citation so a downstream agent (and the
     * run transcript) can attribute every retrieved claim to the document it came from. An empty
     * result set yields "" (the caller then passes the input through untouched).
     */
    private static String combineWithCitations(List<SearchResultResponse> results) {
        StringBuilder combined = new StringBuilder();
        int index = 0;
        for (SearchResultResponse result : results) {
            if (result == null || result.content() == null || result.content().isBlank()) {
                continue;
            }
            index++;
            if (combined.length() > 0) {
                combined.append("\n");
            }
            combined.append("[").append(index).append("] ").append(result.content());
            String citation = citationOf(result);
            if (citation != null) {
                combined.append("\n    (source: ").append(citation).append(")");
            }
        }
        return combined.toString();
    }

    /** "name <url>", else whichever of the chunk's source fields exist; null when none do. */
    private static String citationOf(SearchResultResponse result) {
        String source =
                result.source() == null || result.source().isBlank()
                        ? null
                        : result.source().trim();
        String url =
                result.sourceUrl() == null || result.sourceUrl().isBlank()
                        ? null
                        : result.sourceUrl().trim();
        if (source == null && url == null) {
            return result.documentId() == null ? null : "document " + result.documentId();
        }
        return url == null ? source : (source == null ? url : source + " <" + url + ">");
    }
}
