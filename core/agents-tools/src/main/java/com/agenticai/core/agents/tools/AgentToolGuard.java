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
import com.agenticai.core.guardrails.GuardedToolCallback;
import com.agenticai.core.guardrails.GuardrailService;
import com.agenticai.core.guardrails.TenantGuardrailRuleEvaluator;
import com.agenticai.core.guardrails.ToolCallVerifierService;
import com.agenticai.core.guardrails.ToolDispatchGate;
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
                approvalGateModeFor(autonomyTier, raw.getToolDefinition().name());
        ToolDispatchGate dispatchGate =
                governance != null ? governance.dispatchGate(agentId) : null;
        if (hooks != null) {
            gateMode = hooks.tighten(gateMode);
            dispatchGate = hooks.gate(dispatchGate);
        }
        GuardedToolCallback core =
                new GuardedToolCallback(
                        hooks != null ? hooks.inner(raw) : raw,
                        stepCounter,
                        effectiveMaxStepsFor(autonomyTier),
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
                            effectiveMaxStepsFor(autonomyTier));
            if (withPolicy != null) {
                guarded = withPolicy;
            }
        }
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
                        governance != null ? governance.meterRegistry() : null);
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
                        governance != null ? governance.outputRedactorFor(capturedContext) : null);
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
        return new StepEventToolCallback(propagating, stepEvents, appHook);
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
     * {@code SUPERVISED} is tighter (half, floored at 3 so a supervised agent can still complete a
     * trivial multi-step task); {@code FULLY_AUTONOMOUS} is looser (double).
     */
    private int effectiveMaxStepsFor(AgentAutonomyTier tier) {
        return switch (tier) {
            case SUPERVISED -> Math.max(3, maxToolCallsPerTurn / 2);
            case SEMI_AUTONOMOUS -> maxToolCallsPerTurn;
            case FULLY_AUTONOMOUS -> maxToolCallsPerTurn * 2;
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
     * Tool-aware mapping: as {@link #approvalGateModeFor(AgentAutonomyTier)} but never NEVER_GATE
     * for {@link #ALWAYS_CONFIRM_TOOLS}.
     */
    ApprovalGateMode approvalGateModeFor(AgentAutonomyTier tier, String toolName) {
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
