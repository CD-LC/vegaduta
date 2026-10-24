package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.prompts.DefaultPromptResolver;
import com.agenticai.core.prompts.InMemoryPromptOverrideStore;
import com.agenticai.core.prompts.PromptOverride;
import com.agenticai.core.prompts.PromptProperties;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptScope;
import com.agenticai.core.prompts.PromptSlot;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** The classifier and extractor node prompts come from the registry on the real node path. */
class WorkflowPromptOverrideTest {

    private static final ObjectMapper M = new ObjectMapper();

    private final InMemoryPromptOverrideStore store = new InMemoryPromptOverrideStore();
    private final DefaultPromptResolver resolver =
            new DefaultPromptResolver(PromptResolver.defaultsOnly().catalog(), store, new PromptProperties());

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void tenantOverride(String key, String text) {
        TenantContext.set(TenantContext.of("a", "owner", Set.of()));
        store.put(new PromptOverride(PromptScope.TENANT, "", key, "en", text, true, 1, "owner", Instant.now()));
        resolver.evictAll();
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<WorkflowAiModel> provider(WorkflowAiModel model) {
        ObjectProvider<WorkflowAiModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(model);
        return provider;
    }

    private static WorkflowNodeDto classifierNode() {
        Map<String, Object> config = new HashMap<>();
        config.put("classes", List.of(
            Map.of("handle", "billing", "label", "Billing", "description", "money"),
            Map.of("handle", "other", "label", "Other", "description", "rest")));
        config.put("defaultClass", "other");
        return new WorkflowNodeDto("n1", "question_classifier", Map.of(), Map.of("config", config));
    }

    @Test
    void theClassifierSendsTheTenantsOverrideAndTheShippedTextWithout() {
        PromptSlot slot = new PromptSlot();
        slot.use(resolver);
        List<WorkflowAiModel.Request> seen = new ArrayList<>();
        AiClassifierHandler handler = new AiClassifierHandler(provider(req -> {
            seen.add(req);
            return "{\"class\":\"billing\",\"confidence\":0.9}";
        }), M, true, 12_000, 0.5);
        handler.usePrompts(slot);

        handler.execute(classifierNode(), "I was charged twice");
        assertEquals(WorkflowAiNodeHandler.CLASSIFIER_SYSTEM, seen.get(0).system());

        tenantOverride(WorkflowAiNodeHandler.CLASSIFIER_KEY, "Classify strictly.");
        handler.execute(classifierNode(), "I was charged twice");
        assertEquals("Classify strictly.", seen.get(1).system());
    }

    @Test
    void theExtractorRepairRequestIsOverridable() {
        PromptSlot slot = new PromptSlot();
        slot.use(resolver);
        tenantOverride(WorkflowAiNodeHandler.EXTRACTOR_REPAIR_KEY, "\n\nFix: {issues}");
        List<WorkflowAiModel.Request> seen = new ArrayList<>();
        String[] replies = {"{\"amount\":\"lots\",\"who\":\"Asha\"}", "{\"amount\":9,\"who\":\"Asha\"}"};
        int[] n = {0};
        AiExtractorHandler handler = new AiExtractorHandler(provider(req -> {
            seen.add(req);
            return replies[Math.min(n[0]++, replies.length - 1)];
        }), M, true, 12_000);
        handler.usePrompts(slot);
        Map<String, Object> amount = new HashMap<>(Map.of("name", "amount", "type", "number", "description", "d", "required", true));
        Map<String, Object> who = new HashMap<>(Map.of("name", "who", "type", "string", "description", "d", "required", true));
        handler.execute(new WorkflowNodeDto("n1", "parameter_extractor", Map.of(),
            Map.of("config", Map.of("parameters", List.of(amount, who)))), "text");

        assertEquals(2, seen.size());
        assertTrue(seen.get(1).user().endsWith("\n\nFix: amount: expected a number"), seen.get(1).user());
        assertEquals(WorkflowAiNodeHandler.EXTRACTOR_SYSTEM.length() > 0, !seen.get(0).system().isEmpty());
    }
}
