package com.agenticai.core.guardrails.toolargs;

import com.agenticai.core.guardrails.ToolStepLimitExceededException;
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
 * <p><b>Failure posture.</b> A policy LOOKUP that throws is logged and the call proceeds (the policy store
 * being briefly unavailable must not take every tool of the agent down - the same choice {@code
 * GuardedToolCallback} makes for its dispatch gate). An evaluation that throws on a matching policy is
 * refused: the call could not be verified.
 */
public final class ToolArgPolicyCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(ToolArgPolicyCallback.class);

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
            if (stepCounter != null && stepCounter.incrementAndGet() > maxSteps) {
                throw new ToolStepLimitExceededException(agentId, sessionId, maxSteps);
            }
            return "This tool call ('" + toolName + "') was not executed: its arguments broke this agent's argument "
                + "policy for the tool - " + reason + ". Fix the arguments and try again, or proceed without this tool.";
        }
        return toolContext != null ? delegate.call(toolInput, toolContext) : delegate.call(toolInput);
    }

    /** Null = no policy applies (or the lookup failed open). */
    private ToolArgPolicyEvaluator.Result evaluate(String toolName, String toolInput) {
        ToolArgPolicyEvaluator evaluator;
        try {
            evaluator = source.evaluatorFor(agentId);
        } catch (RuntimeException e) {
            log.warn("Tool argument policy lookup failed for agent {} - allowing '{}' (fail-open on lookup)",
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
