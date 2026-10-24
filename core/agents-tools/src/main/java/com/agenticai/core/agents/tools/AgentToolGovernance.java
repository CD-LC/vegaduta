package com.agenticai.core.agents.tools;

import com.agenticai.core.agents.tools.classification.ToolClassification;
import com.agenticai.core.agents.tools.classification.ToolClassificationDecision;
import com.agenticai.core.agents.tools.classification.ToolClassificationRegistry;
import com.agenticai.core.chat.gateway.LlmGatewayToolOutputRedactor;
import com.agenticai.core.guardrails.ToolDispatchGate;
import com.agenticai.core.guardrails.toolargs.ToolArgPolicyCallback;
import com.agenticai.core.guardrails.toolargs.ToolArgPolicySource;
import com.agenticai.core.security.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The per-call governance collaborators {@link AgentToolGuard} threads into every guarded tool
 * chain (2026-09-28): the tool-classification dispatch gate, the meter registry for {@code
 * agent.tool.calls}, and the tenant's LLM-gateway PII redaction for tool output. Bundled into one
 * bean so the guard's already-long constructor gains a single optional parameter rather than three.
 *
 * <h2>Classification enforcement - what is refused and what is not</h2>
 *
 * {@link ToolClassificationRegistry#resolve} is consulted before every guarded call (inside the
 * turn's restored tenant scope, so a tenant's own {@code tool_classification} rows are what
 * resolve):
 *
 * <ul>
 *   <li><b>QUARANTINED</b> or <b>INVALID_DECLARATION</b> - refused. Both are a decision somebody
 *       made (or a row nobody can parse) that the tool must not run until a human clears it.
 *   <li><b>UNCLASSIFIED</b> - allowed and counted ({@value #UNCLASSIFIED_ALLOWED_METRIC}). The
 *       registry treats this as blocked by design, but most MCP and tenant-authored tools have
 *       never been classified; refusing them all at once would break every such agent. The counter
 *       is what tells an operator when it is safe to flip.
 *   <li><b>Classified and {@link ToolClassification#requiresHumanConfirmation()}</b> (irreversible
 *       AND consequential) - routed to the existing human approval/park gate ({@value
 *       #CONFIRMATION_ENFORCED_METRIC}) unless {@code
 *       app.tools.classification.confirmation-gate=false} (opt-out; default {@code true} since
 *       2026-10-01). Tools named in {@code app.tools.classification.confirmation-gate-exempt-tools}
 *       (default {@code channel_send_message}, {@code run_code}, {@code run_in_code_workspace},
 *       {@code browser_click}, {@code browser_type}, {@code google_gmail}, {@code google_calendar}:
 *       unattended responders, sandboxes, browser agents and multi-action tools that would stall on
 *       every call). Agents on the NEVER_GATE autonomy tier are exempted by {@code
 *       GuardedToolCallback} itself) and every call with the gate off are only counted ({@value
 *       #CONFIRMATION_SHADOW_METRIC}). The exempt list is for ORDINARY agents: an agent that
 *       currently holds the lethal trifecta loses the exemption ({@link
 *       TrifectaEnforcementService#exemptionHolds}, behind {@code
 *       app.tools.classification.trifecta-overrides-exempt}).
 * </ul>
 *
 * A lookup failure (the registry throws) is handled by {@link
 * com.agenticai.core.guardrails.GuardrailFailMode}: refused when closed (the default), allowed when
 * open. A tool with no classification row follows {@code
 * app.tools.classification.unclassified-action}: {@code allow} (default, counted), {@code
 * require-approval} or {@code deny}.
 */
@Component
public class AgentToolGovernance implements ToolReplaySafety {

    private static final Logger log = LoggerFactory.getLogger(AgentToolGovernance.class);

    static final String UNCLASSIFIED_ALLOWED_METRIC =
            "agent.tool.classification.unclassified_allowed";
    static final String CONFIRMATION_SHADOW_METRIC =
            "agent.tool.classification.confirmation_shadow";
    static final String CONFIRMATION_ENFORCED_METRIC =
            "agent.tool.classification.confirmation_enforced";
    static final String REFUSED_METRIC = "agent.tool.classification.refused";

    private final ObjectProvider<ToolClassificationRegistry> registry;
    private final ObjectProvider<MeterRegistry> meterRegistry;
    private final ObjectProvider<LlmGatewayToolOutputRedactor> toolOutputRedactor;
    private final boolean confirmationGate;

    /** What a tool with no classification row gets; see {@link UnclassifiedAction}. */
    private final UnclassifiedAction unclassifiedAction;

    /**
     * {@code allow} keeps today's rollout-friendly behaviour (the call runs and is counted). {@code
     * require-approval} and {@code deny} are the fail-closed settings: a new or third-party tool
     * cannot act until someone classifies it.
     */
    enum UnclassifiedAction {
        ALLOW,
        REQUIRE_APPROVAL,
        DENY;

        static UnclassifiedAction parse(String raw) {
            if (raw == null) {
                return ALLOW;
            }
            return switch (raw.trim().toLowerCase(java.util.Locale.ROOT).replace('_', '-')) {
                case "require-approval", "approval" -> REQUIRE_APPROVAL;
                case "deny", "block" -> DENY;
                default -> ALLOW;
            };
        }
    }

    /** Tools that stay allow-and-count even with the gate on (unattended responders, sandboxes). */
    private final Set<String> confirmationExempt;

    /**
     * {@code app.assistant.local-shell.approval-required} (default {@code true}): when {@code
     * false}, the install's owner has decided the sandboxed terminal no longer parks per command -
     * {@code local_shell} then behaves like an ordinary confirmed-once tool: it drops out of the
     * always-confirm pin AND the classification confirmation gate. The OS sandbox/workspace-runner
     * boundary, the tool-call log and {@code SUPERVISED}'s ALWAYS_GATE tier are untouched.
     */
    private final boolean localShellApprovalRequired;

    /**
     * {@code app.assistant.desktop.approval-required} (default {@code true}): the same dial for the
     * {@code local_desktop_*} actuating tools ({@code DesktopToolSource.GATED_NAMES} - press, click,
     * type, key, open, activate). Unlike the shell these are NOT sandboxed, so the pin exists on
     * every profile by default; {@code false} lets them run like ordinary unconfirmed tools while
     * SUPERVISED still gates. Read-side tools (screen, elements, clipboard, notify) are never
     * pinned by either mechanism.
     */
    private final boolean desktopApprovalRequired;

    /**
     * Set once an {@link AgentToolGuard} actually holds this bean - the honest answer to "is the
     * dispatch path consulting the registry?", which is what {@code ToolClassificationController}'s
     * {@code enforced} field reports.
     */
    private final AtomicBoolean wiredIntoDispatch = new AtomicBoolean(false);

    public AgentToolGovernance(
            ObjectProvider<ToolClassificationRegistry> registry,
            ObjectProvider<MeterRegistry> meterRegistry,
            ObjectProvider<LlmGatewayToolOutputRedactor> toolOutputRedactor,
            boolean confirmationGate,
            String exemptTools) {
        this(registry, meterRegistry, toolOutputRedactor, confirmationGate, exemptTools, "allow");
    }

    public AgentToolGovernance(
            ObjectProvider<ToolClassificationRegistry> registry,
            ObjectProvider<MeterRegistry> meterRegistry,
            ObjectProvider<LlmGatewayToolOutputRedactor> toolOutputRedactor,
            boolean confirmationGate,
            String exemptTools,
            String unclassifiedAction) {
        this(
                registry,
                meterRegistry,
                toolOutputRedactor,
                confirmationGate,
                exemptTools,
                unclassifiedAction,
                true,
                true);
    }

    @Autowired
    public AgentToolGovernance(
            ObjectProvider<ToolClassificationRegistry> registry,
            ObjectProvider<MeterRegistry> meterRegistry,
            ObjectProvider<LlmGatewayToolOutputRedactor> toolOutputRedactor,
            @Value("${app.tools.classification.confirmation-gate:true}") boolean confirmationGate,
            @Value(
                            "${app.tools.classification.confirmation-gate-exempt-tools:channel_send_message,run_code,run_in_code_workspace,browser_click,browser_type,google_gmail,google_calendar}")
                    String exemptTools,
            @Value("${app.tools.classification.unclassified-action:allow}")
                    String unclassifiedAction,
            @Value("${app.assistant.local-shell.approval-required:true}")
                    boolean localShellApprovalRequired,
            @Value("${app.assistant.desktop.approval-required:true}")
                    boolean desktopApprovalRequired) {
        this.unclassifiedAction = UnclassifiedAction.parse(unclassifiedAction);
        this.registry = registry;
        this.meterRegistry = meterRegistry;
        this.toolOutputRedactor = toolOutputRedactor;
        this.confirmationGate = confirmationGate;
        this.localShellApprovalRequired = localShellApprovalRequired;
        this.desktopApprovalRequired = desktopApprovalRequired;
        Set<String> exempt = new HashSet<>();
        if (exemptTools != null) {
            for (String name : exemptTools.split(",")) {
                if (!name.isBlank()) {
                    exempt.add(name.trim());
                }
            }
        }
        this.confirmationExempt = Set.copyOf(exempt);
    }

    /**
     * The always-confirm pin, minus the one carve-out the operator can make: {@code local_shell}
     * when {@code app.assistant.local-shell.approval-required=false}. Everything else on {@code
     * AgentToolGuard.ALWAYS_CONFIRM_TOOLS} (a2a calls, schedule_task, coding_task) stays pinned on
     * every install.
     */
    boolean alwaysConfirm(String toolName) {
        if (com.agenticai.core.agents.tools.desktop.DesktopToolSource.GATED_NAMES.contains(
                toolName)) {
            return desktopApprovalRequired;
        }
        if (!AgentToolGuard.ALWAYS_CONFIRM_TOOLS.contains(toolName)) {
            return false;
        }
        return localShellApprovalRequired
                || !com.agenticai.core.agents.tools.localshell.LocalShellToolCallback.RUN_NAME
                        .equals(toolName);
    }

    /** True once the tool dispatch path consults the classification registry. */
    public boolean isEnforcing() {
        return wiredIntoDispatch.get() && registry.getIfAvailable() != null;
    }

    void markWiredIntoDispatch() {
        wiredIntoDispatch.set(true);
    }

    private volatile TrifectaEnforcementService trifectaEnforcement;

    /**
     * Optional: absent (slim/test contexts) means the exempt list applies to every agent exactly as
     * before.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setTrifectaEnforcement(TrifectaEnforcementService trifectaEnforcement) {
        this.trifectaEnforcement = trifectaEnforcement;
    }

    private volatile com.agenticai.core.guardrails.GuardrailTuningProperties guardrailTuning;

    /**
     * Optional: the platform value of {@code app.guardrails.supervised.gate-read-only}. Absent (slim/test contexts)
     * means the shipped default, {@code true}: a SUPERVISED agent is gated on every call.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setGuardrailTuning(com.agenticai.core.guardrails.GuardrailTuningProperties guardrailTuning) {
        this.guardrailTuning = guardrailTuning;
    }

    private volatile ObjectProvider<com.agenticai.core.audit.AuditLogService> auditLog;

    /** Optional: where a read-only call let through under SUPERVISED is audited. Absent means it is logged and journaled only. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAuditLog(ObjectProvider<com.agenticai.core.audit.AuditLogService> auditLog) {
        this.auditLog = auditLog;
    }

    /**
     * Whether a SUPERVISED agent's explicitly classified read-only tool calls run without a human
     * ({@code app.guardrails.supervised.gate-read-only=false}, as the platform, the tenant and the agent
     * resolve it). True only when ALL of these hold; any doubt answers false, which is the gate every SUPERVISED
     * call has always had:
     * <ul>
     *   <li>the setting says so for this agent (a tenant or agent can only turn it back to gated);
     *   <li>the classification registry is present, so a tool can be judged at all;
     *   <li>the lethal-trifecta detector positively judged the agent as not holding the trifecta
     *       ({@link TrifectaEnforcementService#definitelyNotLethal}); an agent that does, or that cannot be judged,
     *       keeps full gating.
     * </ul>
     * Whether the particular CALL qualifies (its tool's own classification) is decided per call by the dispatch gate
     * built with {@code markReadOnly}; nothing else about the call is relaxed.
     */
    boolean supervisedAutoAllowsReadOnly(UUID agentId) {
        if (agentId == null || registry.getIfAvailable() == null) {
            return false;
        }
        com.agenticai.core.guardrails.GuardrailTuningProperties tuning = guardrailTuning;
        if (tuning == null || com.agenticai.core.guardrails.GuardrailSettings.supervisedGatesReadOnly(tuning, agentId)) {
            return false;
        }
        TrifectaEnforcementService trifecta = trifectaEnforcement;
        return trifecta != null && trifecta.definitelyNotLethal(agentId);
    }

    /**
     * Reports a read-only call let through under SUPERVISED to the audit log ({@link
     * com.agenticai.core.guardrails.ReadOnlyAutoAllow}); the sink handed to {@code GuardedToolCallback}. Never throws.
     */
    com.agenticai.core.guardrails.ReadOnlyAutoAllow.Audit readOnlyAutoAllowAudit() {
        return (toolName, agentId, sessionId, detail) -> {
            ObjectProvider<com.agenticai.core.audit.AuditLogService> provider = auditLog;
            com.agenticai.core.audit.AuditLogService service = provider == null ? null : provider.getIfAvailable();
            if (service == null) {
                return;
            }
            try {
                // audit_log_entry.target_id is varchar(128); an MCP tool name can exceed it.
                String target = toolName != null && toolName.length() > AUDIT_TARGET_MAX
                        ? toolName.substring(0, AUDIT_TARGET_MAX) : toolName;
                service.record("agent-supervised",
                        com.agenticai.core.guardrails.ReadOnlyAutoAllow.AUDIT_ACTION, "tool", target,
                        com.agenticai.core.audit.AuditOutcome.AUTO_APPROVED,
                        detail + " (agent " + agentId + ", session " + sessionId + ")");
            } catch (RuntimeException e) {
                log.warn("Could not record the supervised read-only audit entry for tool '{}'", toolName, e);
            }
        };
    }

    private static final int AUDIT_TARGET_MAX = 120;

    /** Nullable. */
    MeterRegistry meterRegistry() {
        return meterRegistry.getIfAvailable();
    }

    private volatile EffectJournal effectJournal;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setEffectJournal(EffectJournal effectJournal) {
        this.effectJournal = effectJournal;
    }

    /** Nullable; null when idempotency journaling is unavailable. */
    EffectJournal effectJournal() {
        return effectJournal;
    }

    private volatile StepJournal stepJournal;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setStepJournal(StepJournal stepJournal) {
        this.stepJournal = stepJournal;
    }

    /** Nullable; null when the durable step journal bean is unavailable. */
    StepJournal stepJournal() {
        return stepJournal;
    }

    private volatile com.agenticai.core.agents.hooks.AgentHookRuntime hookRuntime;

    /**
     * Optional: agent lifecycle hooks (app.agent-hooks, default off). Absent bean or flag off = the
     * guard builds the chain it always did.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setHookRuntime(com.agenticai.core.agents.hooks.AgentHookRuntime hookRuntime) {
        this.hookRuntime = hookRuntime;
    }

    /** Nullable; null when hooks are off or none applies to this tool. */
    com.agenticai.core.agents.hooks.AgentToolHooks toolHooks(
            org.springframework.ai.tool.ToolCallback raw,
            UUID agentId,
            String sessionId,
            java.util.concurrent.atomic.AtomicInteger stepCounter) {
        com.agenticai.core.agents.hooks.AgentHookRuntime runtime = hookRuntime;
        if (runtime == null || !runtime.isEnabled()) {
            return null;
        }
        return runtime.toolHooks(
                raw, agentId, sessionId, stepCounter, AgentToolGuard.STEP_EVENT_SCOPE.get());
    }

    private volatile AgentTaintPolicy taintPolicy;

    /**
     * Optional: the untrusted-content taint layer ({@code app.agents.taint.mode}). Absent bean
     * (slim/test contexts) means no taint gate, exactly as before it existed.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setTaintPolicy(AgentTaintPolicy taintPolicy) {
        this.taintPolicy = taintPolicy;
    }

    /** The turn's taint gate for one tool, or {@code null} when the layer is absent or off. */
    com.agenticai.core.guardrails.taint.TaintGate taintGate(
            java.util.concurrent.atomic.AtomicInteger stepCounter, UUID agentId, String sessionId) {
        AgentTaintPolicy policy = taintPolicy;
        return policy == null ? null : policy.gateFor(stepCounter, agentId, sessionId);
    }

    private volatile ToolArgPolicySource toolArgPolicySource;

    /**
     * Optional: absent (slim/test contexts) means no argument policies, exactly as before they
     * existed.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setToolArgPolicySource(ToolArgPolicySource toolArgPolicySource) {
        this.toolArgPolicySource = toolArgPolicySource;
    }

    /**
     * Wraps {@code inner} with the agent's per-tool argument policies (see {@link
     * ToolArgPolicyCallback}), or returns {@code inner} itself - the identical object - when the
     * feature is off or no source bean exists. Tighten-only: it can refuse a call before {@code
     * inner} (the approval gate and everything else in the guard chain) ever sees it, never allow
     * one.
     */
    org.springframework.ai.tool.ToolCallback withToolArgPolicy(
            org.springframework.ai.tool.ToolCallback inner,
            UUID agentId,
            String sessionId,
            java.util.concurrent.atomic.AtomicInteger stepCounter,
            int maxSteps) {
        ToolArgPolicySource source = toolArgPolicySource;
        if (source == null || !source.isEnabled()) {
            return inner;
        }
        return new ToolArgPolicyCallback(inner, source, agentId, sessionId, stepCounter, maxSteps);
    }

    private static final long DURABLE_CACHE_TTL_MILLIS = 60_000L;
    private static final int DURABLE_CACHE_MAX = 2_000;

    private record DurableVerdict(boolean durable, long expiresAtMillis) {}

    private final java.util.concurrent.ConcurrentHashMap<String, DurableVerdict> durableCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * True when the tool is classified DURABLE (write/send: replay must use the journal).
     * ToolClassificationRegistry.resolve hits the database uncached, so the verdict is memoised per
     * tenant+tool for 60s (a reclassification takes effect within a minute; the dispatch gate
     * itself is not cached).
     */
    boolean isDurableTool(String toolName) {
        String tenant = TenantContext.current().map(TenantContext::getTenantId).orElse("-");
        String key = tenant + "|" + toolName;
        long now = System.currentTimeMillis();
        DurableVerdict cached = durableCache.get(key);
        if (cached != null && cached.expiresAtMillis() > now) {
            return cached.durable();
        }
        ToolClassificationRegistry resolved = registry.getIfAvailable();
        if (resolved == null) {
            return false;
        }
        boolean durable =
                resolved.resolve(toolName) instanceof ToolClassificationDecision.Classified c
                        && c.classification().determinism().replayMustUseJournaledResponse();
        if (durableCache.size() >= DURABLE_CACHE_MAX) {
            durableCache.clear();
        }
        durableCache.put(key, new DurableVerdict(durable, now + DURABLE_CACHE_TTL_MILLIS));
        return durable;
    }

    /**
     * Replay-safety verdict for the L4 verifier-escalation router, from the tool CLASSIFICATION
     * (never the tool name). {@code Optional.of(true)} = classified, not DURABLE and not
     * irreversible (re-running the turn cannot repeat a write/send); {@code Optional.of(false)} =
     * classified and unsafe; empty = unclassified/unknown or no registry (caller must fail safe). A
     * tool whose only classification is derived from MCP server annotations counts as unclassified
     * HERE: that hint is a third-party claim, and a "read-only" lie must not make a mutating tool
     * replayable.
     */
    public java.util.Optional<Boolean> replaySafeVerdict(String toolName) {
        ToolClassificationRegistry resolved = registry.getIfAvailable();
        if (resolved == null || toolName == null) {
            return java.util.Optional.empty();
        }
        if (resolved.resolveIgnoringMcpAnnotations(toolName)
                instanceof ToolClassificationDecision.Classified c) {
            ToolClassification k = c.classification();
            return java.util.Optional.of(
                    !k.determinism().replayMustUseJournaledResponse() && !k.irreversible());
        }
        return java.util.Optional.empty();
    }

    /** The classification gate, or {@code null} when there is no registry bean. */
    ToolDispatchGate dispatchGate() {
        return dispatchGate(null);
    }

    /**
     * The classification gate for one agent's tools, or {@code null} when there is no registry
     * bean. The agent id is what lets the exempt list be withheld from an agent that holds the
     * lethal trifecta.
     */
    ToolDispatchGate dispatchGate(UUID agentId) {
        return dispatchGate(agentId, false);
    }

    /**
     * As {@link #dispatchGate(UUID)}; with {@code markReadOnly} an ALLOW for a tool with an explicit read-only
     * classification is returned as {@link ToolDispatchGate.Verdict#allowReadOnly()}, which a SUPERVISED agent's
     * {@code GATE_UNLESS_READ_ONLY} mode reads. Costs one more registry lookup per such call, so only that mode asks.
     */
    ToolDispatchGate dispatchGate(UUID agentId, boolean markReadOnly) {
        ToolClassificationRegistry resolved = registry.getIfAvailable();
        if (resolved == null) {
            return null;
        }
        return toolName -> decide(resolved, toolName, agentId, markReadOnly);
    }

    /**
     * The tenant's PII redaction for tool output, or {@code null} when there is no tenant
     * (internal/system call) or no redactor bean. Runs inside {@code
     * TenantContextPropagatingToolCallback}'s restored scope.
     */
    UnaryOperator<String> outputRedactorFor(TenantContext capturedContext) {
        LlmGatewayToolOutputRedactor redactor = toolOutputRedactor.getIfAvailable();
        if (redactor == null || capturedContext == null || capturedContext.getTenantId() == null) {
            return null;
        }
        String tenantId = capturedContext.getTenantId();
        return output -> redactor.redact(tenantId, output);
    }

    ToolDispatchGate.Verdict decide(ToolClassificationRegistry resolved, String toolName) {
        return decide(resolved, toolName, null);
    }

    ToolDispatchGate.Verdict decide(
            ToolClassificationRegistry resolved, String toolName, UUID agentId) {
        return decide(resolved, toolName, agentId, false);
    }

    ToolDispatchGate.Verdict decide(
            ToolClassificationRegistry resolved, String toolName, UUID agentId, boolean markReadOnly) {
        ToolClassificationDecision decision = resolved.resolve(toolName);
        return switch (decision) {
            case ToolClassificationDecision.Blocked blocked ->
                    switch (blocked.reason()) {
                        case UNCLASSIFIED -> {
                            if (alwaysConfirm(toolName)) {
                                count(CONFIRMATION_ENFORCED_METRIC);
                                yield ToolDispatchGate.Verdict.requireApproval(
                                        "outbound agent call");
                            }
                            yield switch (unclassifiedAction) {
                                case REQUIRE_APPROVAL -> {
                                    count(REFUSED_METRIC, "reason", "UNCLASSIFIED_APPROVAL");
                                    yield ToolDispatchGate.Verdict.requireApproval(
                                            "unclassified tool");
                                }
                                case DENY -> {
                                    count(REFUSED_METRIC, "reason", "UNCLASSIFIED");
                                    yield ToolDispatchGate.Verdict.deny(
                                            "this tool has no safety classification in this workspace, so it is blocked until an "
                                                    + "administrator classifies it.");
                                }
                                case ALLOW -> {
                                    count(UNCLASSIFIED_ALLOWED_METRIC);
                                    yield ToolDispatchGate.Verdict.allow();
                                }
                            };
                        }
                        case QUARANTINED -> {
                            count(REFUSED_METRIC, "reason", "QUARANTINED");
                            log.warn("Refusing quarantined tool '{}'", toolName);
                            yield ToolDispatchGate.Verdict.deny(
                                    "this tool has been quarantined in this workspace and needs an administrator to re-approve "
                                            + "it before it can run.");
                        }
                        case INVALID_DECLARATION -> {
                            count(REFUSED_METRIC, "reason", "INVALID_DECLARATION");
                            log.warn(
                                    "Refusing tool '{}' with an invalid classification row",
                                    toolName);
                            yield ToolDispatchGate.Verdict.deny(
                                    "this tool's safety classification in this workspace is invalid, so it is blocked until an "
                                            + "administrator fixes it.");
                        }
                    };
            case ToolClassificationDecision.Classified classified -> {
                ToolClassification classification = classified.classification();
                if (!classification.requiresHumanConfirmation()) {
                    yield markReadOnly && explicitlyReadOnly(resolved, toolName)
                            ? ToolDispatchGate.Verdict.allowReadOnly()
                            : ToolDispatchGate.Verdict.allow();
                }
                // ALWAYS_CONFIRM_TOOLS (a2a_call_agent: durable, egress, irreversible) are gated
                // even
                // when the classification gate is shadow-only or the tool is on the exempt list.
                boolean pinned = alwaysConfirm(toolName);
                boolean exempt =
                        confirmationExempt.contains(toolName)
                                || (!localShellApprovalRequired
                                        && com.agenticai.core.agents.tools.localshell
                                                .LocalShellToolCallback.RUN_NAME.equals(toolName))
                                || (!desktopApprovalRequired
                                        && com.agenticai.core.agents.tools.desktop.DesktopToolSource
                                                .GATED_NAMES.contains(toolName));
                // Only an exempt tool on a gate-on, per-agent dispatch pays for the trifecta lookup
                // (a cached
                // map read), so ordinary tools never touch it.
                if (exempt
                        && confirmationGate
                        && !pinned
                        && agentId != null
                        && !exemptionHolds(agentId)) {
                    exempt = false;
                }
                if (pinned || (confirmationGate && !exempt)) {
                    count(CONFIRMATION_ENFORCED_METRIC);
                    yield ToolDispatchGate.Verdict.requireApproval(classification.riskLabel());
                }
                count(CONFIRMATION_SHADOW_METRIC);
                yield ToolDispatchGate.Verdict.allow();
            }
        };
    }

    /**
     * True when the tool has an EXPLICIT classification row (a stored row or a compiled-in declaration) that is
     * neither consequential (write, credentialed, egress) nor irreversible, and it is not one of the tools pinned to
     * always confirm. A classification derived from an MCP server's own annotations does not count: that hint is a
     * third party's claim, and a "read-only" lie must not make a mutating tool run unattended. A tool that is
     * unclassified, quarantined or invalid is never read-only.
     */
    private boolean explicitlyReadOnly(ToolClassificationRegistry resolved, String toolName) {
        if (alwaysConfirm(toolName)) {
            return false;
        }
        return resolved.resolveIgnoringMcpAnnotations(toolName) instanceof ToolClassificationDecision.Classified c
                && isReadOnly(c.classification());
    }

    static boolean isReadOnly(ToolClassification classification) {
        return !classification.sensitivity().isConsequential() && !classification.irreversible();
    }

    private boolean exemptionHolds(UUID agentId) {
        TrifectaEnforcementService service = trifectaEnforcement;
        return service == null || service.exemptionHolds(agentId);
    }

    private void count(String name, String... tags) {
        MeterRegistry meters = meterRegistry.getIfAvailable();
        if (meters == null) {
            return;
        }
        try {
            meters.counter(name, tags).increment();
        } catch (RuntimeException e) {
            log.debug("Could not record metric {}", name, e);
        }
    }
}
