package com.agenticai.core.guardrails.toolargs;

import com.agenticai.core.guardrails.GuardrailPrompts;
import com.agenticai.core.guardrails.GuardrailSettings;
import com.agenticai.core.guardrails.ToolStepLimitExceededException;
import com.agenticai.core.guardrails.ToolStepLimitProperties;
import com.agenticai.core.prompts.PromptResolver;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Applies an agent's per-tool argument policies (see {@link ToolArgPolicyEvaluator}) in front of a tool call.
 *
 * <p>Placed where the guard chain is composed ({@code AgentToolGuard#guardAndLog}): it wraps {@code
 * GuardedToolCallback} and sits inside {@code LoggingToolCallback}, so a refused call never reaches the
 * approval gate (nothing is queued for a human for a call that is not even well-formed), the refusal is
 * recorded in the tool-call log like any other result, and the step-event trace shows exactly what the model
 * was told. It is a decorator in the same style as {@code GuardedToolCallback}; it never edits arguments.
 *
 * <p><b>A refusal is a tool RESULT, not an exception</b>, phrased like the other "not executed" results
 * ({@code This tool call ('x') was not executed: ...}) so {@code LoggingToolCallback} does not journal it as
 * an executed effect and the model can read the reason, fix the arguments and retry. To keep a model from
 * retrying forever, a refusal still spends one step of the turn's shared step budget when the wrapper was
 * given the counter, and the ceiling is then enforced exactly as {@code GuardedToolCallback} does.
 *
 * <p><b>Failure posture.</b> A policy LOOKUP that throws follows the global guardrail fail mode ({@code
 * app.guardrails.fail-mode}, {@link GuardrailSettings#failClosed(UUID)}) exactly as {@code
 * GuardedToolCallback}'s dispatch gate does: refused when closed (the default), logged and allowed when open -
 * a failing policy store must not be a way to switch an agent's argument policies off. An evaluation that
 * throws on a matching policy is refused in both modes: the call could not be verified.
 */
public final class ToolArgPolicyCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(ToolArgPolicyCallback.class);

    /** The rule name of the refusal made when the policy lookup itself failed (as opposed to a rule the arguments broke). */
    static final String LOOKUP_FAILED_RULE = "lookup";

    private final ToolCallback delegate;
    private final ToolArgPolicySource source;
    private final UUID agentId;
    private final String sessionId;
    private final AtomicInteger stepCounter;
    private final int maxSteps;

    /**
     * @param stepCounter the turn's shared step counter, or null to not count refusals
     * @param maxSteps the turn's step ceiling (ignored when {@code stepCounter} is null)
     */
    public ToolArgPolicyCallback(
        ToolCallback delegate, ToolArgPolicySource source, UUID agentId, String sessionId,
        AtomicInteger stepCounter, int maxSteps
    ) {
        this.delegate = delegate;
        this.source = source;
        this.agentId = agentId;
        this.sessionId = sessionId;
        this.stepCounter = stepCounter;
        this.maxSteps = maxSteps;
    }

    /** Where the refusal text comes from (the prompt registry); the shipped wording unless set. */
    private volatile PromptResolver prompts = PromptResolver.defaultsOnly();

    /** Uses the prompt registry for the refusal text; null keeps the shipped wording. */
    public ToolArgPolicyCallback withPrompts(PromptResolver resolver) {
        if (resolver != null) {
            this.prompts = resolver;
        }
        return this;
    }

    /** What happens past the ceiling ({@code app.guardrails.step-limit.*}); the module defaults unless set. */
    private volatile ToolStepLimitProperties stepLimits = new ToolStepLimitProperties();

    /** Uses the operator's step-limit settings; null keeps the module defaults. */
    public ToolArgPolicyCallback withStepLimits(ToolStepLimitProperties limits) {
        if (limits != null) {
            this.stepLimits = limits;
        }
        return this;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        return guarded(toolInput, null);
    }

    /** Same check; Spring AI's {@link ToolContext} is forwarded to the wrapped tool instead of dropped. */
    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return guarded(toolInput, toolContext);
    }

    private String guarded(String toolInput, ToolContext toolContext) {
        String toolName = delegate.getToolDefinition().name();
        ToolArgPolicyEvaluator.Result verdict = evaluate(toolName, toolInput);
        if (verdict != null && !verdict.allowed()) {
            String reason = verdict.reason();
            report(toolName, reason);
            if (stepCounter != null) {
                int step = stepCounter.incrementAndGet();
                ToolStepLimitProperties.Stage stage = stepLimits.stageOf(step, maxSteps);
                if (stage == ToolStepLimitProperties.Stage.ABORT) {
                    throw new ToolStepLimitExceededException(agentId, sessionId, maxSteps);
                }
                if (stage == ToolStepLimitProperties.Stage.EXHAUSTED) {
                    // Over the turn's ceiling: the model is told to answer now, as for any other call over it.
                    return GuardrailPrompts.budgetCallsResult(prompts, agentId, stepLimits, maxSteps);
                }
            }
            boolean lookupFailed = verdict.violations().stream().anyMatch(v -> LOOKUP_FAILED_RULE.equals(v.rule()));
            String refusal = lookupFailed
                ? GuardrailPrompts.text(prompts, GuardrailPrompts.DISPATCH_DENIED, agentId, "tool", toolName, "reason", reason)
                : GuardrailPrompts.text(prompts, GuardrailPrompts.ARG_POLICY_VIOLATION, agentId, "tool", toolName, "reason", reason);
            return com.agenticai.core.guardrails.ToolResultMarker.mark(
                refusal,
                com.agenticai.core.guardrails.ToolResultMarker.Kind.BLOCKED);
        }
        return toolContext != null ? delegate.call(toolInput, toolContext) : delegate.call(toolInput);
    }

    /** Null = no policy applies (or the lookup failed and the fail mode is open). */
    private ToolArgPolicyEvaluator.Result evaluate(String toolName, String toolInput) {
        ToolArgPolicyEvaluator evaluator;
        try {
            evaluator = source.evaluatorFor(agentId);
        } catch (RuntimeException e) {
            if (GuardrailSettings.failClosed(agentId)) {
                log.warn("Tool argument policy lookup failed for agent {} - refusing '{}' (fail-closed)",
                    agentId, toolName, e);
                return new ToolArgPolicyEvaluator.Result(false, java.util.List.of(new ToolArgPolicyEvaluator.Violation(
                    toolName, "", LOOKUP_FAILED_RULE,
                    GuardrailPrompts.text(prompts, GuardrailPrompts.DISPATCH_CHECK_FAILED, agentId))));
            }
            log.warn("Tool argument policy lookup failed for agent {} - allowing '{}' (fail-open by app.guardrails.fail-mode)",
                agentId, toolName, e);
            return null;
        }
        if (evaluator == null || !evaluator.appliesTo(toolName)) {
            return null;
        }
        try {
            return evaluator.evaluate(toolName, toolInput);
        } catch (RuntimeException e) {
            log.warn("Tool argument policy evaluation failed for tool '{}' - refusing the call", toolName, e);
            return new ToolArgPolicyEvaluator.Result(false, java.util.List.of(new ToolArgPolicyEvaluator.Violation(
                toolName, "", "internal", "the arguments could not be verified against this agent's policy")));
        }
    }

    private void report(String toolName, String reason) {
        try {
            source.onViolation(agentId, sessionId, toolName, reason);
        } catch (RuntimeException e) {
            log.warn("Could not record the argument-policy refusal for tool '{}'", toolName, e);
        }
    }
}
