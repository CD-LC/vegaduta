package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.workflow.WorkflowNodeExecutor.NodeExecutionResult;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** {@link AiClassifierHandler} used directly (the facade path is covered by WorkflowAiNodeHandlerTest). */
class AiClassifierHandlerTest {
    private static final ObjectMapper M = new ObjectMapper();

    @SuppressWarnings("unchecked")
    private static AiClassifierHandler handler(WorkflowAiModel model, boolean enabled) {
        ObjectProvider<WorkflowAiModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(model);
        return new AiClassifierHandler(provider, M, enabled, 12_000, 0.5);
    }

    private static WorkflowNodeDto node() {
        Map<String, Object> config = new HashMap<>();
        config.put("classes", List.of(
            Map.of("handle", "billing", "label", "Billing", "description", "money"),
            Map.of("handle", "other", "label", "Other", "description", "rest")));
        config.put("defaultClass", "other");
        return new WorkflowNodeDto("n1", "question_classifier", Map.of(), Map.of("config", config));
    }

    @Test
    void routesToTheClassTheModelChose() {
        List<WorkflowAiModel.Request> seen = new ArrayList<>();
        NodeExecutionResult r = handler(req -> {
            seen.add(req);
            return "{\"class\":\"billing\",\"confidence\":0.9}";
        }, true).execute(node(), "I was charged twice");
        assertEquals("billing", r.branch());
        assertEquals("I was charged twice", r.output());
        assertEquals(AiClassifierHandler_CLASSIFIER_SYSTEM(), seen.get(0).system());
        assertEquals(AiClassifierHandler_MAX_TOKENS(), seen.get(0).maxTokens());
        assertEquals(0.0, seen.get(0).temperature());
    }

    @Test
    void fallsBackToDefaultOnProviderFailureOnBadReplyAndWhenDisabled() {
        assertEquals("other", handler(req -> { throw new IllegalStateException("x"); }, true).execute(node(), "t").branch());
        assertEquals("other", handler(req -> "no json", true).execute(node(), "t").branch());
        assertEquals("other", handler(req -> "{\"class\":\"billing\"}", false).execute(node(), "t").branch());
        assertEquals("other", handler(null, true).execute(node(), "t").branch());
    }

    @Test
    void lowConfidenceFallsBack() {
        assertEquals("other", handler(req -> "{\"class\":\"billing\",\"confidence\":0.1}", true).execute(node(), "t").branch());
    }

    private static String AiClassifierHandler_CLASSIFIER_SYSTEM() {
        return WorkflowAiNodeHandler.CLASSIFIER_SYSTEM;
    }

    private static int AiClassifierHandler_MAX_TOKENS() {
        return WorkflowAiNodeHandler.CLASSIFIER_MAX_TOKENS;
    }
}
