package com.agenticai.core.approval;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decides whether a given action type requires human approval -
 * the "configurable per-tool policy" decision.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>An explicit {@link ApprovalPolicyOverride} for this exact action type
 *       always wins, in either direction (an admin can both tighten and
 *       loosen the default).</li>
 *   <li>Otherwise, a small hardcoded set of default-sensitive patterns -
 *       any outbound channel send, any bulk campaign send, any delete, and
 *       any code-intel workspace run/apply (arbitrary command execution or
 *       arbitrary file writes) - requires approval out of the box, so a
 *       brand-new channel adapter (or a brand-new consequential tool like
 *       code-intel's) is gated by default even before anyone configures
 *       anything for it.</li>
 *   <li>Anything else defaults to not requiring approval (reads, internal
 *       lookups, non-destructive config changes).</li>
 * </ol>
 *
 * <p>Patterns support a single {@code *} wildcard (e.g. {@code
 * "channel.*.send"} matches {@code "channel.whatsapp.send"} and {@code
 * "channel.telegram.send"}) - intentionally simple glob matching, not a
 * regex engine, since action-type naming is fully within this codebase's
 * control and doesn't need more than that.
 */
@Service
public class ApprovalPolicyService {

    private static final List<String> DEFAULT_SENSITIVE_PATTERNS = List.of(
        "channel.*.send",
        "channel.*.auto_reply",
        "campaign.bulk_send",
        "*.delete",
        // code-intel workspace write/execute actions (core/codeintel's
        // ApplyCodeChangesToolCallback / RunInCodeWorkspaceToolCallback):
        // running an arbitrary shell command or writing arbitrary file
        // content is exactly the kind of action this class's javadoc
        // describes needing to be "gated by default even before anyone
        // configures anything for it" - a fresh install with zero
        // ApprovalPolicyOverride rows must still require a human before
        // either of these ever calls code-intel's exec endpoints. An
        // explicit override can still loosen this later (same two-way
        // resolution as every other pattern here), but the out-of-the-box
        // default must be "ask," not "allow."
        "codeintel.workspace.run",
        "codeintel.workspace.apply",
        // Payment / financial-transfer tools - defensive, not closing a live
        // gap: as of this review, core/billing (RazorpayGatewayClient,
        // PaymentGatewayClient/PaymentGatewayRegistry, InvoiceService) has
        // NO ToolCallback/@Tool binding anywhere - nothing agent-callable
        // today can charge, refund, or transfer money, so no actionType a
        // real tool call could produce is known. Still added by default
        // per this class's stated principle ("gated by default even before
        // anyone configures anything for it") so that if/when a payment
        // tool IS registered - native (its own actionType, same as
        // codeintel's two rows above) or MCP-sourced (GuardedToolCallback's
        // "mcp_tool." + toolName actionType, see that class's javadoc) -
        // it is default-gated the moment it exists, not after an incident.
        // Broad substring wildcards on purpose: no real naming convention
        // exists yet to match narrowly against, and fail-safe (occasionally
        // over-gating something unrelated with "charge" in its name) is the
        // correct bias for money-moving actions versus fail-open.
        "*payment*",
        "*refund*",
        "*charge*",
        "*transfer*",
        // Ambient sentinels (AgentSentinelSweep -> SentinelProposalExecutor):
        // a sentinel's "propose" verdict parks a follow-up agent run as an
        // approval request. Default-gated because the entire point of the
        // ambient-agent model is "agents propose, humans ratify" - an
        // ungated proposal would let a watcher schedule launch real work on
        // its own say-so. An explicit ApprovalPolicyOverride can still
        // loosen it for a tenant that trusts its sentinels.
        "agent.proposal"
        // Live Vision: "camera.capture.start" is deliberately
        // NOT in this default list. Unlike the codeintel rows above (an
        // agent acting on the world), a WATCH/LIVE vision session is the
        // USER pointing their own camera at something after an explicit
        // in-panel consent flow - default-deny here would 403 the feature
        // out of the box for every tenant. Tenants that want stricter governance
        // add an ApprovalPolicyOverride row for "camera.capture.start"
        // (requiresApproval=true); core/agents' VisionSessionService.start
        // consults requiresApproval() explicitly and refuses WATCH/LIVE
        // synchronously (403 naming the policy row) when such a row exists.
        // Any future agent-initiated camera TOOL still gets the standard
        // GuardedToolCallback gate under the same action name.
    );

    private final ApprovalPolicyOverrideRepository overrideRepository;

    public ApprovalPolicyService(ApprovalPolicyOverrideRepository overrideRepository) {
        this.overrideRepository = overrideRepository;
    }

    /**
     * Cost-transparency panel: how many default-sensitive action patterns
     * ({@link #DEFAULT_SENSITIVE_PATTERNS}) gate on approval out of the box,
     * before any tenant configures a single {@link ApprovalPolicyOverride}.
     * There is no on/off switch for this mechanism itself (unlike routing/
     * tool-minimization above) - it is structurally always active, so the
     * panel reports it active with this count rather than a boolean read
     * from config.
     */
    public int defaultSensitivePatternCount() {
        return DEFAULT_SENSITIVE_PATTERNS.size();
    }

    /** Cost-transparency panel: how many tenant-specific overrides currently exist, for context alongside the default count. */
    @Transactional(readOnly = true)
    public long overrideCount() {
        return overrideRepository.count();
    }

    @Transactional(readOnly = true)
    public boolean requiresApproval(String actionType) {
        Optional<ApprovalPolicyOverride> override = overrideRepository.findByActionType(actionType);
        if (override.isPresent()) {
            return override.get().isRequiresApproval();
        }
        return DEFAULT_SENSITIVE_PATTERNS.stream().anyMatch(pattern -> matchesGlob(pattern, actionType));
    }

    /**
     * Single-statement native {@code INSERT ... ON CONFLICT} upsert (see
     * {@link ApprovalPolicyOverrideRepository#upsert}). This replaces the
     * previous read-then-write implementation, whose documented residual
     * race - two concurrent callers both reading "no override exists" for
     * the same brand-new {@code actionType}, the loser's insert then dying
     * on the {@code uq_approval_policy_override_action_type} unique
     * constraint with an unretryable aborted transaction - is exactly what
     * a database-side upsert eliminates: Postgres resolves the conflict
     * atomically, so concurrent setters simply last-write-win instead of
     * one of them getting a 500. Semantics are unchanged: an existing row
     * keeps its id and has its flag and {@code updated_at} refreshed (even
     * when the flag value is the same, matching the old
     * {@code setRequiresApproval} behavior); a missing row is created.
     */
    @Transactional
    public void setOverride(String actionType, boolean requiresApproval) {
        overrideRepository.upsert(UUID.randomUUID(), actionType, requiresApproval, Instant.now());
    }

    private boolean matchesGlob(String pattern, String value) {
        String regex = "\\Q" + pattern.replace("*", "\\E.*\\Q") + "\\E";
        return value.matches(regex);
    }
}
