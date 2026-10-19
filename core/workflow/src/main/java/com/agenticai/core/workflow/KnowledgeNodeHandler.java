package com.agenticai.core.workflow;


import com.agenticai.core.workflow.WorkflowNodeExecutor.NodeExecutionResult;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.agenticai.core.knowledge.KnowledgeSearchService;
import com.agenticai.core.knowledge.dto.SearchResultResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** "knowledge": similarity search over the knowledge base, prepended to the input as context. */
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
        String combined =
                results.stream()
                        .map(SearchResultResponse::content)
                        .reduce((a, b) -> a + "\n---\n" + b)
                        .orElse("");
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
