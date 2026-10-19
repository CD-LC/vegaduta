package com.agenticai.core.agents.tools.classification;

import java.util.Objects;

/**
 * The complete, immutable risk declaration for one tool name:
 * <b>trust x sensitivity x determinism</b>, plus the three flags that the three
 * enums cannot express on their own.
 *
 * <p><b>Why a first-class type at all.</b> Every enforcement mechanism this
 * platform wants next needs to know what a tool <i>is</i>, and today that
 * knowledge is implicit and scattered:
 * {@code ApprovalPolicyService.DEFAULT_SENSITIVE_PATTERNS} holds a handful of
 * action-name globs, {@code GuardedToolCallback} knows only
 * {@code "mcp_tool." + name}, {@code AgentToolProvider} knows which credential
 * each tool needs but records nothing about blast radius, and nothing anywhere
 * records whether a call can be safely repeated. An irreversible-action gate,
 * taint propagation, a per-tool approval policy, progressive tool disclosure and
 * durable replay are five features that each would otherwise re-derive the same
 * facts, differently, and disagree under pressure. This record is the one place
 * those facts are stated.
 *
 * <p><b>Immutability is load-bearing, not stylistic.</b> A classification is
 * read on the tool-dispatch path, concurrently, by every turn of every agent of
 * every tenant in the process, and it is cached. A mutable classification object
 * would mean a security decision whose inputs can change between the check and
 * the call, which is the classic TOCTOU shape. Being a {@code record} with no
 * setters, no mutable component types (four enums, a String, two booleans) and a
 * validating canonical constructor makes "the thing I checked is the thing I
 * enforced" structurally true rather than a review comment.
 *
 * <p><b>There is no "unknown" or "default" instance, on purpose.</b> Callers do
 * not receive a {@code ToolClassification} for a tool nobody classified; they
 * receive a {@link ToolClassificationDecision.Blocked}. Fail-closed is the whole
 * design, and the way it is made unskippable is that the happy-path type simply
 * cannot represent ignorance - see {@link ToolClassificationDecision}.
 *
 * @param toolName        the exact tool name this applies to, as the model sees
 *                        it and as {@code ToolDefinition.name()} reports it. For
 *                        MCP-sourced tools that is the namespaced form
 *                        {@code mcp__<server>__<tool>} produced by
 *                        {@code NamespacedMcpToolCallback}, never the bare
 *                        upstream name - classifying the bare name would leave
 *                        the name the platform actually dispatches on
 *                        unclassified, i.e. blocked.
 * @param trust           where this tool's code and returned bytes come from
 * @param sensitivity     what this tool can reach or reveal
 * @param determinism     whether repeating this call is safe
 * @param requiredScope   the narrowest scope/permission string a caller must
 *                        hold, or {@code null} when the tool needs no scope
 *                        beyond being bound to the agent. Free-form on purpose:
 *                        the honest value is whatever the upstream system calls
 *                        it (an OAuth scope URL such as
 *                        {@code https://www.googleapis.com/auth/gmail.send}, a
 *                        platform role, an entitlement key), and forcing those
 *                        into one enum would mean inventing a mapping that then
 *                        has to be kept true.
 * @param irreversible    true when a successful call cannot be undone by this
 *                        platform. Separate from {@link ToolDeterminism} because
 *                        "creates a workspace" and "deletes a workspace" are both
 *                        {@link ToolDeterminism#DURABLE} and are nothing alike;
 *                        this flag, not determinism, is what drives
 *                        {@link #requiresHumanConfirmation()}.
 * @param needsToolContext true when the tool cannot execute correctly without
 *                        Spring AI's model-invisible {@code ToolContext} carrying
 *                        tenant/user identity. Declared so that a registration
 *                        check can refuse to bind an identity-dependent tool into
 *                        a path that has no context to supply (a background
 *                        sweeper, an A2A inbound call, an edge execution) instead
 *                        of discovering it as a null-tenant failure at runtime.
 *                        {@code ToolContext} is the identity channel in this
 *                        codebase and identity is never a tool argument, so a
 *                        tool that needs identity and has no context is a bug
 *                        that should be caught at wiring time.
 */
public record ToolClassification(
    String toolName,
    ToolTrustTier trust,
    ToolSensitivity sensitivity,
    ToolDeterminism determinism,
    String requiredScope,
    boolean irreversible,
    boolean needsToolContext
) {

    /**
     * Validating canonical constructor. The four non-nullable components are
     * rejected rather than defaulted, because there is no safe default for any of
     * them: guessing {@code TRUSTED_INTERNAL} would un-taint an untrusted source
     * and guessing {@code UNTRUSTED_EXTERNAL} would block first-party tools, so
     * the only correct behaviour on a missing value is to refuse to construct the
     * object at all. A row that cannot produce a valid classification surfaces as
     * {@link ToolClassificationDecision.Blocked} with reason
     * {@link ToolClassificationDecision.Reason#INVALID_DECLARATION}.
     *
     * <p>A blank {@code requiredScope} is normalised to {@code null} so that
     * "absent" has exactly one representation - an empty-string scope read back
     * from a database column would otherwise compare unequal to a {@code null}
     * one declared in code and show up as spurious drift in
     * {@link ToolClassificationRegistry#driftFromBuiltInDeclarations()}.
     */
    public ToolClassification {
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(trust, "trust");
        Objects.requireNonNull(sensitivity, "sensitivity");
        Objects.requireNonNull(determinism, "determinism");
        toolName = toolName.trim();
        if (toolName.isEmpty()) {
            throw new IllegalArgumentException("toolName must not be blank");
        }
        if (requiredScope != null && requiredScope.isBlank()) {
            requiredScope = null;
        }
    }

    /**
     * True when a human must confirm this call before it executes, even with no
     * taint present and no approval policy configured.
     *
     * <p>The rule is <b>irreversible AND consequential</b>: an action that cannot
     * be undone, reaching something that matters. Both halves are needed.
     * Irreversibility alone is not enough - {@code fetch_web_page} cannot be
     * "undone" in any meaningful sense and gating it would make the platform
     * unusable. Sensitivity alone is not enough either - indexing a document is
     * {@link ToolSensitivity#WRITE_TENANT} and is trivially reversible by
     * deleting it, and prompting a human for every knowledge write would train
     * operators to click approve without reading, which is strictly worse than
     * not asking.
     *
     * <p>Deliberately <b>independent of taint</b>. This predicate answers "is
     * this consequential enough that a human should see it", which is a property
     * of the tool and is true on a perfectly clean conversation. Taint adds a
     * second, orthogonal reason to escalate, evaluated per-invocation by the
     * taint gate against {@link ToolSensitivity#isTaintSink()}. Keeping them
     * separate means the confirmation prompt can state which of the two reasons
     * fired, and means turning the taint gate off for a tenant does not quietly
     * also turn off confirmation on irreversible actions.
     *
     * <p>This is intentionally the floor and not the ceiling: a tenant-admin can
     * still gate anything else through {@code ApprovalPolicyService}'s existing
     * {@code mcp_tool.<name>} override, and nothing here loosens a policy that
     * already says approve.
     */
    public boolean requiresHumanConfirmation() {
        return irreversible && sensitivity.isConsequential();
    }

    /**
     * True when output from this tool must be recorded as tainted by the
     * taint-propagation layer. Delegates to {@link ToolTrustTier} so there is one
     * definition.
     */
    public boolean producesTaintedOutput() {
        return trust.producesTaintedOutput();
    }

    /**
     * True when this tool must not be invoked while the conversation carries
     * taint - the sink side of the information-flow invariant. Delegates to
     * {@link ToolSensitivity} so there is one definition.
     */
    public boolean isTaintSink() {
        return sensitivity.isTaintSink();
    }

    /**
     * True when a retry, resume or replay of this tool must carry an idempotency
     * key. Delegates to {@link ToolDeterminism} so there is one definition.
     */
    public boolean requiresIdempotencyKey() {
        return determinism.requiresIdempotencyKey();
    }

    /**
     * A compact, log- and span-safe rendering of the three dimensions:
     * {@code "TENANT_SCOPED/EGRESS/DURABLE"}, with {@code "+irreversible"}
     * appended when that flag is set.
     *
     * <p>Exists so that a single attribute can be attached to every tool-call
     * span and tool_call_log row, which is what makes the post-incident question
     * this registry was built for - "which CREDENTIALED tools ran in this run" -
     * a query rather than a code-reading exercise. Contains no tenant data, no
     * arguments and no tool output, so it is safe at any log level.
     */
    public String riskLabel() {
        return trust + "/" + sensitivity + "/" + determinism + (irreversible ? "+irreversible" : "");
    }

    /**
     * Returns a copy keyed to a different tool name, everything else unchanged.
     *
     * <p>The reason this exists: a stored glob row such as
     * {@code mcp__somesrv__*} classifies a whole MCP server's surface, and when
     * it matches the concrete tool {@code mcp__somesrv__delete_issue} the caller
     * must be handed a classification whose {@code toolName} is the concrete tool
     * it actually asked about - not the pattern. Otherwise every log line, span
     * attribute and approval prompt downstream names a tool that does not exist,
     * and correlating an enforcement decision back to a real call becomes
     * guesswork.
     */
    public ToolClassification withToolName(String concreteToolName) {
        return new ToolClassification(
            concreteToolName, trust, sensitivity, determinism, requiredScope, irreversible, needsToolContext);
    }
}
