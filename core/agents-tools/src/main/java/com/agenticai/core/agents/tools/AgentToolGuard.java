package com.agenticai.core.agents.tools;

import com.agenticai.core.agents.collab.RunBudget;
import com.agenticai.core.agents.core.AgentAutonomyTier;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.hooks.AgentToolHooks;
import com.agenticai.core.agents.tools.search.CallToolToolCallback;
import com.agenticai.core.agents.tools.search.SearchToolsToolCallback;
import com.agenticai.core.approval.ApprovalPolicyService;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.audit.PlatformIssueService;
import com.agenticai.core.guardrails.ApprovalGateMode;
import com.agenticai.core.guardrails.ApprovalParkProperties;
import com.agenticai.core.guardrails.GuardedToolCallback;
import com.agenticai.core.guardrails.GuardrailService;
import com.agenticai.core.guardrails.TenantGuardrailRuleEvaluator;
import com.agenticai.core.guardrails.ToolCallVerifierService;
import com.agenticai.core.guardrails.ToolDispatchGate;
import com.agenticai.core.guardrails.ToolOutputProperties;
import com.agenticai.core.guardrails.ToolStepLimitProperties;
import com.agenticai.core.guardrails.TurnApprovalState;
import com.agenticai.core.security.TenantContext;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Guard/log/step-event wrapping applied to every tool {@link AgentToolProvider} resolves. Extracted
 * from AgentToolProvider verbatim (extract-only, no behavior change).
 */
final class AgentToolGuard {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AgentToolGuard.class);

    private final ToolCallLogRepository toolCallLogRepository;
    private final AgentService agentService;
    private final GuardrailService guardrailService;
    private final ApprovalPolicyService approvalPolicyService;
    private final HumanApprovalGateService approvalGateService;
    private final int maxToolCallsPerTurn;
    private final ObjectProvider<ToolCallVerifierService> toolCallVerifierService;
    private final ObjectProvider<PlatformIssueService> issueService;
    private final ObjectProvider<TenantGuardrailRuleEvaluator> tenantGuardrailRuleEvaluator;
    private final ObjectProvider<com.agenticai.core.guardrails.procedures.ProcedureGuardEvaluator>
            procedureGuardEvaluator;
    // #815 Governance follow-up (Fail-safe: circuit breakers on repeated
    // failures) - plain field, not ObjectProvider: StringRedisTemplate is a
    // required (non-profile-gated) dependency in this module already (see
    // SessionMemoryService), so ToolCircuitBreakerService is always a real
    // bean wherever AgentToolProvider itself is.
    private final ToolCircuitBreakerService toolCircuitBreakerService;

    /**
     * Nullable (2026-09-28) - classification dispatch gate, {@code agent.tool.calls} metrics and
     * tenant PII redaction of tool output. See {@link AgentToolGovernance}. {@code null} (the
     * legacy constructor) wraps exactly as before.
     */
    private final AgentToolGovernance governance;

    AgentToolGuard(
            ToolCallLogRepository toolCallLogRepository,
            AgentService agentService,
            GuardrailService guardrailService,
            ApprovalPolicyService approvalPolicyService,
            HumanApprovalGateService approvalGateService,
            int maxToolCallsPerTurn,
            ObjectProvider<ToolCallVerifierService> toolCallVerifierService,
            ObjectProvider<PlatformIssueService> issueService,
            ObjectProvider<TenantGuardrailRuleEvaluator> tenantGuardrailRuleEvaluator,
            ObjectProvider<com.agenticai.core.guardrails.procedures.ProcedureGuardEvaluator>
                    procedureGuardEvaluator,
            ToolCircuitBreakerService toolCircuitBreakerService) {
        this(
                toolCallLogRepository,
                agentService,
                guardrailService,
                approvalPolicyService,
                approvalGateService,
                maxToolCallsPerTurn,
                toolCallVerifierService,
                issueService,
                tenantGuardrailRuleEvaluator,
                procedureGuardEvaluator,
                toolCircuitBreakerService,
                null);
    }

    AgentToolGuard(
            ToolCallLogRepository toolCallLogRepository,
            AgentService agentService,
            GuardrailService guardrailService,
            ApprovalPolicyService approvalPolicyService,
            HumanApprovalGateService approvalGateService,
            int maxToolCallsPerTurn,
            ObjectProvider<ToolCallVerifierService> toolCallVerifierService,
            ObjectProvider<PlatformIssueService> issueService,
            ObjectProvider<TenantGuardrailRuleEvaluator> tenantGuardrailRuleEvaluator,
            ObjectProvider<com.agenticai.core.guardrails.procedures.ProcedureGuardEvaluator>
                    procedureGuardEvaluator,
            ToolCircuitBreakerService toolCircuitBreakerService,
            AgentToolGovernance governance) {
        this.governance = governance;
        if (governance != null) {
            governance.markWiredIntoDispatch();
        }
        this.toolCallLogRepository = toolCallLogRepository;
        this.agentService = agentService;
        this.guardrailService = guardrailService;
        this.approvalPolicyService = approvalPolicyService;
        this.approvalGateService = approvalGateService;
        this.maxToolCallsPerTurn = maxToolCallsPerTurn;
        this.toolCallVerifierService = toolCallVerifierService;
        this.issueService = issueService;
        this.tenantGuardrailRuleEvaluator = tenantGuardrailRuleEvaluator;
        this.procedureGuardEvaluator = procedureGuardEvaluator;
        this.toolCircuitBreakerService = toolCircuitBreakerService;
    }

    /**
     * The in-flight turn's step-event emitter, visible only for the duration of one synchronous
     * {@link #resolveToolsFor} call on the thread making it. Never read outside {@link
     * #guardAndLog}; never read from a reactive thread. See the six-arg {@code resolveToolsFor}'s
     * javadoc for why this is a thread-scoped hand-off instead of a parameter, and why the scope is
     * opened inside this class rather than by the caller.
     */
    static final ThreadLocal<AgentStepEventEmitter> STEP_EVENT_SCOPE = new ThreadLocal<>();

    /**
     * The in-flight turn's approval state (parked flag, scoped memory id), visible only while
     * {@link AgentToolProvider#resolveToolsFor(com.agenticai.core.agents.turn.TurnContext, UUID,
     * String, String, String, ChatClient, AgentStepEventEmitter,
     * com.agenticai.core.agents.core.ReasoningDepth)} resolves the turn's tools on the calling
     * thread. Same single-thread hand-off and the same reason as {@link #STEP_EVENT_SCOPE}; read
     * only by {@link #guardAndLog}, which hands it to the {@link GuardedToolCallback} so the turn
     * loop and the guard share one instance.
     */
    static final ThreadLocal<TurnApprovalState> TURN_APPROVAL_SCOPE = new ThreadLocal<>();

    /**
     * The in-flight turn's tool activity (what its tools are doing right now and have done), visible only
     * while the turn's tools are resolved on the calling thread; the same single-thread hand-off as {@link
     * #TURN_APPROVAL_SCOPE}. Read only by {@link #guardAndLog}, which gives it to the {@link
     * LoggingToolCallback} so the turn loop (idle timer, failover replay gate) sees the tool calls.
     */
    static final ThreadLocal<com.agenticai.core.agents.turn.TurnToolActivity> TURN_ACTIVITY_SCOPE =
            new ThreadLocal<>();

    /** One agent's resolved runtime settings, for the thread that resolves that agent's tools for a turn. */
    record TurnRuntimeScope(UUID agentId, com.agenticai.core.agents.runtime.AgentRuntimeSettings settings) {}

    /**
     * The in-flight turn's resolved runtime settings (tool-step ceiling, tool timeout, tool-output cap, ...)
     * and the agent they belong to, visible only while {@link AgentToolProvider#resolveToolsFor(
     * com.agenticai.core.agents.turn.TurnContext, UUID, String, String, String, ChatClient,
     * AgentStepEventEmitter, com.agenticai.core.agents.core.ReasoningDepth)} resolves the turn's tools on the
     * calling thread; the same single-thread hand-off as {@link #TURN_APPROVAL_SCOPE}. {@link #guardAndLog}
     * uses it only for a tool of that same agent (a delegated agent's tools resolve under its own settings)
     * and otherwise asks the resolver, which is also what the tool resolution outside a turn does (approval
     * replay, edge execution).
     */
    static final ThreadLocal<TurnRuntimeScope> TURN_RUNTIME_SCOPE = new ThreadLocal<>();

    /**
     * Collision-free per-turn id: a random UUID bound (by identity, weakly held so it dies with the
     * turn) to the turn's shared step counter. Replaces identityHashCode, which can collide between
     * turns.
     */
    private static final java.util.Map<AtomicInteger, String> TURN_IDS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    static String turnIdFor(AtomicInteger stepCounter) {
        return TURN_IDS.computeIfAbsent(stepCounter, k -> UUID.randomUUID().toString());
    }

    /** Nullable; the durable step journal when wired (used by the swarm tool), else null. */
    StepJournal stepJournal() {
        return governance != null ? governance.stepJournal() : null;
    }

    ToolCallback guardAndLog(
            ToolCallback raw,
            UUID agentId,
            String sessionId,
            AtomicInteger stepCounter,
            String userMessage,
            ChatClient chatClient,
            boolean rigorous) {
        // Captured HERE, synchronously, on the request thread that's calling
        // resolveToolsFor - the actual ToolCallback.call() invocations below
        // happen later, driven by Spring AI's reactive ChatClient.stream(),
        // on a different thread that never inherits this plain ThreadLocal.
        // See TenantContextPropagatingToolCallback's javadoc for the full
        // story (a real cross-tenant bug found during Stage C's regression
        // pass, not introduced by Stage C).
        TenantContext capturedContext = TenantContext.current().orElse(null);
        // Data-governance audit fix (2026-07-20) - same synchronous, correct-thread capture
        // point as capturedContext just above (deliberately not re-read inside
        // LoggingToolCallback.call() itself, to avoid any hidden dependency on
        // TenantContextPropagatingToolCallback's wrapping order for correctness). See
        // extractMcpGrantId's own javadoc and TenantContext#getExternalTriggerId's javadoc.
        UUID mcpGrantId = extractMcpGrantId(capturedContext);
        // #815 Governance follow-up - resolved once per tool, same as every
        // other per-agent signal here (rigorous, chatClient, userMessage);
        // AgentService.getAutonomyTier is the same cheap read-only
        // projection pattern as getReasoningDepth, not a full entity load.
        AgentAutonomyTier autonomyTier = agentService.getAutonomyTier(agentId);
        // The agent's runtime limits (tool steps, tool timeout, tool-output cap): the turn's own settings
        // when this tool belongs to the turn's agent, else resolved for the agent; null (hand-built
        // fixtures, or a resolver that failed) = the guard's own settings, as before the resolver existed.
        com.agenticai.core.agents.runtime.AgentRuntimeSettings runtime = runtimeSettings(agentId);
        // Agent lifecycle hooks (app.agent-hooks.enabled, default off). Null unless the flag is on
        // AND a hook applies
        // to this tool; with null every expression below is exactly what it was before hooks
        // existed. Hooks only
        // tighten: block / require_approval fold into the dispatch gate and the mode (stricter-of,
        // never looser),
        // arguments hooks sit INSIDE the guard around the raw tool, result hooks AROUND the guard
        // inside logging.
        AgentToolHooks hooks =
                governance != null
                        ? governance.toolHooks(raw, agentId, sessionId, stepCounter)
                        : null;
        ApprovalGateMode gateMode =
                approvalGateModeFor(autonomyTier, raw.getToolDefinition().name(), agentId);
        // Only the mode that reads a read-only verdict pays for the extra classification lookup that makes it.
        ToolDispatchGate dispatchGate =
                governance != null
                        ? governance.dispatchGate(
                                agentId, gateMode == ApprovalGateMode.GATE_UNLESS_READ_ONLY)
                        : null;
        if (hooks != null) {
            gateMode = hooks.tighten(gateMode);
            dispatchGate = hooks.gate(dispatchGate);
        }
        GuardedToolCallback core =
                new GuardedToolCallback(
                        hooks != null ? hooks.inner(raw) : raw,
                        stepCounter,
                        runtime != null ? runtime.maxToolSteps() : effectiveMaxStepsFor(autonomyTier),
                        guardrailService,
                        approvalPolicyService,
                        approvalGateService,
                        agentId,
                        sessionId,
                        toolCallVerifierService.getIfAvailable(),
                        rigorous,
                        chatClient,
                        userMessage,
                        gateMode,
                        issueService.getIfAvailable(),
                        tenantGuardrailRuleEvaluator.getIfAvailable(),
                        procedureGuardEvaluator.getIfAvailable(),
                        dispatchGate,
                        governance != null ? governance.meterRegistry() : null);
        // The turn's approval state and the operator's park texts (null = the module defaults): the
        // state is what lets the turn loop see that the turn parked and the replay envelope carry the
        // turn's scoped memory id.
        core.withTurnApproval(TURN_APPROVAL_SCOPE.get());
        if (approvalPark != null) {
            core.withApprovalParkTexts(approvalPark.getIfAvailable());
        }
        // Dispatch plumbing can never be replayed by an approval (replay resolves tools by exact
        // name without deferral), so a post-park call to one is refused without an approval row.
        core.withQueueExcludedTools(QUEUE_EXCLUDED_TOOLS);
        core.withStepLimits(stepLimits(runtime));
        core.withPrompts(prompts());
        if (governance != null && gateMode == ApprovalGateMode.GATE_UNLESS_READ_ONLY) {
            core.withReadOnlyAutoAllowAudit(governance.readOnlyAutoAllowAudit());
        }
        if (rigorous) {
            core.withConversationTail(conversationTail());
        }
        core.withOutputLimits(outputLimits(runtime));
        // Untrusted-content taint gate (app.agents.taint.mode). Null when the layer is absent or off,
        // which leaves the chain exactly as it was; the TurnTaint behind it is shared by every tool
        // of this turn through the step counter.
        if (governance != null) {
            core.withTaintGate(governance.taintGate(stepCounter, agentId, sessionId));
        }
        ToolCallback guarded = core;
        // Per-tool argument policies (app.tool-arg-policies.enabled, default off): refuses a call
        // whose arguments
        // break a declared constraint BEFORE the approval gate and the tool see it; the refusal is
        // an ordinary
        // tool result, so LoggingToolCallback below records it. With the flag off this is the
        // identical object.
        if (governance != null) {
            ToolCallback withPolicy =
                    governance.withToolArgPolicy(
                            guarded,
                            agentId,
                            sessionId,
                            stepCounter,
                            runtime != null ? runtime.maxToolSteps() : effectiveMaxStepsFor(autonomyTier));
            if (withPolicy != null) {
                if (withPolicy instanceof com.agenticai.core.guardrails.toolargs.ToolArgPolicyCallback policy) {
                    // A refusal spends the turn's step budget; past the ceiling it answers like the guard does.
                    policy.withStepLimits(stepLimits(runtime));
                    policy.withPrompts(prompts());
                }
                guarded = withPolicy;
            }
        }
        // The same redactor the model-facing result goes through, built once: the tool ledger masks what it
        // records with it, because the ledger is replayed to the model on later turns.
        java.util.function.UnaryOperator<String> outputRedactor =
                governance != null ? governance.outputRedactorFor(capturedContext) : null;
        ToolCallback logged =
                new LoggingToolCallback(
                        hooks != null ? hooks.outer(guarded) : guarded,
                        toolCallLogRepository,
                        agentId,
                        sessionId,
                        issueService,
                        toolCircuitBreakerService,
                        mcpGrantId,
                        governance != null ? governance.effectJournal() : null,
                        governance != null ? governance::isDurableTool : null,
                        // Per-turn id: stepCounter is shared by every tool of one chat turn, so a
                        // model
                        // re-issuing the identical write call inside the turn maps to the same key.
                        turnIdFor(stepCounter),
                        governance != null ? governance.meterRegistry() : null)
                        .withOutputLimits(outputLimits(runtime))
                        .withToolTimeout(
                                runtime != null && runtime.hasToolTimeout() ? runtime.toolTimeout() : null,
                                toolTimeoutMessage())
                        .withActivity(TURN_ACTIVITY_SCOPE.get())
                        .withPrompts(prompts())
                        .withLedger(toolLedgerForTenant(), TURN_APPROVAL_SCOPE.get(), outputRedactor);
        // PII redaction of tool output (2026-09-28) runs in the outermost
        // model-facing position that still has the tenant scope restored, so
        // what the provider receives - success or friendly failure alike - is
        // what the tenant's LLM-gateway policy allows. The tool-call log
        // (inside) keeps the unredacted output, same as it keeps the
        // unredacted user message.
        ToolCallback propagating =
                new TenantContextPropagatingToolCallback(
                        logged,
                        capturedContext,
                        outputRedactor);
        // Opt-in step-event streaming (2026-08-17). Read on THIS thread,
        // synchronously, for the same reason capturedContext above is - see
        // STEP_EVENT_SCOPE's javadoc. Almost every turn takes the early return:
        // the scope is only ever set by the six-arg resolveToolsFor, which only
        // AgentChatService#chat calls, and only when ChatRequest#stepEvents was
        // true. When it is unset the returned chain is the exact same object
        // graph as before this feature existed - not an equivalent one - which
        // is what guarantees the SSE stream stays byte-identical for the LiveKit
        // voice bridge and every omnichannel adapter (see AgentStepEventEmitter).
        AgentStepEventEmitter stepEvents = STEP_EVENT_SCOPE.get();
        if (stepEvents == null || !stepEvents.isEnabled()) {
            return propagating;
        }
        // MCP Apps (app.mcp.apps.enabled): null with the flag off, which keeps the event
        // byte-identical.
        com.agenticai.core.agents.tools.mcpapps.McpAppsService apps =
                mcpApps == null ? null : mcpApps.getIfAvailable();
        McpAppStepHook appHook =
                apps == null ? null : apps.hookFor(capturedContext, agentId, sessionId);
        return new StepEventToolCallback(propagating, stepEvents, appHook, TURN_APPROVAL_SCOPE.get());
    }

    /**
     * {@code app.chat.tool-ledger.*} (setter-injected; null in hand-built fixtures = the module defaults,
     * which record). Null limits from {@link #toolLedger()} would turn the ledger off.
     */
    private volatile ObjectProvider<com.agenticai.core.agents.turn.ToolLedgerProperties> toolLedger;

    void setToolLedger(ObjectProvider<com.agenticai.core.agents.turn.ToolLedgerProperties> provider) {
        this.toolLedger = provider;
    }

    /** The settings catalog (setter-injected; null in hand-built fixtures = the ledger is recorded as configured). */
    private volatile ObjectProvider<com.agenticai.core.settings.SettingsResolver> settings;

    void setSettings(ObjectProvider<com.agenticai.core.settings.SettingsResolver> provider) {
        this.settings = provider;
    }

    /**
     * The ledger limits for the calling thread's tenant ({@code chat.toolLedger.*} over {@code
     * app.chat.tool-ledger.*}): a tenant that switched the record off gets a guard whose ledger is off.
     */
    com.agenticai.core.agents.turn.ToolLedgerProperties toolLedgerForTenant() {
        ObjectProvider<com.agenticai.core.settings.SettingsResolver> provider = settings;
        return com.agenticai.core.agents.memory.ToolLedgerOverrides.effective(
                toolLedger(), provider == null ? null : provider.getIfAvailable());
    }

    com.agenticai.core.agents.turn.ToolLedgerProperties toolLedger() {
        ObjectProvider<com.agenticai.core.agents.turn.ToolLedgerProperties> provider = toolLedger;
        return provider == null
                ? new com.agenticai.core.agents.turn.ToolLedgerProperties()
                : provider.getIfAvailable(com.agenticai.core.agents.turn.ToolLedgerProperties::new);
    }

    /** Park texts and limits (setter-injected; null in hand-built fixtures = the module defaults). */
    private volatile ObjectProvider<ApprovalParkProperties> approvalPark;

    void setApprovalPark(ObjectProvider<ApprovalParkProperties> provider) {
        this.approvalPark = provider;
    }

    /** The prompt registry (setter-injected; null in hand-built fixtures = the shipped wording). */
    private volatile ObjectProvider<com.agenticai.core.prompts.PromptResolver> promptResolver;

    void setPromptResolver(ObjectProvider<com.agenticai.core.prompts.PromptResolver> provider) {
        this.promptResolver = provider;
    }

    /** Where the RIGOROUS verifier reads the end of the conversation (setter-injected; null = the current message alone). */
    private volatile ObjectProvider<com.agenticai.core.guardrails.ConversationTailSource> conversationTailSource;

    void setConversationTailSource(ObjectProvider<com.agenticai.core.guardrails.ConversationTailSource> provider) {
        this.conversationTailSource = provider;
    }

    private com.agenticai.core.guardrails.ConversationTailSource conversationTail() {
        ObjectProvider<com.agenticai.core.guardrails.ConversationTailSource> provider = conversationTailSource;
        return provider == null ? null : provider.getIfAvailable();
    }

    /** The prompt registry for text a sibling wrapper builds (null in hand-built fixtures = the shipped wording). */
    com.agenticai.core.prompts.PromptResolver promptResolver() {
        return prompts();
    }

    private com.agenticai.core.prompts.PromptResolver prompts() {
        ObjectProvider<com.agenticai.core.prompts.PromptResolver> provider = promptResolver;
        return provider == null ? null : provider.getIfAvailable();
    }

    /**
     * {@code app.guardrails.step-limit.*}: what a call past the turn's ceiling gets, and how the autonomy
     * tiers scale the ceiling (setter-injected; null in hand-built fixtures = the module defaults).
     */
    private volatile ObjectProvider<ToolStepLimitProperties> stepLimitProperties;

    void setStepLimitProperties(ObjectProvider<ToolStepLimitProperties> provider) {
        this.stepLimitProperties = provider;
    }

    /**
     * {@code app.tools.output.*}: the one cap on a tool result (setter-injected; null in hand-built
     * fixtures = the module defaults).
     */
    private volatile ObjectProvider<ToolOutputProperties> outputProperties;

    void setOutputProperties(ObjectProvider<ToolOutputProperties> provider) {
        this.outputProperties = provider;
    }

    /**
     * The one resolver of the per-tenant / per-agent runtime limits (setter-injected; null in hand-built
     * fixtures = every agent runs under the guard's own constructor settings, as before the resolver existed).
     */
    private volatile ObjectProvider<com.agenticai.core.agents.runtime.AgentRuntime> runtimeResolver;

    void setRuntimeResolver(
            ObjectProvider<com.agenticai.core.agents.runtime.AgentRuntime> provider) {
        this.runtimeResolver = provider;
    }

    /** The module defaults, shared by every guard built without the beans (hand-built fixtures). */
    private static final ToolStepLimitProperties DEFAULT_STEP_LIMITS = new ToolStepLimitProperties();

    private static final ToolOutputProperties DEFAULT_OUTPUT_LIMITS = new ToolOutputProperties();

    private ToolStepLimitProperties stepLimits() {
        ObjectProvider<ToolStepLimitProperties> provider = stepLimitProperties;
        ToolStepLimitProperties configured = provider == null ? null : provider.getIfAvailable();
        return configured != null ? configured : DEFAULT_STEP_LIMITS;
    }

    /**
     * The step limits of a turn: the platform's, with the grace the turn's runtime settings resolved for the tenant
     * and the agent ({@code agent.steps.graceCalls}) when they carry one.
     */
    private ToolStepLimitProperties stepLimits(com.agenticai.core.agents.runtime.AgentRuntimeSettings runtime) {
        ToolStepLimitProperties base = stepLimits();
        return runtime != null && runtime.hasGraceCalls() && runtime.graceCalls() != base.getGraceCalls()
                ? base.withGraceCalls(runtime.graceCalls())
                : base;
    }

    private ToolOutputProperties outputLimits() {
        ObjectProvider<ToolOutputProperties> provider = outputProperties;
        ToolOutputProperties configured = provider == null ? null : provider.getIfAvailable();
        return configured != null ? configured : DEFAULT_OUTPUT_LIMITS;
    }

    /** The tool-output cap for a turn of the agent: the agent's own default cap with the operator's per-tool caps kept. */
    private ToolOutputProperties outputLimits(com.agenticai.core.agents.runtime.AgentRuntimeSettings runtime) {
        ToolOutputProperties base = outputLimits();
        return runtime == null ? base : base.withMaxChars(runtime.toolOutputMaxChars());
    }

    private com.agenticai.core.agents.runtime.AgentRuntime runtimeResolver() {
        ObjectProvider<com.agenticai.core.agents.runtime.AgentRuntime> provider = runtimeResolver;
        return provider == null ? null : provider.getIfAvailable();
    }

    private String toolTimeoutMessage() {
        com.agenticai.core.agents.runtime.AgentRuntime resolver = runtimeResolver();
        return resolver == null ? null : resolver.toolTimeoutMessage();
    }

    /**
     * The runtime settings for a tool of {@code agentId}: the turn's, when the turn being resolved is this
     * agent's; otherwise resolved now. Null when there is no resolver or it failed (the legacy settings apply).
     */
    com.agenticai.core.agents.runtime.AgentRuntimeSettings runtimeSettings(UUID agentId) {
        TurnRuntimeScope scope = TURN_RUNTIME_SCOPE.get();
        if (scope != null && scope.settings() != null && agentId != null && agentId.equals(scope.agentId())) {
            return scope.settings();
        }
        com.agenticai.core.agents.runtime.AgentRuntime resolver = runtimeResolver();
        if (resolver == null) {
            return null;
        }
        try {
            return resolver.forAgent(agentId);
        } catch (RuntimeException e) {
            log.warn("Could not resolve the runtime settings of agent {}; using the guard's own: {}", agentId, e.toString());
            return null;
        }
    }

    /**
     * MCP Apps provider (setter-injected by AgentToolProvider; null in hand-built fixtures =
     * feature off).
     */
    private volatile ObjectProvider<com.agenticai.core.agents.tools.mcpapps.McpAppsService> mcpApps;

    void setMcpApps(
            ObjectProvider<com.agenticai.core.agents.tools.mcpapps.McpAppsService> provider) {
        this.mcpApps = provider;
    }

    /**
     * Gates a delegation tool on the turn's shared per-run USD budget (see {@link
     * RunBudgetToolCallback}).
     */
    ToolCallback withRunBudget(ToolCallback tool, RunBudget budget, boolean recordUsage) {
        if (budget == null || !budget.isEnabled()) {
            return tool;
        }
        return new RunBudgetToolCallback(tool, budget, recordUsage);
    }

    /**
     * Data-governance audit fix (2026-07-20) - turns {@link TenantContext#getExternalTriggerId()}
     * into the typed {@code UUID} {@link LoggingToolCallback} persists on {@code
     * tool_call_log.mcp_grant_id}. Defensive, not authoritative: a malformed/non-UUID
     * externalTriggerId (a future non-MCP caller of this same generic seam, e.g. a workflow webhook
     * token) degrades to "not attributed" rather than ever throwing out of this per-tool-call hot
     * path - see this field's own javadoc on TenantContext.
     */
    private UUID extractMcpGrantId(TenantContext capturedContext) {
        if (capturedContext == null || capturedContext.getExternalTriggerId() == null) {
            return null;
        }
        try {
            return UUID.fromString(capturedContext.getExternalTriggerId());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * #815 Governance follow-up - see {@code AgentAutonomyTier}'s javadoc. {@code SEMI_AUTONOMOUS}
     * returns the tenant-configured {@code maxToolCallsPerTurn} unchanged (today's exact behavior);
     * {@code SUPERVISED} is tighter and {@code FULLY_AUTONOMOUS} looser, by {@code
     * app.guardrails.step-limit.supervised-divisor} / {@code supervised-min-steps} / {@code
     * autonomous-multiplier} (defaults: half, floored at 3 so a supervised agent can still complete a
     * trivial multi-step task; double). The ceiling counts CALLS across the whole turn; see {@link
     * ToolStepLimitProperties} for how it relates to the tool loop's per-invocation round cap.
     */
    private int effectiveMaxStepsFor(AgentAutonomyTier tier) {
        ToolStepLimitProperties limits = stepLimits();
        return switch (tier) {
            case SUPERVISED -> limits.supervisedCeiling(maxToolCallsPerTurn);
            case SEMI_AUTONOMOUS -> maxToolCallsPerTurn;
            case FULLY_AUTONOMOUS -> limits.autonomousCeiling(maxToolCallsPerTurn);
        };
    }

    /**
     * Tools whose classification-driven human confirmation must survive the FULLY_AUTONOMOUS
     * (NEVER_GATE) tier: the message leaves the platform to a third party and cannot be undone, so
     * "the tenant trusts this agent" does not extend to it. For these, NEVER_GATE degrades to
     * POLICY_DEFAULT, which keeps the classification confirmation gate and the tenant policy.
     */
    static final Set<String> ALWAYS_CONFIRM_TOOLS =
            Set.of("a2a_call_agent", "schedule_task", "coding_task", "local_shell");

    /**
     * Dispatch plumbing a post-park queue must never file an approval request for: approval replay
     * resolves tools by exact name without deferral, so an approved {@code search_tools} or {@code
     * call_tool} resolves to a refusal - recording one only pollutes the approver's inbox.
     * {@code restore_tool_result} is a context-compaction placeholder that likewise cannot be
     * replayed meaningfully.
     */
    static final Set<String> QUEUE_EXCLUDED_TOOLS =
            Set.of(SearchToolsToolCallback.NAME, CallToolToolCallback.NAME, "restore_tool_result");

    /**
     * Tool-aware mapping: as {@link #approvalGateModeFor(AgentAutonomyTier)} but never NEVER_GATE
     * for {@link #ALWAYS_CONFIRM_TOOLS}.
     */
    ApprovalGateMode approvalGateModeFor(AgentAutonomyTier tier, String toolName) {
        return approvalGateModeFor(tier, toolName, null);
    }

    /**
     * As above, for one agent's tool. {@code SUPERVISED} is {@code ALWAYS_GATE} (every call asks a human) unless {@code
     * app.guardrails.supervised.gate-read-only} resolves to false for this agent AND the agent is positively judged not
     * to hold the lethal trifecta ({@link AgentToolGovernance#supervisedAutoAllowsReadOnly}); then it is {@code
     * GATE_UNLESS_READ_ONLY}, which skips the human only for a call the dispatch gate vouches for as an explicitly
     * classified read-only tool. With no governance wired, no agent id or any doubt, the answer is {@code ALWAYS_GATE}.
     */
    ApprovalGateMode approvalGateModeFor(AgentAutonomyTier tier, String toolName, UUID agentId) {
        // search_tools and call_tool are dispatch plumbing, never gated themselves: a parked call
        // could not be
        // replayed (approval replay resolves tools by exact name without deferral, so neither
        // exists there) and
        // the deferred target already carries its own guard chain and approval gate, so gating the
        // dispatcher
        // would ask a human twice. The classification dispatch gate (DENY) still runs on the
        // wrapper.
        if (SearchToolsToolCallback.NAME.equals(toolName)
                || CallToolToolCallback.NAME.equals(toolName)) {
            return ApprovalGateMode.NEVER_GATE;
        }
        ApprovalGateMode mode = approvalGateModeFor(tier);
        if (mode == ApprovalGateMode.ALWAYS_GATE
                && governance != null
                && governance.supervisedAutoAllowsReadOnly(agentId)) {
            mode = ApprovalGateMode.GATE_UNLESS_READ_ONLY;
        }
        // The pinned set consults the install-level shell-approval dial when governance is wired;
        // a null governance (hand-built fixture) keeps the static set exactly as before.
        boolean pinned =
                governance != null
                        ? governance.alwaysConfirm(toolName)
                        : ALWAYS_CONFIRM_TOOLS.contains(toolName);
        if (mode == ApprovalGateMode.NEVER_GATE && pinned) {
            return ApprovalGateMode.POLICY_DEFAULT;
        }
        return mode;
    }

    /**
     * #815 Governance follow-up - see {@code AgentAutonomyTier}'s javadoc for the mapping's
     * rationale.
     */
    private ApprovalGateMode approvalGateModeFor(AgentAutonomyTier tier) {
        return switch (tier) {
            case SUPERVISED -> ApprovalGateMode.ALWAYS_GATE;
            case SEMI_AUTONOMOUS -> ApprovalGateMode.POLICY_DEFAULT;
            case FULLY_AUTONOMOUS -> ApprovalGateMode.NEVER_GATE;
        };
    }
}
