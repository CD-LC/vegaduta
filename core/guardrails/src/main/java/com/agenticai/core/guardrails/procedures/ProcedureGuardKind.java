package com.agenticai.core.guardrails.procedures;

/**
 * The closed set of machine-checkable enforcement clauses a {@link
 * ProcedureRule} can carry - the "deterministic" half of the procedures
 * layer (the instruction text itself is the natural-language half, injected
 * into the prompt). Deliberately small: every kind is evaluated by {@link
 * ProcedureGuardEvaluator} inside {@code GuardedToolCallback#call} with zero
 * LLM calls, same posture as {@code ToolCallOutputValidator}.
 *
 * <ul>
 *   <li>{@code FORBID_TOOL} - {@code guardTool} may never be called by an
 *       agent this procedure is attached to. The call is soft-stopped with
 *       an explanation returned to the model.</li>
 *   <li>{@code REQUIRE_APPROVAL} - {@code guardTool} is forced through the
 *       existing human-approval gate ({@code "mcp_tool." + toolName}) even
 *       when {@code ApprovalPolicyService} would not gate it - the rule is
 *       an additional, stricter override; it can never loosen policy.</li>
 *   <li>{@code REQUIRE_TOOL_BEFORE} - {@code guardTool} may only run after
 *       the tool named by {@code guardConfig.prior} has already been called
 *       in the same chat session ("verify_identity before process_refund").
 *       Session-scoped ordering is the strongest guarantee the tool-call
 *       seam can give deterministically - see {@link
 *       ProcedureGuardEvaluator}'s javadoc.</li>
 * </ul>
 */
public enum ProcedureGuardKind {
    FORBID_TOOL,
    REQUIRE_APPROVAL,
    REQUIRE_TOOL_BEFORE
}
