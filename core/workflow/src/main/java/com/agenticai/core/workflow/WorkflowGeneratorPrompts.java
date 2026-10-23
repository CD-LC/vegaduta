package com.agenticai.core.workflow;

import com.agenticai.core.prompts.PromptSlot;
import com.agenticai.core.workflow.expression.BuiltIn;
import com.agenticai.core.workflow.expression.BuiltIns;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The system prompts of {@link WorkflowGraphGeneratorService}: the graph-shape contract (node types and their
 * config), the expression-language section built from the live {@link BuiltIns} registry, and the reference-mode
 * addendum the Brief planner uses. The wording of each is a prompt-registry key ({@code
 * prompts/en/workflow.generator.*.md}); this class only picks which pieces apply and fills their placeholders, so the
 * prompt contract stays unit-testable on its own and an owner can reword it.
 */
final class WorkflowGeneratorPrompts {

    static final String SYSTEM_KEY = "workflow.generator.system";
    static final String CONDITION_EXPRESSION_HINT_KEY = "workflow.generator.condition_expression_hint";
    static final String PER_ITEM_SEND_HINT_KEY = "workflow.generator.per_item_send_hint";
    static final String EXPRESSION_SECTION_KEY = "workflow.generator.expression_section";
    static final String REF_MODE_KEY = "workflow.generator.ref_mode";

    private WorkflowGeneratorPrompts() {
    }

    static String refModeAddendum(String knowledgeList) {
        return refModeAddendum(knowledgeList, new PromptSlot(), null);
    }

    static String refModeAddendum(String knowledgeList, PromptSlot prompts, UUID agentId) {
        return prompts.text(REF_MODE_KEY, agentId, Map.of("knowledge_list", knowledgeList));
    }

    /**
     * Prompt block teaching the model the expression language. The wording is the registry's; the function list is
     * built from the live {@link BuiltIns} registry (names only, grouped by category) so it cannot drift from what
     * the engine accepts.
     */
    static String expressionSection() {
        return expressionSection(new PromptSlot(), null);
    }

    static String expressionSection(PromptSlot prompts, UUID agentId) {
        Map<String, List<String>> byCategory = new LinkedHashMap<>();
        for (BuiltIn fn : BuiltIns.registry().values()) {
            byCategory.computeIfAbsent(fn.category(), k -> new ArrayList<>()).add(fn.name());
        }
        String functions = byCategory.entrySet().stream()
            .map(e -> "  " + e.getKey() + ": " + String.join(", ", e.getValue()))
            .collect(Collectors.joining("\n"));
        return prompts.text(EXPRESSION_SECTION_KEY, agentId, Map.of("functions", functions));
    }

    static String buildSystemPrompt(String agentList, boolean expressionsEnabled) {
        return buildSystemPrompt(agentList, expressionsEnabled, new PromptSlot(), null);
    }

    static String buildSystemPrompt(String agentList, boolean expressionsEnabled, PromptSlot prompts, UUID agentId) {
        // The hints are taught only when the expression engine is on (destination fields are evaluated only then,
        // ExpressionFields: output fields have no legacy templating).
        String conditionExpressionHint = expressionsEnabled ? prompts.text(CONDITION_EXPRESSION_HINT_KEY, agentId) : "";
        String expressionBlock = expressionsEnabled ? expressionSection(prompts, agentId) : "";
        String perItemSendHint = expressionsEnabled ? prompts.text(PER_ITEM_SEND_HINT_KEY, agentId) : "";
        return prompts.text(
            SYSTEM_KEY,
            agentId,
            Map.of(
                "agent_list", agentList,
                "condition_expression_hint", conditionExpressionHint,
                "per_item_send_hint", perItemSendHint,
                "expression_block", expressionBlock));
    }
}
