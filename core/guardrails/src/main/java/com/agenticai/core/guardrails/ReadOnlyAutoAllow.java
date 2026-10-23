package com.agenticai.core.guardrails;

import java.util.UUID;

/**
 * The one place that names what a SUPERVISED agent's unattended read-only call is called in the records people review.
 * Under {@code app.guardrails.supervised.gate-read-only=false} a classified read-only tool runs without a human
 * ({@link ApprovalGateMode#GATE_UNLESS_READ_ONLY}); that is a decision the platform made on the person's behalf, so
 * each such call is written to the audit log ({@link Audit}) and to the turn journal, with this note, and counted.
 *
 * <p>These are machine labels read by reviewers and the turn inspector, not model-facing or end-user text.
 */
public final class ReadOnlyAutoAllow {

    /** What the audit entry and the log line say. */
    public static final String NOTE = "auto-allowed: read-only under SUPERVISED";

    /** Audit action type of the entry. */
    public static final String AUDIT_ACTION = "tool.supervised.auto_allowed";

    /** Counter of calls let through this way (no tool tag: the call timer already carries it). */
    public static final String METRIC = "agent.tool.supervised.auto_allowed";

    /** The journal's {@code decision} value (one token: the inspector splits a summary on whitespace). */
    public static final String JOURNAL_DECISION = "auto-allowed";

    /** The journal's {@code reason} value for that decision (the same note, as one token). */
    public static final String JOURNAL_REASON = "read-only-under-SUPERVISED";

    private ReadOnlyAutoAllow() {}

    /** Where {@link GuardedToolCallback} reports such a call for the audit log. Must not throw; the call proceeds either way. */
    @FunctionalInterface
    public interface Audit {

        /**
         * @param detail log-safe text: the {@link #NOTE} and the tool's risk label, never arguments or output
         */
        void autoAllowed(String toolName, UUID agentId, String sessionId, String detail);
    }
}
