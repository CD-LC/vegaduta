package com.agenticai.core.guardrails.procedures;

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

    /** Empty string when the agent has no active procedures - callers append unconditionally. */
    public static String render(List<ProcedureService.ResolvedProcedure> procedures) {
        if (procedures == null || procedures.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n## Operating procedures\n")
            .append("This workspace requires you to follow the procedures below. Treat them as binding ")
            .append("business rules, not suggestions: they sit above your general instructions. Rules ")
            .append("marked [enforced: ...] are checked mechanically by the platform - calls that violate ")
            .append("them are blocked before they run.\n");
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
                String marker = enforcementMarker(rule);
                if (marker != null) {
                    sb.append(" [enforced: ").append(marker).append(']');
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private static String enforcementMarker(ProcedureRule rule) {
        if (rule.getGuardKind() == null || rule.getGuardTool() == null) {
            return null;
        }
        return switch (rule.getGuardKind()) {
            case FORBID_TOOL -> "tool '" + rule.getGuardTool() + "' is blocked";
            case REQUIRE_APPROVAL -> "tool '" + rule.getGuardTool() + "' requires human approval";
            case REQUIRE_TOOL_BEFORE -> "tool '" + rule.getGuardTool() + "' only after its prerequisite tool";
        };
    }

    /** An instruction is one list item; a newline inside it must not be able to start a fake new section. */
    private static String singleLine(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }
}
