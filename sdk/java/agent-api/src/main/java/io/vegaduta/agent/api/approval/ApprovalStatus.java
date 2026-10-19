package io.vegaduta.agent.api.approval;

/** Lifecycle of an approval request. */
public enum ApprovalStatus {
  /** Waiting for a human. */
  PENDING,
  /** A human approved it; the action may run (or is running). */
  APPROVED,
  /** A human rejected it. */
  REJECTED,
  /** Nobody decided before it expired. */
  EXPIRED,
  /** Approved and the action ran successfully. */
  EXECUTED,
  /** Approved but the action failed. */
  FAILED;

  public boolean decided() {
    return this != PENDING;
  }
}
