package com.agenticai.core.guardrails;

/**
 * A pre-dispatch verdict on one tool call, consulted by {@link
 * GuardedToolCallback} before anything else it does except the step count.
 *
 * <p>Exists so a policy that lives in a module {@code core/guardrails} cannot
 * depend on - today the tool-classification registry in {@code core/agents}
 * ({@code ToolClassificationRegistry}) - can still take part in the one
 * dispatch choke point every agent tool call passes through. The agents module
 * supplies the implementation; this module only knows the three possible
 * answers.
 *
 * <p>Implementations are called on the tool-execution thread with the turn's
 * tenant context already restored ({@code TenantContextPropagatingToolCallback}
 * wraps outside {@code GuardedToolCallback}), so a tenant-schema lookup is safe.
 * An implementation that throws is handled by the caller per {@link
 * GuardrailFailMode}: {@link Outcome#DENY} when closed (the default), {@link Outcome#ALLOW} when
 * open.
 */
@FunctionalInterface
public interface ToolDispatchGate {

    Verdict check(String toolName);

    enum Outcome {
        /** Run the tool (subject to every other check in {@link GuardedToolCallback}). */
        ALLOW,
        /** Do not run it; {@link Verdict#reason()} is returned to the model as the tool result. */
        DENY,
        /** Route it through the human-approval gate, exactly like a policy-gated action. */
        REQUIRE_APPROVAL
    }

    /**
     * @param reason model-facing explanation for {@link Outcome#DENY}; a short
     *     log/metric-safe note for the other outcomes. Never contains tool
     *     arguments or output.
     * @param readOnly true only for an ALLOW the gate positively classified as read-only (see {@link
     *     #allowReadOnly()}); false for everything else, including an unclassified tool.
     */
    record Verdict(Outcome outcome, String reason, boolean readOnly) {

        private static final Verdict ALLOWED = new Verdict(Outcome.ALLOW, null, false);

        public Verdict {
            outcome = outcome == null ? Outcome.ALLOW : outcome;
            // Only an ALLOW can be read-only: a refusal or an approval requirement is never "safe to run unattended".
            readOnly = readOnly && outcome == Outcome.ALLOW;
        }

        public Verdict(Outcome outcome, String reason) {
            this(outcome, reason, false);
        }

        /**
         * An ALLOW the gate also vouches for as read-only: the tool has an explicit classification row (a stored row
         * or a compiled-in declaration, never a server's own hint) that is not consequential and not irreversible.
         * {@code GuardedToolCallback} uses it only under {@link ApprovalGateMode#GATE_UNLESS_READ_ONLY}; for every
         * other mode it is an ordinary allow.
         */
        public static Verdict allowReadOnly() {
            return new Verdict(Outcome.ALLOW, null, true);
        }

        public static Verdict allow() {
            return ALLOWED;
        }

        public static Verdict deny(String reason) {
            return new Verdict(Outcome.DENY, reason);
        }

        public static Verdict requireApproval(String reason) {
            return new Verdict(Outcome.REQUIRE_APPROVAL, reason);
        }
    }
}
