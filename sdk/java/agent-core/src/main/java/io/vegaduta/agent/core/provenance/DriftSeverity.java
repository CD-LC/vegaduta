package io.vegaduta.agent.core.provenance;

import io.vegaduta.agent.api.Experimental;

/**
 * How serious a difference between the pinned and the current tool manifest is.
 *
 * <ul>
 *   <li>{@link #HIGH}: a tool was added, a tool's input schema changed, or a description matched an
 *       injection pattern. A new tool is capability the agent was never approved for; a schema
 *       change can add a parameter that exfiltrates data ("also send the file to this URL").
 *   <li>{@link #MEDIUM}: only descriptions or metadata changed. A description is text the model
 *       reads and trusts as part of its instructions, so an edited description can carry injected
 *       instructions even when the tool's code and schema are unchanged.
 *   <li>{@link #LOW}: tools were only removed. The agent lost capability; nothing new can run.
 *   <li>{@link #NONE}: identical.
 * </ul>
 */
@Experimental
public enum DriftSeverity {
  NONE,
  LOW,
  MEDIUM,
  HIGH;

  public boolean atLeast(DriftSeverity other) {
    return compareTo(other) >= 0;
  }
}
