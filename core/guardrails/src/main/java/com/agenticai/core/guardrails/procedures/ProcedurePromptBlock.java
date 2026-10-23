package com.agenticai.core.guardrails.procedures;

import com.agenticai.core.guardrails.GuardrailPrompts;
import com.agenticai.core.prompts.PromptContext;
import com.agenticai.core.prompts.PromptResolver;
import java.util.List;

/**
 * Level-1 prompt surface for the procedures layer: the block appended to an
 * agent's system prompt listing every attached ACTIVE procedure's rules as
 * numbered instructions. Unlike {@code SkillPromptBlock} (name+description
 * only, body behind a tool call) a procedure's instructions are inline -
 * business rules the agent must follow on EVERY turn can't sit behind a
 * load step the model might skip.
 *
 * <p>Pure function of its input and order-stable (procedures arrive
 * position-ordered, rules rule_order-ordered), so the block is
 * byte-identical turn to turn and stays inside the provider's cacheable
 * prompt prefix - the same invariant {@code SkillPromptBlock} documents.
 *
 * <p>Rules carrying a guard clause get an explicit {@code [enforced: ...]}
 * marker so the model knows the platform will stop it mechanically, not
 * merely expects compliance - surfacing the deterministic half honestly in
 * the natural-language half.
 */
public final class ProcedurePromptBlock {

    /** Backstop so a pathological attachment set can never crowd out the real prompt. */
    public static final int MAX_RULES_IN_PROMPT = 100;

    private ProcedurePromptBlock() {
    }

    /**
     * Empty string when the agent has no active procedures - callers append unconditionally. The shipped wording
     * (no registry overrides); see {@link #render(List, PromptResolver, PromptContext)}.
     */
    public static String render(List<ProcedureService.ResolvedProcedure> procedures) {
        return render(procedures, PromptResolver.defaultsOnly(), PromptContext.none());
    }

    /**
     * As {@link #render(List)}, with the wording from the prompt registry ({@code procedures.header}, {@code
     * procedures.marker.*}) resolved for {@code context}, so a tenant's or an agent's override applies. The layout
     * (the "Operating procedures" heading, numbering, [enforced: ...] brackets) is code.
     */
    public static String render(
            List<ProcedureService.ResolvedProcedure> procedures, PromptResolver prompts, PromptContext context) {
        if (procedures == null || procedures.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n## Operating procedures\n")
            .append(GuardrailPrompts.text(prompts, GuardrailPrompts.PROCEDURES_HEADER, context.agentId()))
            .append('\n');
        int emitted = 0;
        for (ProcedureService.ResolvedProcedure resolved : procedures) {
            if (emitted >= MAX_RULES_IN_PROMPT) {
                break;
            }
            sb.append("\n### ").append(singleLine(resolved.procedure().getName())).append('\n');
            if (resolved.procedure().getDescription() != null
                && !resolved.procedure().getDescription().isBlank()) {
                sb.append(singleLine(resolved.procedure().getDescription())).append('\n');
            }
            int n = 0;
            for (ProcedureRule rule : resolved.rules()) {
                if (emitted >= MAX_RULES_IN_PROMPT) {
                    break;
                }
                n++;
                emitted++;
                sb.append(n).append(". ").append(singleLine(rule.getInstruction()));
                String marker = enforcementMarker(rule, prompts, context);
                if (marker != null) {
                    sb.append(" [enforced: ").append(marker).append(']');
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private static String enforcementMarker(ProcedureRule rule, PromptResolver prompts, PromptContext context) {
        if (rule.getGuardKind() == null || rule.getGuardTool() == null) {
            return null;
        }
        String key = switch (rule.getGuardKind()) {
            case FORBID_TOOL -> GuardrailPrompts.PROCEDURE_MARKER_FORBID_TOOL;
            case REQUIRE_APPROVAL -> GuardrailPrompts.PROCEDURE_MARKER_REQUIRE_APPROVAL;
            case REQUIRE_TOOL_BEFORE -> GuardrailPrompts.PROCEDURE_MARKER_REQUIRE_TOOL_BEFORE;
        };
        return GuardrailPrompts.text(prompts, key, context.agentId(), "tool", rule.getGuardTool());
    }

    /** An instruction is one list item; a newline inside it must not be able to start a fake new section. */
    private static String singleLine(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }
}
