package com.agenticai.core.audit;

/**
 * The outcome recorded for a single audited action. Deliberately more
 * granular than a plain success/failure boolean - PENDING_APPROVAL and
 * AUTO_APPROVED are themselves meaningful compliance-relevant states for
 * anything gated by {@link com.agenticai.core.approval.HumanApprovalGateService},
 * not just a means to an eventual SUCCESS/FAILURE.
 */
public enum AuditOutcome {
    SUCCESS,
    FAILURE,
    PENDING_APPROVAL,
    AUTO_APPROVED,
    APPROVED,
    REJECTED,
    // The values below are the VegaDuta Agent SDK's audit vocabulary (sdk/java agent-api
    // io.vegaduta.agent.api.audit.AuditOutcome, sdk/python-agent vegaduta_agent.audit.AuditOutcome).
    // Core stores, filters and counts them so a record from an SDK-governed app and a core record
    // validate against the one published schema (contracts/outbound/audit-event.schema.json);
    // AuditOutcomeAlignmentTest (core/app) pins the three enums and the schema together.
    // Appended at the end: the column is EnumType.STRING, but ordinals stay stable anyway.
    /** A policy, guardrail or permission check blocked the action before it ran. */
    DENIED,
    /** An approval request timed out without a decision. */
    EXPIRED,
    /** The action was served from a recorded run (replay or shadow mode), not executed live. */
    REPLAYED,
    /** A cost or token budget stopped the action. */
    BUDGET_EXCEEDED,
    /** The action ran, but a check flagged it (for example output redacted). */
    WARNING
}
