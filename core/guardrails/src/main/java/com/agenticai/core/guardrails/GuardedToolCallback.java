package com.agenticai.core.guardrails;

import com.agenticai.core.approval.ApprovalPolicyService;
import com.agenticai.core.approval.ApprovalReplayGrant;
import com.agenticai.core.approval.ToolCallOrigin;
import com.agenticai.core.approval.ToolCallScope;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.audit.IssueSeverity;
import com.agenticai.core.audit.IssueSource;
import com.agenticai.core.audit.PlatformIssueService;
import com.agenticai.core.guardrails.taint.TaintGate;
import com.agenticai.core.prompts.PromptResolver;
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
 *       "N calls to this specific tool." Past the ceiling a call is NOT run and
 *       the model is handed the {@code tool_budget_exhausted} tool result (the
 *       same one the tool loop's round cap uses) so it can write a normal final
 *       answer; only a model that keeps calling tools after that for more than
 *       {@code app.guardrails.step-limit.grace-calls} calls gets {@link
 *       ToolStepLimitExceededException}, which stops the turn with an error. See
 *       {@link ToolStepLimitProperties} for calls versus rounds.</li>
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
 *   <li><b>Output cap</b> - a result longer than {@link ToolOutputProperties}
 *       allows for the tool ({@code app.tools.output.*}) is TRUNCATED, with a
 *       marker that states how much was cut, BEFORE sanitization below: no
 *       point running a safety pass over text the model will not see. Nothing
 *       is withheld for its shape (a result that starts with {@code [} or
 *       {@code &#123;} need not be JSON). Zero LLM calls, runs on every tool
 *       call.</li>
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

    static final String ACTION_TYPE_PREFIX = "mcp_tool.";

    /** Timer over every guarded call, tags {@code tool} and {@code outcome}. */
    public static final String CALL_TIMER = "agent.tool.calls";
    /** Counter of guarded calls whose delegate (or a check) threw, tags {@code tool} and {@code exception}. */
    public static final String ERROR_COUNTER = "agent.tool.errors";

    static final String OUTCOME_SUCCESS = "success";
    static final String OUTCOME_ERROR = "error";
    static final String OUTCOME_STEP_LIMIT = "step_limit";
    static final String OUTCOME_BLOCKED = "blocked";
    /** An offline eval turn's sandbox answered with a simulated result; the tool did not run. */
    static final String OUTCOME_SANDBOXED = "sandboxed";
    static final String OUTCOME_APPROVAL_PENDING = "approval_pending";
    static final String OUTCOME_OUTPUT_WITHHELD = "output_withheld";

    /**
     * Approval state of turns whose tools were built WITHOUT an explicit {@link TurnApprovalState}
     * (see {@link #withTurnApproval}), keyed by the turn's step counter (one per turn, compared by
     * identity, weakly held so a finished turn is collected). Once a call parks, nothing else runs in
     * that turn: the model could otherwise carry on with sibling or follow-up tool calls (often ones
     * that depend on the parked one) and finish the task before the human has decided.
     */
    private static final java.util.Map<AtomicInteger, TurnApprovalState> FALLBACK_STATES =
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** Step counters currently tracked by the fallback registry (for leak tests). */
    static int trackedTurnCount() {
        synchronized (FALLBACK_STATES) {
            return FALLBACK_STATES.size();
        }
    }

    private static final ToolOutputProperties DEFAULT_OUTPUT_LIMITS = new ToolOutputProperties();
    private static final ToolStepLimitProperties DEFAULT_STEP_LIMITS = new ToolStepLimitProperties();

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
    private volatile ConversationTailSource conversationTail;
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

    /** The output cap shared by every tool; null = the module defaults (see {@link ToolOutputProperties}). */
    private volatile ToolOutputProperties outputLimits;

    /** What happens past the step ceiling; null = the module defaults (see {@link ToolStepLimitProperties}). */
    private volatile ToolStepLimitProperties stepLimits;

    /** Sets the tool-output cap ({@code app.tools.output.*}); a setter for the same reason as {@link #withTaintGate}. */
    public GuardedToolCallback withOutputLimits(ToolOutputProperties limits) {
        this.outputLimits = limits;
        return this;
    }

    /** Sets the past-the-ceiling behaviour ({@code app.guardrails.step-limit.*}). */
    public GuardedToolCallback withStepLimits(ToolStepLimitProperties limits) {
        this.stepLimits = limits;
        return this;
    }

    private ToolOutputProperties outputLimits() {
        ToolOutputProperties configured = outputLimits;
        return configured != null ? configured : DEFAULT_OUTPUT_LIMITS;
    }

    private ToolStepLimitProperties stepLimits() {
        ToolStepLimitProperties configured = stepLimits;
        return configured != null ? configured : DEFAULT_STEP_LIMITS;
    }

    /** Where a read-only call let through under SUPERVISED is reported for the audit log; null = not audited (the journal and log still record it). */
    private volatile ReadOnlyAutoAllow.Audit readOnlyAutoAllowAudit;

    /** Sets the audit sink for {@link ApprovalGateMode#GATE_UNLESS_READ_ONLY} calls; a setter for the same reason as {@link #withTaintGate}. */
    public GuardedToolCallback withReadOnlyAutoAllowAudit(ReadOnlyAutoAllow.Audit audit) {
        this.readOnlyAutoAllowAudit = audit;
        return this;
    }

    /** The turn's approval state (parked flag, scoped memory id); null = the per-step-counter fallback. */
    private volatile TurnApprovalState turnApproval;

    /** Texts and limits for a held call; null = the module defaults (see {@link ApprovalParkProperties}). */
    private volatile ApprovalParkProperties parkTexts;

    /**
     * Shares {@code state} with the rest of the turn, so the turn loop can ask whether the turn is
     * parked and the replay envelope carries the turn's scoped memory id. A setter for the same
     * reason as {@link #withTaintGate}.
     */
    public GuardedToolCallback withTurnApproval(TurnApprovalState state) {
        this.turnApproval = state;
        return this;
    }

    /**
     * Where the RIGOROUS verifier reads the end of this turn's conversation, so a follow-up is judged against
     * what it answers. Null (or a turn with no scoped memory id) leaves the verifier on the current message alone.
     */
    public GuardedToolCallback withConversationTail(ConversationTailSource source) {
        this.conversationTail = source;
        return this;
    }

    public GuardedToolCallback withApprovalParkTexts(ApprovalParkProperties texts) {
        this.parkTexts = texts;
        return this;
    }

    /**
     * Names of dispatch-plumbing tools ({@code search_tools}, {@code call_tool}, {@code
     * restore_tool_result}) a post-park queue must NOT file approval requests for: an approval
     * replay resolves tools by exact name without deferral, so an approved {@code call_tool}
     * resolves to a refusal and an approved {@code search_tools} is meaningless - recording either
     * only pollutes the approver's inbox. Excluded calls are refused without a record. Set by the
     * module that knows the plumbing names (this one cannot see them); empty = record everything,
     * the pre-{@code queueExcludedTools} behavior.
     */
    public GuardedToolCallback withQueueExcludedTools(java.util.Set<String> names) {
        this.queueExcludedTools = names == null ? java.util.Set.of() : names;
        return this;
    }

    private volatile java.util.Set<String> queueExcludedTools = java.util.Set.of();

    private boolean queueRecords(String toolName) {
        return !queueExcludedTools.contains(toolName);
    }

    /**
     * Where the texts this class hands the model come from (the prompt registry, {@link GuardrailPrompts}); the
     * shipped wording until set. The agent is this callback's own, the tenant is the calling thread's, so a tenant
     * or agent override applies on every path that builds the tool chain (stream, buffered, delegation, jobs, a
     * continuation after an approval). A setter for the same reason as {@link #withTaintGate}.
     */
    public GuardedToolCallback withPrompts(PromptResolver resolver) {
        if (resolver != null) {
            this.prompts = resolver;
        }
        return this;
    }

    private volatile PromptResolver prompts = PromptResolver.defaultsOnly();

    private String text(String key, String... nameValuePairs) {
        return GuardrailPrompts.text(prompts, key, agentId, nameValuePairs);
    }

    /**
     * Whether this callback's turn is parked on a human approval, and the turn's scoped memory id:
     * the explicit state when {@link #withTurnApproval} supplied one, otherwise the state shared by
     * every callback built on the same step counter.
     */
    public TurnApprovalState turnApprovalState() {
        TurnApprovalState explicit = turnApproval;
        if (explicit != null) {
            return explicit;
        }
        return FALLBACK_STATES.computeIfAbsent(stepCounter, k -> new TurnApprovalState(null));
    }

    private static final ApprovalParkProperties DEFAULT_PARK_TEXTS = new ApprovalParkProperties();

    /**
     * Where this turn's tool calls come from, for a tool that records its own approval: the goal and
     * conversation identity when the turn knows them, otherwise only the scoped history id (such a request
     * is recorded but its task is not continued), or {@code null} when the turn knows nothing.
     */
    private ToolCallOrigin callOrigin() {
        TurnApprovalState state = turnApprovalState();
        ApprovalParkProperties texts = parkTexts != null ? parkTexts : DEFAULT_PARK_TEXTS;
        ToolCallOrigin origin = state.origin(texts.getMaxGoalChars(), texts.getGoalCutMarker());
        if (origin != null) {
            return origin;
        }
        return state.memorySessionId() == null ? null : ToolCallOrigin.ofMemorySession(state.memorySessionId());
    }

    private ApprovalParking parking() {
        ApprovalParkProperties texts = parkTexts;
        return new ApprovalParking(
            delegate, approvalGateService, agentId, sessionId, turnApprovalState(),
            texts != null ? texts : new ApprovalParkProperties(), prompts);
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
            return ToolResultMarker.markGuardOutcome(guardedCall(toolName, toolInput, toolContext, outcome), outcome[0]);
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
        // procedure guard, verifier, output cap/sanitization) is platform
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
        if (turnApprovalState().isParked()) {
            // The answer carries the approval_parked marker so the tool loop ends the invocation
            // instead of letting a non-compliant model queue calls forever - see
            // ArgumentSanitizingToolCallingManager's parked detection in core/chat.
            ApprovalParking.Outcome queued =
                    parking().queueBehindPark(toolName, toolInput, queueRecords(toolName));
            outcome[0] = queued.recorded() ? OUTCOME_APPROVAL_PENDING : OUTCOME_BLOCKED;
            return ApprovalParked.result(queued.modelText());
        }
        int step = stepCounter.incrementAndGet();
        if (step > maxStepsPerTurn) {
            ToolStepLimitProperties limits = stepLimits();
            if (limits.stageOf(step, maxStepsPerTurn) == ToolStepLimitProperties.Stage.ABORT) {
                // The model was told the budget is gone and kept calling tools: that is a loop.
                throw new ToolStepLimitExceededException(agentId, sessionId, maxStepsPerTurn);
            }
            log.warn("Agent {} (session {}) is past its tool-call ceiling of {} (call {}): '{}' not run, "
                + "the model is told to answer now", agentId, sessionId, maxStepsPerTurn, step, toolName);
            outcome[0] = OUTCOME_STEP_LIMIT;
            return GuardrailPrompts.budgetCallsResult(prompts, agentId, limits, maxStepsPerTurn);
        }

        // Pre-dispatch policy (tool classification, 2026-09-28): a
        // quarantined/invalidly-declared tool is refused before any other
        // check can spend work on it (the verifier is an LLM call; the
        // approval gate writes a request a human would then have to reject).
        ToolDispatchGate.Verdict gateVerdict = checkDispatchGate(toolName);
        if (gateVerdict.outcome() == ToolDispatchGate.Outcome.DENY) {
            outcome[0] = OUTCOME_BLOCKED;
            return text(GuardrailPrompts.DISPATCH_DENIED, "tool", toolName, "reason",
                gateVerdict.reason() != null ? gateVerdict.reason() : text(GuardrailPrompts.DISPATCH_DENIED_DEFAULT_REASON));
        }
        boolean gateRequiresApproval = gateVerdict.outcome() == ToolDispatchGate.Outcome.REQUIRE_APPROVAL;

        if (rigorousVerification && verifierService != null) {
            VerificationResult verification;
            // The judge reads its prompt for the agent this callback belongs to (see GuardrailPromptScope).
            try (GuardrailPromptScope scope = GuardrailPromptScope.open(agentId)) {
                ToolCallVerifierService.ConversationTail tail = verifierConversationTail();
                verification = tail != null
                    ? verifierService.verify(verifierChatClient, userMessage, tail, toolName, toolInput)
                    : verifierService.verify(verifierChatClient, userMessage, toolName, toolInput);
            }
            if (!verification.approved()) {
                outcome[0] = OUTCOME_BLOCKED;
                return text(GuardrailPrompts.VERIFIER_REJECTED, "tool", toolName, "reason", verification.reason());
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
                return text(GuardrailPrompts.PROCEDURE_BLOCKED, "tool", toolName,
                    "procedure", guardDecision.procedureName(), "instruction", guardDecision.ruleInstruction());
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
            // SUPERVISED with app.guardrails.supervised.gate-read-only=false: only the human-approval requirement is
            // skipped, and only for a call the dispatch gate positively classified as read-only. Everything above
            // (procedures, classification REQUIRE_APPROVAL from a hook or the confirmation gate, taint) was OR-ed in
            // already, and every check below still runs.
            case GATE_UNLESS_READ_ONLY -> !gateVerdict.readOnly();
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
            // An offline eval turn may run under a sandbox auto-approval (SandboxApprovalGrants): it skips
            // ONLY this park, for the tools the grant permits, and counts every call it lets through.
            // Every other check above and below this point still applies, and an ordinary turn has no grant.
            SandboxApprovalGrants.Grant sandbox = turnApprovalState().sandboxGrant();
            if (sandbox == null || !sandbox.permits(toolName)) {
                ApprovalParking.Outcome held = parking().park(actionType, toolName, toolInput, taint.reason());
                outcome[0] = held.recorded() ? OUTCOME_APPROVAL_PENDING : OUTCOME_ERROR;
                // The approval_parked marker lets the tool loop end the invocation if the model
                // keeps calling tools instead of reporting the wait - see the isParked branch above.
                return held.recorded() ? ApprovalParked.result(held.modelText()) : held.modelText();
            }
            sandbox.recordUse(toolName);
            log.info("Agent {} (session {}): '{}' needed approval and was auto-approved by the eval sandbox grant"
                + " of '{}' - this is an offline eval turn, not a person's decision", agentId, sessionId, toolName,
                sandbox.actor());
        }

        // An isolated eval turn (SandboxApprovalGrants.registerSandbox) runs a tool for real only when the sandbox
        // rule says it cannot change anything: every other call, including one the sandbox just approved, is
        // answered with a simulated result and the delegate is never reached. A rule that throws simulates the call
        // (fail closed). An ordinary turn has no grant, so nothing here applies to it.
        SandboxApprovalGrants.Grant isolation = turnApprovalState().sandboxGrant();
        if (isolation != null && isolation.isolated()) {
            ToolEffectSandbox.Verdict effect;
            try {
                effect = isolation.effects().check(toolName, toolInput);
            } catch (RuntimeException e) {
                log.warn("Eval sandbox could not classify tool '{}' - simulating the call (fail-closed): {}", toolName,
                    e.toString());
                effect = ToolEffectSandbox.Verdict.simulate("its effect could not be determined");
            }
            if (effect == null || !effect.execute()) {
                isolation.recordSimulated(toolName);
                outcome[0] = OUTCOME_SANDBOXED;
                log.info("Agent {} (session {}): '{}' was not run - offline eval turn, call simulated ({})", agentId,
                    sessionId, toolName, effect == null ? "no verdict" : effect.reason());
                return text(GuardrailPrompts.EVAL_SANDBOX_SIMULATED, "tool", toolName);
            }
        }

        if (approvalGateMode == ApprovalGateMode.GATE_UNLESS_READ_ONLY && gateVerdict.readOnly() && !requiresApproval) {
            recordReadOnlyAutoAllowed(toolName, toolInput);
        }

        // Left uncleared on a throw - inDelegate[0] staying true is exactly how
        // the wrapper above knows the exception came from the tool itself.
        inDelegate[0] = true;
        String rawOutput;
        // A tool that holds its OWN approval inside call() (a code-workspace run, a channel send) reads this
        // scope to record the request with the conversation it belongs to, so the decision continues the task.
        try (ToolCallScope.Scope ignored = ToolCallScope.open(toolName, agentId, sessionId, null, callOrigin())) {
            rawOutput = toolContext != null ? delegate.call(toolInput, toolContext) : delegate.call(toolInput);
        }
        inDelegate[0] = false;
        // Recorded only AFTER the delegate actually ran - a denied/gated/
        // soft-stopped call above can never satisfy a REQUIRE_TOOL_BEFORE
        // prerequisite. See ProcedureGuardEvaluator#recordCall.
        if (procedureGuard != null) {
            procedureGuard.recordCall(sessionId, toolName);
        }

        // A result the PLATFORM authored - registered by its producer with ToolResultMarker
        // (call_tool's "not loaded yet" hint, an MCP argument refusal, another guard's parked or
        // withheld answer relayed through call_tool) - is not tool output and carries no untrusted
        // bytes, so the cap, the content screen and the tenant rules below do not run on it, same
        // as the guard's own answers above. Screening platform text once withheld exactly this kind
        // of diagnostic as a false-positive injection and hid the model's own recovery
        // instructions. The bypass is the REGISTERED mark only, never the wording - a tool result
        // that merely reads like a refusal still gets screened.
        if (ToolResultMarker.kindOf(rawOutput).isPresent()) {
            return rawOutput;
        }

        // Context-budget cap, BEFORE the content-safety sanitizer below: no point running a safety
        // pass over text that is about to be cut. The head is kept and a marker says how much was cut
        // (an over-long result used to be discarded outright, and plain text that merely started with
        // '[' or '{' was withheld as "malformed JSON"). One cap for every tool, see ToolOutputProperties.
        rawOutput = outputLimits().limit(toolName, rawOutput);

        // 3-arg overload: SemanticGuardrailService (the default as of
        // Master-Roadmap-2026.md item #7) uses verifierChatClient for its
        // injection-classifier check; PatternBasedGuardrailService (explicit
        // app.guardrails.implementation=pattern opt-out) ignores it via the
        // default method that just delegates to the 2-arg version - see
        // GuardrailService's javadoc.
        try {
            String sanitized;
            // The injection judge reads its prompt for the agent this callback belongs to (see GuardrailPromptScope).
            try (GuardrailPromptScope scope = GuardrailPromptScope.open(agentId)) {
                sanitized = guardrailService.sanitizeToolOutput(toolName, rawOutput, verifierChatClient);
            }
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
     * A SUPERVISED agent's read-only call is about to run with no human in the loop. That is the platform's decision,
     * not the person's, so it is made reviewable: logged, counted, noted on the turn (the turn journal reads it) and
     * sent to the audit sink. None of it may fail the call.
     */
    private void recordReadOnlyAutoAllowed(String toolName, String toolInput) {
        log.info("Agent {} (session {}): '{}' {}", agentId, sessionId, toolName, ReadOnlyAutoAllow.NOTE);
        try {
            turnApprovalState().markAutoAllowedReadOnly(toolName, toolInput);
        } catch (RuntimeException e) {
            log.debug("Could not note the auto-allowed read-only call '{}' on the turn", toolName, e);
        }
        if (meterRegistry != null) {
            try {
                meterRegistry.counter(ReadOnlyAutoAllow.METRIC).increment();
            } catch (RuntimeException e) {
                log.debug("Could not record {}", ReadOnlyAutoAllow.METRIC, e);
            }
        }
        ReadOnlyAutoAllow.Audit audit = readOnlyAutoAllowAudit;
        if (audit != null) {
            try {
                audit.autoAllowed(toolName, agentId, sessionId, ReadOnlyAutoAllow.NOTE);
            } catch (RuntimeException e) {
                log.warn("Could not audit the auto-allowed read-only call '{}'", toolName, e);
            }
        }
    }

    /** The conversation tail for the verifier: only when a source is wired and the turn has a scoped memory id. */
    private ToolCallVerifierService.ConversationTail verifierConversationTail() {
        ConversationTailSource source = conversationTail;
        String memorySessionId = turnApprovalState().memorySessionId();
        if (source == null || memorySessionId == null) {
            return null;
        }
        return (maxMessages, maxChars) -> source.tail(memorySessionId, maxMessages, maxChars);
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
            if (GuardrailSettings.failClosed(agentId)) {
                log.warn("Tool dispatch gate failed for tool '{}' - refusing the call (fail-closed)", toolName, e);
                return ToolDispatchGate.Verdict.deny(text(GuardrailPrompts.DISPATCH_CHECK_FAILED));
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
                toolName, text(GuardrailPrompts.TENANT_RULE_MATCHED, "rule", outcome.blockedByRuleName()));
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
        return text(GuardrailPrompts.OUTPUT_WITHHELD, "tool", toolName, "reason", e.getMessage());
    }
}
