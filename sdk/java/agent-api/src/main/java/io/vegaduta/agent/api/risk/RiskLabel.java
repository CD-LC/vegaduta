package io.vegaduta.agent.api.risk;

/** Coarse damage potential of a tool, ordered from least to most severe. */
public enum RiskLabel {
  /** Read-only, public data, no side effects. */
  LOW,
  /** Reads internal data or performs reversible writes. */
  MEDIUM,
  /** Irreversible or externally visible effects: money, messages, deletions, deployments. */
  HIGH,
  /** Effects that can harm people or the organisation at scale; always approval-gated. */
  CRITICAL;

  public boolean atLeast(RiskLabel other) {
    return compareTo(other) >= 0;
  }
}
