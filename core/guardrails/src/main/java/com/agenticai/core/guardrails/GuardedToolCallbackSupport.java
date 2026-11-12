package com.agenticai.core.guardrails;

import com.agenticai.core.approval.ToolCallOrigin;
import com.agenticai.core.audit.IssueSeverity;
import com.agenticai.core.audit.IssueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;

/**
 * The end-of-call helpers of {@link GuardedToolCallback} - read-only auto-allow audit, the
 * verifier's conversation tail, the dispatch gate, tenant rules and the false-positive
 * record-and-explain path - extracted so the callback stays under the file-size cap.
 * Package-private; callers still go through {@link GuardedToolCallback}.
 */
final class GuardedToolCallbackSupport {

    private static final Logger log = LoggerFactory.getLogger(GuardedToolCallbackSupport.class);

    private final GuardedToolCallback owner;

    GuardedToolCallbackSupport(GuardedToolCallback owner) {
        this.owner = owner;
    }

    void recordReadOnlyAutoAllowed(String toolName, String toolInput) {
        log.info(
                "Agent {} (session {}): '{}' {}",
                owner.agentId,
                owner.sessionId,
                toolName,
                ReadOnlyAutoAllow.NOTE);
        try {
            owner.turnApprovalState().markAutoAllowedReadOnly(toolName, toolInput);
        } catch (RuntimeException e) {
            log.debug(
                    "Could not note the auto-allowed read-only call '{}' on the turn", toolName, e);
        }
        owner.metrics.readOnlyAutoAllowed();
        ReadOnlyAutoAllow.Audit audit = owner.readOnlyAutoAllowAudit;
        if (audit != null) {
            try {
                audit.autoAllowed(toolName, owner.agentId, owner.sessionId, ReadOnlyAutoAllow.NOTE);
            } catch (RuntimeException e) {
                log.warn("Could not audit the auto-allowed read-only call '{}'", toolName, e);
            }
        }
    }

    /**
     * The conversation tail for the verifier: only when a source is wired and the turn has a scoped
     * memory id.
     */
    ToolCallVerifierService.ConversationTail verifierConversationTail() {
        ConversationTailSource source = owner.conversationTail;
        String memorySessionId = owner.turnApprovalState().memorySessionId();
        if (source == null || memorySessionId == null) {
            return null;
        }
        return (maxMessages, maxChars) -> source.tail(memorySessionId, maxMessages, maxChars);
    }

    /**
     * No gate configured allows the call. A gate that throws follows {@link GuardrailFailMode}:
     * refused when closed (the default), allowed when open - a policy lookup failure must not be a
     * way to switch the policy off.
     */
    ToolDispatchGate.Verdict checkDispatchGate(String toolName) {
        if (owner.dispatchGate == null) {
            return ToolDispatchGate.Verdict.allow();
        }
        try {
            ToolDispatchGate.Verdict verdict = owner.dispatchGate.check(toolName);
            return verdict != null ? verdict : ToolDispatchGate.Verdict.allow();
        } catch (RuntimeException e) {
            if (GuardrailSettings.failClosed(owner.agentId)) {
                log.warn(
                        "Tool dispatch gate failed for tool '{}' - refusing the call (fail-closed)",
                        toolName,
                        e);
                return ToolDispatchGate.Verdict.deny(
                        owner.text(GuardrailPrompts.DISPATCH_CHECK_FAILED));
            }
            log.warn(
                    "Tool dispatch gate failed for tool '{}' - allowing the call (fail-open)",
                    toolName,
                    e);
            return ToolDispatchGate.Verdict.allow();
        }
    }

    /**
     * Applies this tenant's own {@link GuardrailRule} rows to a tool result.
     *
     * <p>A BLOCK match is raised as a {@link GuardrailViolationException} so it lands in exactly
     * the same soft-stop path as a compiled-in pattern match (withheld from the model, recorded as
     * a platform issue, the turn continues); a REDACT match returns the rewritten text. Anything
     * that goes wrong inside the evaluator already fails open there, so this method has no error
     * handling of its own beyond the null-evaluator case.
     */
    String applyTenantRules(String toolName, String sanitized) {
        if (owner.tenantRuleEvaluator == null || sanitized == null || sanitized.isEmpty()) {
            return sanitized;
        }
        TenantGuardrailRuleEvaluator.Outcome outcome =
                owner.tenantRuleEvaluator.apply(sanitized, true);
        if (outcome.blocked()) {
            log.warn(
                    "Tenant guardrail rule '{}' blocked output from tool '{}'",
                    outcome.blockedByRuleName(),
                    toolName);
            throw new GuardrailViolationException(
                    toolName,
                    owner.text(
                            GuardrailPrompts.TENANT_RULE_MATCHED,
                            "rule",
                            outcome.blockedByRuleName()));
        }
        return outcome.text();
    }

    /**
     * A {@link GuardrailViolationException} is a soft stop, same posture as every other stop
     * condition in {@link #call} except the step-limit (which is a deliberate runaway-loop
     * hard-stop, see this class's own javadoc): the flagged output is still withheld from the model
     * either way, but a false positive now costs one tool result instead of the whole turn.
     * Best-effort recorded on the admin Issues portal so a recurring false positive on the same
     * tool is triaged like any other platform issue, rather than only ever appearing in application
     * logs.
     */
    String recordAndExplain(String toolName, GuardrailViolationException e) {
        if (owner.issueService != null) {
            try {
                owner.issueService.record(
                        IssueSource.GUARDRAIL_FALSE_POSITIVE,
                        toolName,
                        IssueSeverity.WARNING,
                        "Guardrail blocked output from tool '" + toolName + "'",
                        e.getMessage());
            } catch (RuntimeException recordingFailure) {
                log.warn(
                        "Failed to record platform issue for guardrail violation on tool '{}'",
                        toolName,
                        recordingFailure);
            }
        }
        return owner.text(
                GuardrailPrompts.OUTPUT_WITHHELD, "tool", toolName, "reason", e.getMessage());
    }

    /**
     * Where this turn's tool calls come from, for a tool that records its own approval: the goal
     * and conversation identity when the turn knows them, otherwise only the scoped history id
     * (such a request is recorded but its task is not continued), or {@code null} when the turn
     * knows nothing.
     */
    ToolCallOrigin callOrigin() {
        TurnApprovalState state = owner.turnApprovalState();
        ApprovalParkProperties texts =
                owner.parkTexts != null ? owner.parkTexts : GuardedToolCallback.DEFAULT_PARK_TEXTS;
        ToolCallOrigin origin = state.origin(texts.getMaxGoalChars(), texts.getGoalCutMarker());
        if (origin != null) {
            return origin;
        }
        return state.memorySessionId() == null
                ? null
                : ToolCallOrigin.ofMemorySession(state.memorySessionId());
    }

    ApprovalParking parking() {
        ApprovalParkProperties texts = owner.parkTexts;
        return new ApprovalParking(
                owner.delegate,
                owner.approvalGateService,
                owner.agentId,
                owner.sessionId,
                owner.turnApprovalState(),
                texts != null ? texts : new ApprovalParkProperties(),
                owner.prompts);
    }

    String timedCall(String toolInput, ToolContext toolContext) {
        String toolName = owner.delegate.getToolDefinition().name();
        long startedAt = System.nanoTime();
        String[] outcome = {GuardedToolCallback.OUTCOME_SUCCESS};
        try {
            return ToolResultMarker.markGuardOutcome(
                    guardedCall(toolName, toolInput, toolContext, outcome),
                    outcome[0],
                    owner.turnApprovalState());
        } catch (ToolStepLimitExceededException e) {
            outcome[0] = GuardedToolCallback.OUTCOME_STEP_LIMIT;
            throw e;
        } catch (RuntimeException e) {
            outcome[0] = GuardedToolCallback.OUTCOME_ERROR;
            owner.metrics.recordError(toolName, e);
            throw e;
        } finally {
            owner.metrics.recordTiming(toolName, outcome[0], System.nanoTime() - startedAt);
        }
    }

    ToolOutputProperties outputLimits() {
        ToolOutputProperties configured = owner.outputLimits;
        return configured != null ? configured : GuardedToolCallback.DEFAULT_OUTPUT_LIMITS;
    }

    ToolStepLimitProperties stepLimits() {
        ToolStepLimitProperties configured = owner.stepLimits;
        return configured != null ? configured : GuardedToolCallback.DEFAULT_STEP_LIMITS;
    }

    boolean queueRecords(String toolName) {
        return !owner.queueExcludedTools.contains(toolName);
    }

    String guardedCall(
            String toolName, String toolInput, ToolContext toolContext, String[] outcome) {
        // inDelegate[0] is true only while the raw tool executes: an exception
        // thrown then is the TOOL's own failure and passes through unwrapped.
        // A RuntimeException from any surrounding check (approval-policy read,
        // procedure guard, verifier, output cap/sanitization) is platform
        // machinery failing around a tool that never ran - tagged
        // ToolInfrastructureException so LoggingToolCallback's per-tool circuit
        // breaker is billed only for failures the tool actually owns (one shared
        // outage must not trip the breakers of every healthy tool).
        boolean[] inDelegate = {false};
        try {
            return owner.guardedCallBody(toolName, toolInput, toolContext, outcome, inDelegate);
        } catch (RuntimeException e) {
            if (inDelegate[0]
                    || e instanceof ToolStepLimitExceededException
                    || e instanceof GuardrailViolationException
                    || e instanceof ToolInfrastructureException) {
                throw e;
            }
            throw new ToolInfrastructureException(toolName, e);
        }
    }
}
