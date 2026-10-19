package com.agenticai.core.guardrails;

import com.agenticai.core.approval.ApprovalPolicyService;
import com.agenticai.core.approval.ApprovalReplayGrant;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.audit.IssueSeverity;
import com.agenticai.core.audit.IssueSource;
import com.agenticai.core.audit.PlatformIssueService;
import com.agenticai.core.guardrails.taint.TaintGate;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Wraps a raw tool callback with Autonomy/Guardrails Strategy Stage A's
 * per-turn enforcement points, in the same decorator style {@code
 * LoggingToolCallback} already established (this codebase wraps
 * {@code ToolCallback}s rather than modifying Spring AI's tool-execution
 * internals, since Spring AI's streaming {@code ChatClient.stream()} runs
 * its own tool-calling loop internally with no public step-count hook to
 * configure - a counting decorator shared across every tool in one turn is
 * the seam this codebase actually has):
 *
 * <ol>
 *   <li><b>Step ceiling</b> - {@code stepCounter} is intentionally a single
 *       {@link AtomicInteger} shared by every {@code GuardedToolCallback}
 *       built for one {@code AgentToolProvider.resolveToolsFor} call (one
 *       per chat turn) - the ceiling is "N tool calls total this turn," not
 *       "N calls to this specific tool."</li>
 *   <li><b>Per-tool HITL escalation</b> - before the delegate ever runs,
 *       checks {@link ApprovalPolicyService#requiresApproval} against a
 *       {@code "mcp_tool." + toolName} action type. Reuses the exact same
 *       policy store/admin API ({@code PUT /api/approvals/policy/**}) every
 *       other gated action already uses - a tenant-admin sets an override
 *       for {@code "mcp_tool.delete_file"} (say) the same way they'd gate
 *       {@code "channel.whatsapp.send"}, no new endpoint needed. If gated,
 *       the tool is <b>not executed at all</b> in this turn - it is
 *       recorded (with a replay envelope, see the 7-arg {@link
 *       HumanApprovalGateService#recordToolCallForApproval}) and actually
 *       RUNS later when a tenant-admin approves it, via {@code
 *       AgentToolCallApprovalExecutor} in {@code core/agents}. The one
 *       exception to the gate is a same-thread {@link ApprovalReplayGrant}
 *       - the single-use token that executor sets so its replay of an
 *       ALREADY-approved call doesn't re-gate into an infinite
 *       approve/record loop; see that class's javadoc for the exact
 *       semantics.</li>
 *   <li><b>Deterministic output validation (2026 Reliability Gap Analysis,
 *       task #815, Gap 1)</b> - {@link ToolCallOutputValidator#validate} runs
 *       BEFORE sanitization below: a length-bound and JSON-well-formedness
 *       check, zero LLM calls, on every single tool call. A failure never
 *       throws - it returns a marked synthetic message the model can react
 *       to, same soft-stop posture as everything else in this class; {@code
 *       LoggingToolCallback} strips the marker and flags {@code
 *       ToolCallLog.validationFailed} for it.</li>
 *   <li><b>Output sanitization</b> - {@link GuardrailService#sanitizeToolOutput}
 *       runs on whatever the delegate actually returned, same as before. A
 *       {@link GuardrailViolationException} is caught here and turned into
 *       the same soft-stop shape as everything else above (see {@code
 *       recordAndExplain}) - the flagged output is still withheld from the
 *       model either way, but a false positive now costs one tool result,
 *       not the whole turn, matching the step-limit exception being the
 *       ONLY intentional hard stop in this class.</li>
 *   <li><b>Independent verification (Agent Intelligence research doc, July
 *       2026)</b> - when the calling agent's {@code reasoningDepth} is
 *       {@code RIGOROUS}, {@link ToolCallVerifierService#verify} runs
 *       BEFORE the delegate executes (checked ahead of the approval-policy
 *       step above, since a call the verifier itself flags as not matching
 *       user intent shouldn't even reach a human's approval queue). A
 *       rejection is returned to the model as an explanatory tool result
 *       (same "let the model react to it" posture as a pending-approval
 *       result below), not thrown - see {@link ToolCallVerifierService}'s
 *       javadoc for why this is deliberately a soft stop, not a hard one.
 *       {@code FAST}/{@code CAREFUL} agents skip this entirely (verifier is
 *       null or {@code rigorous} is false) - zero behavior change, zero
 *       extra latency, for every agent that hasn't opted in.</li>
 * </ol>
 *
 * <p>Wrapping order in {@code AgentToolProvider}: this class wraps the raw
 * tool, and {@code LoggingToolCallback} wraps this class - so the persisted
 * tool-call log records the guardrail-sanitized output (what the model
 * actually saw), and a step-limit/guardrail-violation/approval-required
 * outcome is captured as a logged tool-call entry the same way any other
 * tool result is.
 *
 * <p><b>2026-09-28 additions.</b> An optional {@link ToolDispatchGate} (the
 * tool-classification registry, supplied by {@code core/agents}) is consulted
 * right after the step count: {@code DENY} refuses the call with a tool result,
 * {@code REQUIRE_APPROVAL} ORs into the approval gate exactly like a procedure's
 * REQUIRE_APPROVAL rule. An optional {@link MeterRegistry} records every call
 * as a {@value #CALL_TIMER} timer tagged with the tool name and outcome, plus
 * {@value #ERROR_COUNTER} for calls that threw. Both are {@code null}-safe:
 * callers that pass neither get exactly the previous behaviour. {@link
 * #call(String, ToolContext)} is overridden so Spring AI's {@link ToolContext}
 * reaches the wrapped tool instead of being dropped.
 */
public class GuardedToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(GuardedToolCallback.class);

    private static final String ACTION_TYPE_PREFIX = "mcp_tool.";

    /** Timer over every guarded call, tags {@code tool} and {@code outcome}. */
    public static final String CALL_TIMER = "agent.tool.calls";
    /** Counter of guarded calls whose delegate (or a check) threw, tags {@code tool} and {@code exception}. */
    public static final String ERROR_COUNTER = "agent.tool.errors";

    static final String OUTCOME_SUCCESS = "success";
    static final String OUTCOME_ERROR = "error";
    static final String OUTCOME_STEP_LIMIT = "step_limit";
    static final String OUTCOME_BLOCKED = "blocked";
    static final String OUTCOME_APPROVAL_PENDING = "approval_pending";
    static final String OUTCOME_VALIDATION_FAILED = "validation_failed";
    static final String OUTCOME_OUTPUT_WITHHELD = "output_withheld";

    /**
     * Turns that have parked a call for human approval, keyed by the turn's step counter (one per
     * turn, compared by identity). Once a call parks, nothing else runs in that turn: the model could
     * otherwise carry on with sibling or follow-up tool calls (often ones that depend on the parked
     * one) and finish the task before the human has decided.
     */
    private static final java.util.Set<AtomicInteger> PARKED_TURNS =
        java.util.Collections.newSetFromMap(java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>()));

    private final ToolCallback delegate;
    private final AtomicInteger stepCounter;
    private final int maxStepsPerTurn;
    private final GuardrailService guardrailService;
    private final ApprovalPolicyService approvalPolicyService;
    private final HumanApprovalGateService approvalGateService;
    private final UUID agentId;
    private final String sessionId;
    private final ToolCallVerifierService verifierService;
    private final boolean rigorousVerification;
    private final ChatClient verifierChatClient;
    private final String userMessage;
    private final ApprovalGateMode approvalGateMode;
    private final PlatformIssueService issueService;
    private final TenantGuardrailRuleEvaluator tenantRuleEvaluator;
    private final com.agenticai.core.guardrails.procedures.ProcedureGuardEvaluator procedureGuard;
    private final ToolDispatchGate dispatchGate;
    private final MeterRegistry meterRegistry;

    public GuardedToolCallback(
        ToolCallback delegate,
        AtomicInteger stepCounter,
        int maxStepsPerTurn,
        GuardrailService guardrailService,
        ApprovalPolicyService approvalPolicyService,
        HumanApprovalGateService approvalGateService,
        UUID agentId,
        String sessionId
    ) {
        this(delegate, stepCounter, maxStepsPerTurn, guardrailService, approvalPolicyService,
            approvalGateService, agentId, sessionId, null, false, null, null, ApprovalGateMode.POLICY_DEFAULT, null, null, null);
    }

    /**
     * @param verifierService nullable - a {@code CAREFUL}/no-op mode
     *     platform build (no {@code ToolCallVerifierService} bean) simply
     *     never verifies, same graceful-degradation posture as every other
     *     optional bean in this codebase.
     * @param rigorousVerification the calling agent's {@code reasoningDepth
     *     == RIGOROUS}, resolved once by {@code AgentToolProvider} per
     *     chat turn (not re-read per tool call).
     * @param verifierChatClient the calling turn's already-built
     *     {@code ChatClient} (same provider/model/credentials the main
     *     response uses) - null-safe, see {@link ToolCallVerifierService#verify}.
     * @param userMessage this turn's user message, for the verifier to
     *     judge relevance against.
     * @param approvalGateMode #815 Governance follow-up - resolved from the
     *     calling agent's {@code AgentAutonomyTier} by {@code
     *     AgentToolProvider#guardAndLog} (see {@link ApprovalGateMode}'s
     *     javadoc for why this class takes this narrower type rather than
     *     the tier enum itself). {@code POLICY_DEFAULT} is exactly today's
     *     behavior for every agent that hasn't been given a different tier.
     * @param issueService nullable - diagnostics build-out (2026-08): lets a
     *     soft-stopped {@link GuardrailViolationException} (see {@link
     *     #call}) be recorded on the admin Issues portal ({@code
     *     IssueSource#GUARDRAIL_FALSE_POSITIVE}) instead of only being logged
     *     and swallowed. A {@code null} provider (no {@code PlatformIssueService}
     *     bean, or a caller that hasn't been updated) just skips the record
     *     call, same graceful-degradation posture as {@code verifierService}.
     * @param tenantRuleEvaluator nullable - the tenant's own {@code
     *     guardrail_rule} rows, applied to the tool result AFTER the
     *     compiled-in {@link GuardrailService} pass so the platform's patterns
     *     stay the floor and a tenant rule can only ever add to them. Null
     *     (no bean, or a caller that hasn't been updated) means tenant rules
     *     simply don't run, exactly as they didn't before this parameter
     *     existed - see {@link TenantGuardrailRuleEvaluator}'s javadoc for why
     *     they had to start running at all.
     */
    public GuardedToolCallback(
        ToolCallback delegate,
        AtomicInteger stepCounter,
        int maxStepsPerTurn,
        GuardrailService guardrailService,
        ApprovalPolicyService approvalPolicyService,
        HumanApprovalGateService approvalGateService,
        UUID agentId,
        String sessionId,
        ToolCallVerifierService verifierService,
        boolean rigorousVerification,
        ChatClient verifierChatClient,
        String userMessage,
        ApprovalGateMode approvalGateMode,
        PlatformIssueService issueService,
        TenantGuardrailRuleEvaluator tenantRuleEvaluator,
        com.agenticai.core.guardrails.procedures.ProcedureGuardEvaluator procedureGuard
    ) {
        this(delegate, stepCounter, maxStepsPerTurn, guardrailService, approvalPolicyService, approvalGateService,
            agentId, sessionId, verifierService, rigorousVerification, verifierChatClient, userMessage,
            approvalGateMode, issueService, tenantRuleEvaluator, procedureGuard, null, null);
    }

    /**
     * @param dispatchGate nullable - see {@link ToolDispatchGate}; {@code null}
     *     means no pre-dispatch policy, exactly as before it existed.
     * @param meterRegistry nullable - {@code null} records no metrics.
     */
    public GuardedToolCallback(
        ToolCallback delegate,
        AtomicInteger stepCounter,
        int maxStepsPerTurn,
        GuardrailService guardrailService,
        ApprovalPolicyService approvalPolicyService,
        HumanApprovalGateService approvalGateService,
        UUID agentId,
        String sessionId,
        ToolCallVerifierService verifierService,
        boolean rigorousVerification,
        ChatClient verifierChatClient,
        String userMessage,
        ApprovalGateMode approvalGateMode,
        PlatformIssueService issueService,
        TenantGuardrailRuleEvaluator tenantRuleEvaluator,
        com.agenticai.core.guardrails.procedures.ProcedureGuardEvaluator procedureGuard,
        ToolDispatchGate dispatchGate,
        MeterRegistry meterRegistry
    ) {
        this.dispatchGate = dispatchGate;
        this.meterRegistry = meterRegistry;
        this.delegate = delegate;
        this.stepCounter = stepCounter;
        this.maxStepsPerTurn = maxStepsPerTurn;
        this.guardrailService = guardrailService;
        this.approvalPolicyService = approvalPolicyService;
        this.approvalGateService = approvalGateService;
        this.agentId = agentId;
        this.sessionId = sessionId;
        this.verifierService = verifierService;
        this.rigorousVerification = rigorousVerification;
        this.verifierChatClient = verifierChatClient;
        this.userMessage = userMessage;
        this.approvalGateMode = approvalGateMode != null ? approvalGateMode : ApprovalGateMode.POLICY_DEFAULT;
        this.issueService = issueService;
        this.tenantRuleEvaluator = tenantRuleEvaluator;
        this.procedureGuard = procedureGuard;
    }

    /**
     * Untrusted-content taint gate for this turn (see {@link TaintGate}); set by the factory that
     * builds the chain, null = no taint layer, exactly the previous behaviour. A setter rather than
     * another constructor overload: this class's constructors are already past the parameter budget.
     */
    private volatile TaintGate taintGate;

    public GuardedToolCallback withTaintGate(TaintGate taintGate) {
        this.taintGate = taintGate;
        return this;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        return timedCall(toolInput, null);
    }

    /**
     * Same checks as {@link #call(String)}; the only difference is that the
     * {@link ToolContext} Spring AI supplied is forwarded to the wrapped tool
     * instead of being dropped (the interface default would call {@code
     * call(toolInput)} on THIS wrapper and lose it).
     */
    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return timedCall(toolInput, toolContext);
    }

    private String timedCall(String toolInput, ToolContext toolContext) {
        String toolName = delegate.getToolDefinition().name();
        long startedAt = System.nanoTime();
        String[] outcome = {OUTCOME_SUCCESS};
        try {
            return guardedCall(toolName, toolInput, toolContext, outcome);
        } catch (ToolStepLimitExceededException e) {
            outcome[0] = OUTCOME_STEP_LIMIT;
            throw e;
        } catch (RuntimeException e) {
            outcome[0] = OUTCOME_ERROR;
            recordError(toolName, e);
            throw e;
        } finally {
            recordTiming(toolName, outcome[0], System.nanoTime() - startedAt);
        }
    }

    private String guardedCall(String toolName, String toolInput, ToolContext toolContext, String[] outcome) {
        // inDelegate[0] is true only while the raw tool executes: an exception
        // thrown then is the TOOL's own failure and passes through unwrapped.
        // A RuntimeException from any surrounding check (approval-policy read,
        // procedure guard, verifier, output validation/sanitization) is platform
        // machinery failing around a tool that never ran - tagged
        // ToolInfrastructureException so LoggingToolCallback's per-tool circuit
        // breaker is billed only for failures the tool actually owns (one shared
        // outage must not trip the breakers of every healthy tool).
        boolean[] inDelegate = {false};
        try {
            return guardedCallBody(toolName, toolInput, toolContext, outcome, inDelegate);
        } catch (RuntimeException e) {
            if (inDelegate[0] || e instanceof ToolStepLimitExceededException
                || e instanceof GuardrailViolationException || e instanceof ToolInfrastructureException) {
                throw e;
            }
            throw new ToolInfrastructureException(toolName, e);
        }
    }

    private String guardedCallBody(String toolName, String toolInput, ToolContext toolContext, String[] outcome,
        boolean[] inDelegate) {
        if (PARKED_TURNS.contains(stepCounter)) {
            outcome[0] = OUTCOME_APPROVAL_PENDING;
            return "This tool call ('" + toolName + "') was NOT executed: an earlier action in this turn is "
                + "waiting for the user's approval, and nothing else runs until they decide. Do not call any "
                + "more tools. Reply with one short sentence saying what is waiting for approval, and stop.";
        }
        int step = stepCounter.incrementAndGet();
        if (step > maxStepsPerTurn) {
            throw new ToolStepLimitExceededException(agentId, sessionId, maxStepsPerTurn);
        }

        // Pre-dispatch policy (tool classification, 2026-09-28): a
        // quarantined/invalidly-declared tool is refused before any other
        // check can spend work on it (the verifier is an LLM call; the
        // approval gate writes a request a human would then have to reject).
        ToolDispatchGate.Verdict gateVerdict = checkDispatchGate(toolName);
        if (gateVerdict.outcome() == ToolDispatchGate.Outcome.DENY) {
            outcome[0] = OUTCOME_BLOCKED;
            return "This tool call ('" + toolName + "') was not executed: "
                + (gateVerdict.reason() != null ? gateVerdict.reason() : "this tool is blocked in this workspace.")
                + " Do not retry this tool - proceed without it or tell the user it is unavailable.";
        }
        boolean gateRequiresApproval = gateVerdict.outcome() == ToolDispatchGate.Outcome.REQUIRE_APPROVAL;

        if (rigorousVerification && verifierService != null) {
            VerificationResult verification = verifierService.verify(verifierChatClient, userMessage, toolName, toolInput);
            if (!verification.approved()) {
                outcome[0] = OUTCOME_BLOCKED;
                return "This tool call ('" + toolName + "') was not executed - an independent check found it "
                    + "didn't match your request: " + verification.reason()
                    + ". Try again with a call that matches what was actually asked, or ask the user to clarify.";
            }
        }

        // Procedures layer (Tier-3): the agent's attached SOPs get the FIRST
        // say - a FORBID_TOOL rule soft-stops the call before even the
        // approval-policy gate runs (an approval must never be recorded for
        // a call a business rule flatly prohibits), and a REQUIRE_APPROVAL
        // rule tightens the gate below. DENY is returned to the model as an
        // explanation it can react to, same soft-stop posture as the
        // verifier and guardrail paths - never thrown.
        boolean procedureRequiresApproval = false;
        if (procedureGuard != null) {
            com.agenticai.core.guardrails.procedures.ProcedureGuardEvaluator.Decision guardDecision =
                procedureGuard.check(agentId, sessionId, toolName);
            if (guardDecision.outcome()
                == com.agenticai.core.guardrails.procedures.ProcedureGuardEvaluator.Outcome.DENY) {
                outcome[0] = OUTCOME_BLOCKED;
                return "This tool call ('" + toolName + "') was blocked by the workspace procedure \""
                    + guardDecision.procedureName() + "\": " + guardDecision.ruleInstruction()
                    + " Do not retry this tool - proceed without it or ask the user how to continue.";
            }
            procedureRequiresApproval = guardDecision.outcome()
                == com.agenticai.core.guardrails.procedures.ProcedureGuardEvaluator.Outcome.REQUIRE_APPROVAL;
        }

        // Untrusted-content taint (app.agents.taint.mode): once content an outsider could have
        // authored has entered this turn, a side-effecting tool needs a human. ORs into the approval
        // gate below, so it can only tighten - and, unlike the classification gate, it applies to
        // NEVER_GATE agents too: a full-autonomy tier is a choice about the user's own requests, not
        // about a web page's. Observe mode returns NONE and only records what would have happened.
        TaintGate.Decision taint = taintGate != null ? taintGate.check(toolName, toolInput) : TaintGate.Decision.NONE;

        String actionType = ACTION_TYPE_PREFIX + toolName;
        // #815 Governance follow-up - see ApprovalGateMode's javadoc.
        // POLICY_DEFAULT (every agent that hasn't been given a different
        // autonomy tier) is exactly the pre-existing check, unchanged. The
        // procedure REQUIRE_APPROVAL clause ORs in - a procedure can only
        // ever TIGHTEN the gate, matching ApprovalPolicyOverride's "explicit
        // override wins in either direction" being the only loosening path.
        // The classification gate's REQUIRE_APPROVAL ORs in the same way - it
        // can only tighten, never loosen, what the policy below decides - EXCEPT
        // for NEVER_GATE agents: an explicit full-autonomy tier is the human's
        // own choice and the (default-on) classification gate must not override
        // it. Procedure rules still apply to them, as before.
        boolean classificationGate = gateRequiresApproval && approvalGateMode != ApprovalGateMode.NEVER_GATE;
        boolean requiresApproval = procedureRequiresApproval || classificationGate || taint.requiresApproval()
            || switch (approvalGateMode) {
            case ALWAYS_GATE -> true;
            case NEVER_GATE -> false;
            case POLICY_DEFAULT -> approvalPolicyService.requiresApproval(actionType);
        };
        // ApprovalReplayGrant.consume is checked ONLY when the policy says
        // this call is gated (a non-gated call never needs, and never
        // consumes, a grant) - and it also overrides ALWAYS_GATE above,
        // deliberately: the grant only ever exists because a human already
        // approved this exact action type, which is precisely what
        // ALWAYS_GATE demands. See ApprovalReplayGrant's javadoc.
        if (requiresApproval && !ApprovalReplayGrant.consume(actionType)) {
            outcome[0] = OUTCOME_APPROVAL_PENDING;
            return recordPendingApprovalAndExplain(actionType, toolName, toolInput, taint.reason());
        }

        // Left uncleared on a throw - inDelegate[0] staying true is exactly how
        // the wrapper above knows the exception came from the tool itself.
        inDelegate[0] = true;
        String rawOutput = toolContext != null ? delegate.call(toolInput, toolContext) : delegate.call(toolInput);
        inDelegate[0] = false;
        // Recorded only AFTER the delegate actually ran - a denied/gated/
        // soft-stopped call above can never satisfy a REQUIRE_TOOL_BEFORE
        // prerequisite. See ProcedureGuardEvaluator#recordCall.
        if (procedureGuard != null) {
            procedureGuard.recordCall(sessionId, toolName);
        }

        // #815 Gap 1 - deterministic structural check (length bound, JSON
        // well-formedness) BEFORE the content-safety sanitizer below: no
        // point running a safety pass over a payload that's already known
        // to be truncated/malformed. Zero LLM calls, runs on every tool
        // call - see ToolCallOutputValidator's javadoc.
        ToolCallOutputValidator.ValidationResult validation = ToolCallOutputValidator.validate(toolName, rawOutput);
        if (!validation.valid()) {
            outcome[0] = OUTCOME_VALIDATION_FAILED;
            return ToolCallOutputValidator.VALIDATION_FAILURE_PREFIX + "Tool output from '" + toolName
                + "' failed validation and was not returned to the model: " + validation.reason()
                + ". The tool may be misbehaving - try a different approach or a smaller request.";
        }

        // 3-arg overload: SemanticGuardrailService (the default as of
        // Master-Roadmap-2026.md item #7) uses verifierChatClient for its
        // injection-classifier check; PatternBasedGuardrailService (explicit
        // app.guardrails.implementation=pattern opt-out) ignores it via the
        // default method that just delegates to the 2-arg version - see
        // GuardrailService's javadoc.
        try {
            String sanitized = guardrailService.sanitizeToolOutput(toolName, rawOutput, verifierChatClient);
            // Tenant-authored rules run last, on what the platform's own layer
            // already approved. Same soft-stop handling as any other guardrail
            // violation - a tenant's over-broad regex costs one tool result,
            // not the whole turn.
            String result = applyTenantRules(toolName, sanitized);
            // Last step: a taint source taints the turn and (enforce mode) is delimited as data.
            return taintGate != null ? taintGate.afterCall(toolName, toolInput, result) : result;
        } catch (GuardrailViolationException violation) {
            outcome[0] = OUTCOME_OUTPUT_WITHHELD;
            return recordAndExplain(toolName, violation);
        }
    }

    /**
     * No gate configured allows the call. A gate that throws follows {@link GuardrailFailMode}:
     * refused when closed (the default), allowed when open - a policy lookup failure must not be
     * a way to switch the policy off.
     */
    private ToolDispatchGate.Verdict checkDispatchGate(String toolName) {
        if (dispatchGate == null) {
            return ToolDispatchGate.Verdict.allow();
        }
        try {
            ToolDispatchGate.Verdict verdict = dispatchGate.check(toolName);
            return verdict != null ? verdict : ToolDispatchGate.Verdict.allow();
        } catch (RuntimeException e) {
            if (GuardrailFailMode.isClosed()) {
                log.warn("Tool dispatch gate failed for tool '{}' - refusing the call (fail-closed)", toolName, e);
                return ToolDispatchGate.Verdict.deny(
                    "the workspace's tool policy could not be checked right now.");
            }
            log.warn("Tool dispatch gate failed for tool '{}' - allowing the call (fail-open)", toolName, e);
            return ToolDispatchGate.Verdict.allow();
        }
    }

    private void recordTiming(String toolName, String outcome, long elapsedNanos) {
        if (meterRegistry == null) {
            return;
        }
        try {
            Timer.builder(CALL_TIMER)
                .description("Agent tool calls through the guardrail wrapper, by tool and outcome")
                .tag("tool", toolName)
                .tag("outcome", outcome)
                .register(meterRegistry)
                .record(elapsedNanos, TimeUnit.NANOSECONDS);
        } catch (RuntimeException e) {
            log.debug("Could not record {} for tool '{}'", CALL_TIMER, toolName, e);
        }
    }

    private void recordError(String toolName, RuntimeException error) {
        if (meterRegistry == null) {
            return;
        }
        try {
            meterRegistry.counter(ERROR_COUNTER, "tool", toolName, "exception", error.getClass().getSimpleName())
                .increment();
        } catch (RuntimeException e) {
            log.debug("Could not record {} for tool '{}'", ERROR_COUNTER, toolName, e);
        }
    }

    /**
     * Applies this tenant's own {@link GuardrailRule} rows to a tool result.
     *
     * <p>A BLOCK match is raised as a {@link GuardrailViolationException} so it
     * lands in exactly the same soft-stop path as a compiled-in pattern match
     * (withheld from the model, recorded as a platform issue, the turn
     * continues); a REDACT match returns the rewritten text. Anything that goes
     * wrong inside the evaluator already fails open there, so this method has
     * no error handling of its own beyond the null-evaluator case.
     */
    private String applyTenantRules(String toolName, String sanitized) {
        if (tenantRuleEvaluator == null || sanitized == null || sanitized.isEmpty()) {
            return sanitized;
        }
        TenantGuardrailRuleEvaluator.Outcome outcome = tenantRuleEvaluator.apply(sanitized, true);
        if (outcome.blocked()) {
            log.warn("Tenant guardrail rule '{}' blocked output from tool '{}'", outcome.blockedByRuleName(), toolName);
            throw new GuardrailViolationException(
                toolName, "output matched this workspace's guardrail rule \"" + outcome.blockedByRuleName() + "\"");
        }
        return outcome.text();
    }

    /**
     * A {@link GuardrailViolationException} is a soft stop, same posture as
     * every other stop condition in {@link #call} except the step-limit
     * (which is a deliberate runaway-loop hard-stop, see this class's own
     * javadoc): the flagged output is still withheld from the model either
     * way, but a false positive now costs one tool result instead of the
     * whole turn. Best-effort recorded on the admin Issues portal so a
     * recurring false positive on the same tool is triaged like any other
     * platform issue, rather than only ever appearing in application logs.
     */
    private String recordAndExplain(String toolName, GuardrailViolationException e) {
        if (issueService != null) {
            try {
                issueService.record(
                    IssueSource.GUARDRAIL_FALSE_POSITIVE, toolName, IssueSeverity.WARNING,
                    "Guardrail blocked output from tool '" + toolName + "'", e.getMessage()
                );
            } catch (RuntimeException recordingFailure) {
                log.warn("Failed to record platform issue for guardrail violation on tool '{}'", toolName, recordingFailure);
            }
        }
        return "Tool output from '" + toolName + "' was withheld by a content-safety check and not returned to "
            + "the model: " + e.getMessage() + ". This may be a false positive (e.g. the tool's own error text "
            + "misread as unsafe) - try a different approach, or tell the user this capability hit a safety check "
            + "and to try again; an admin has been notified.";
    }

    /**
     * The confirmation card's summary line: the tool's own plain-language description of THIS call when it
     * implements {@link ToolConfirmationDescriber}, otherwise the historic bare {@code "Tool call: <name>"}. Any
     * failure in the describer falls back to the default - describing a call must never stop it being recorded.
     */
    private String approvalSummaryFor(String toolName, String toolInput) {
        String summary = "Tool call: " + toolName;
        if (delegate instanceof ToolConfirmationDescriber describer) {
            try {
                String described = describer.describeForConfirmation(toolInput);
                if (described != null && !described.isBlank()) {
                    summary = described;
                }
            } catch (RuntimeException e) {
                log.debug("Tool {} could not describe its call for confirmation; using the default summary", toolName, e);
            }
        }
        return summary;
    }

    private String recordPendingApprovalAndExplain(String actionType, String toolName, String toolInput,
        String taintReason) {
        String requestedBy = "agent:" + agentId + ":session:" + sessionId;
        String summary = approvalSummaryFor(toolName, toolInput);
        if (taintReason != null) {
            // The reviewer must see WHY an otherwise-ungated tool is waiting on them.
            summary = summary + " [flagged: " + taintReason + "]";
        }
        // 7-arg overload: records a replay envelope (tool name, exact args,
        // agent/session, and the current thread's tenant/subject - this
        // method runs inside TenantContextPropagatingToolCallback's restored
        // context, so "current" is genuinely the originating turn's) so an
        // approval later can actually re-run this call, not just audit it.
        PARKED_TURNS.add(stepCounter);
        ApprovalRequest request = approvalGateService.recordToolCallForApproval(
            actionType, toolName, summary, toolInput, requestedBy, agentId, sessionId,
            ToolDefinitionDigest.of(delegate.getToolDefinition())
        );
        // Returned to the MODEL, not thrown - an agent asking a gated tool
        // to run is a normal, expected outcome (unlike a step-limit/
        // guardrail violation, which are stop conditions), so the model
        // should see this as a tool result it can react to and tell the
        // user about, not a hard failure.
        return "This action ('" + toolName + "') requires human approval before it can run and was NOT executed. "
            + "A tenant-admin has been notified (approval request " + request.getId() + "). If approved, it will "
            + "run automatically with these exact arguments and the user will be notified of the result - do not "
            + "retry it yourself. STOP NOW: do not call any other tool, do not claim the action was done, and "
            + "do not continue the task. Reply with one short sentence saying you are waiting for approval.";
    }
}
