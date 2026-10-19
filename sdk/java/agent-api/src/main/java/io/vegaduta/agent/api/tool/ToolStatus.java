package io.vegaduta.agent.api.tool;

/** How a governed tool call ended. */
public enum ToolStatus {
  /** The tool ran and returned output. */
  OK,
  /** The output was served from the effect journal; the tool did not run again. */
  REPLAYED,
  /** A gate, guardrail or hook refused the call. */
  DENIED,
  /** An approval request was recorded; the tool will run once a human approves it. */
  PENDING_APPROVAL,
  /** The run's budget could not cover the call. */
  BUDGET_EXCEEDED,
  /** The tool threw. */
  ERROR
}
