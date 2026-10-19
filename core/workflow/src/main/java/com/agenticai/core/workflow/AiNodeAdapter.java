package com.agenticai.core.workflow;


import com.agenticai.core.workflow.WorkflowNodeExecutor.NodeExecutionResult;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Dify-style AI nodes (question_classifier, parameter_extractor, variable_aggregator, document_extractor). */
@Component
class AiNodeAdapter implements NodeHandler {

    // Field-injected and optional: null means the node reports itself unavailable.
    @Autowired(required = false)
    private WorkflowAiNodeHandler handler;
    void setHandler(WorkflowAiNodeHandler handler) {
        this.handler = handler;
    }

    @Override
    public Set<String> nodeTypes() {
        return Set.of("question_classifier", "parameter_extractor", "variable_aggregator", "document_extractor");
    }

    @Override
    public NodeExecutionResult handle(NodeInvocation inv) {
        if (handler == null) {
            return NodeExecutionResult.skipped(
                    inv.input(), "AI node handler unavailable - input passed through unchanged.");
        }
        WorkflowNodeDto node = inv.node();
        String input = inv.input();
        return switch (node.type().toLowerCase(java.util.Locale.ROOT)) {
            case "question_classifier" -> handler.executeQuestionClassifier(node, input);
            case "parameter_extractor" -> handler.executeParameterExtractor(node, input);
            case "variable_aggregator" -> handler.executeVariableAggregator(node, input);
            case "document_extractor" -> handler.executeDocumentExtractor(node, input);
            default -> throw new IllegalStateException("Not an AI node: " + node.type());
        };
    }


}
