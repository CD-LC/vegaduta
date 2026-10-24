package com.agenticai.core.guardrails;

import com.agenticai.core.prompts.PromptContext;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.ResolvedPrompt;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The guardrail module's way to read model-facing text from the prompt registry ({@code docs/prompts.md}):
 * the keys it owns, and one lookup that never throws and never returns nothing.
 *
 * <p>A guardrail text may not be switched off (none of these keys is {@code disableable}), so a lookup
 * that yields no text can only mean the resolver is missing or broken. Then the shipped default is used;
 * a refusal must never turn into an empty tool result.
 */
public final class GuardrailPrompts {

    private static final Logger log = LoggerFactory.getLogger(GuardrailPrompts.class);

    // tool results the model reads
    public static final String DISPATCH_DENIED = "toolresult.guard.dispatch_denied";
    public static final String DISPATCH_DENIED_DEFAULT_REASON = "toolresult.guard.dispatch_denied.default_reason";
    public static final String DISPATCH_CHECK_FAILED = "toolresult.guard.dispatch_check_failed";
    public static final String VERIFIER_REJECTED = "toolresult.guard.verifier_rejected";
    public static final String VERIFIER_DEFAULT_REASON = "toolresult.guard.verifier_default_reason";
    public static final String ARG_POLICY_VIOLATION = "toolresult.guard.arg_policy_violation";
    public static final String PROCEDURE_BLOCKED = "toolresult.guard.procedure_blocked";
    public static final String OUTPUT_WITHHELD = "toolresult.guard.output_withheld";
    public static final String TENANT_RULE_MATCHED = "toolresult.guard.tenant_rule_matched";
    public static final String PATTERN_INJECTION_MARKER = "toolresult.guard.pattern_injection_marker";
    public static final String SEMANTIC_DEFAULT_REASON = "toolresult.guard.semantic_default_reason";
    public static final String SEMANTIC_UNAVAILABLE = "toolresult.guard.semantic_unavailable";
    public static final String PARK_AWAITING = "toolresult.park.awaiting";
    public static final String PARK_QUEUED = "toolresult.park.queued";
    public static final String PARK_QUEUE_FULL = "toolresult.park.queue_full";
    public static final String PARK_RECORD_FAILED = "toolresult.park.record_failed";
    public static final String TOOL_BUDGET_CALLS = "toolresult.tool_budget.calls";
    public static final String EVAL_SANDBOX_SIMULATED = "toolresult.eval_sandbox.simulated";

    // procedures block
    public static final String PROCEDURES_HEADER = "procedures.header";
    public static final String PROCEDURE_MARKER_FORBID_TOOL = "procedures.marker.forbid_tool";
    public static final String PROCEDURE_MARKER_REQUIRE_APPROVAL = "procedures.marker.require_approval";
    public static final String PROCEDURE_MARKER_REQUIRE_TOOL_BEFORE = "procedures.marker.require_tool_before";

    // judges
    public static final String JUDGE_TOOL_VERIFIER = "judge.tool_verifier";
    public static final String JUDGE_TOOL_VERIFIER_CONTEXT = "judge.tool_verifier.context";
    public static final String JUDGE_INJECTION = "judge.injection";
    public static final String JUDGE_POLICY_PACK = "judge.policy_pack";
    public static final String JUDGE_POLICY_PACK_CATEGORY_PREFIX = "judge.policy_pack.";
    public static final String JUDGE_TOXICITY = "judge.toxicity";

    // refusals the user reads
    public static final String REFUSAL_JAILBREAK = "refusal.guardrail.jailbreak";
    public static final String REFUSAL_CHILD_SAFETY = "refusal.guardrail.child_safety";
    public static final String REFUSAL_SELF_HARM = "refusal.guardrail.self_harm";
    public static final String REFUSAL_VIOLENCE = "refusal.guardrail.violence";
    public static final String REFUSAL_ILLEGAL_ACTIVITY = "refusal.guardrail.illegal_activity";
    public static final String REFUSAL_HATE_SPEECH = "refusal.guardrail.hate_speech";
    public static final String REFUSAL_TOXICITY = "refusal.guardrail.toxicity";
    public static final String REFUSAL_TOXICITY_UNVERIFIED = "refusal.guardrail.toxicity_unverified";

    private GuardrailPrompts() {}

    /**
     * The text of {@code key} for {@code agentId} (may be null) with the placeholders filled from
     * {@code nameValuePairs} ({@code "tool", toolName, "reason", why}); a null value reads as "null", as
     * string concatenation did. Never null.
     */
    public static String text(PromptResolver resolver, String key, UUID agentId, String... nameValuePairs) {
        PromptContext context = PromptContext.forAgent(agentId);
        for (int i = 0; i + 1 < nameValuePairs.length; i += 2) {
            context = context.withVariable(nameValuePairs[i], String.valueOf(nameValuePairs[i + 1]));
        }
        String text = null;
        try {
            text = (resolver != null ? resolver : PromptResolver.defaultsOnly()).resolve(key, context).textOrNull();
        } catch (RuntimeException e) {
            log.warn("Prompt '{}' could not be resolved; using the shipped text: {}", key, e.toString());
        }
        if (text == null) {
            text = PromptResolver.defaultsOnly().resolve(key, context).textOrNull();
        }
        return text != null ? text : "[" + key + "]";
    }

    /** As {@link #text} for the agent the current thread's {@link GuardrailPromptScope} names. */
    public static String scopedText(PromptResolver resolver, String key, String... nameValuePairs) {
        return text(resolver, key, GuardrailPromptScope.currentAgent(), nameValuePairs);
    }

    /**
     * The text of {@code key} for a setting an operator may still change in configuration (the
     * {@code app.guardrails.*} property that existed before the key did).
     *
     * <p>Order: a registry override at any scope wins; otherwise the property when it differs from the shipped
     * wording (an operator edited it); otherwise the shipped wording. With nothing customised the result is the
     * shipped default, byte for byte. The property text is a template with the same placeholders.
     *
     * @param configured the property's current value (null or blank: not set)
     */
    public static String textWithConfiguredAlias(
            PromptResolver resolver, String key, UUID agentId, String configured, String... nameValuePairs) {
        PromptContext context = PromptContext.forAgent(agentId);
        for (int i = 0; i + 1 < nameValuePairs.length; i += 2) {
            context = context.withVariable(nameValuePairs[i], String.valueOf(nameValuePairs[i + 1]));
        }
        PromptResolver r = resolver != null ? resolver : PromptResolver.defaultsOnly();
        try {
            ResolvedPrompt resolved = r.resolve(key, context);
            boolean overridden = resolved.known() && resolved.scope() != null;
            String shipped = r.catalog().defaultText(key, null).orElse(null);
            if (!overridden && configured != null && !configured.isBlank() && !configured.equals(shipped)) {
                return render(configured, nameValuePairs);
            }
            String text = resolved.textOrNull();
            if (text != null) {
                return text;
            }
        } catch (RuntimeException e) {
            log.warn("Prompt '{}' could not be resolved; using the shipped text: {}", key, e.toString());
        }
        return text(null, key, agentId, nameValuePairs);
    }

    /**
     * The structured {@code tool_budget_exhausted} result for a call past the turn's tool-call ceiling: the
     * registry's wording ({@code toolresult.tool_budget.calls}), or {@code app.guardrails.step-limit.exhausted-message}
     * when an operator changed it (see {@link #textWithConfiguredAlias}).
     */
    public static String budgetCallsResult(PromptResolver resolver, UUID agentId, ToolStepLimitProperties limits, int ceiling) {
        return ToolBudgetExhausted.result(textWithConfiguredAlias(
            resolver, TOOL_BUDGET_CALLS, agentId, limits == null ? null : limits.getExhaustedMessage(),
            "limit", String.valueOf(ceiling)));
    }

    /** Replaces {@code {name}} for each pair; the same single-pass rule the registry uses. */
    static String render(String template, String... nameValuePairs) {
        java.util.Map<String, String> values = new java.util.HashMap<>();
        for (int i = 0; i + 1 < nameValuePairs.length; i += 2) {
            values.put(nameValuePairs[i], String.valueOf(nameValuePairs[i + 1]));
        }
        return com.agenticai.core.prompts.PromptTemplates.render(template, values.keySet(), values);
    }
}
