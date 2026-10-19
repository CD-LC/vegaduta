package io.vegaduta.agent.api.audit;

/**
 * Outcome of an audited action. Deliberately more granular than success/failure: the approval
 * states are compliance evidence on their own.
 */
public enum AuditOutcome {
  SUCCESS,
  FAILURE,
  DENIED,
  PENDING_APPROVAL,
  AUTO_APPROVED,
  APPROVED,
  REJECTED,
  EXPIRED,
  REPLAYED,
  BUDGET_EXCEEDED,
  WARNING
}
