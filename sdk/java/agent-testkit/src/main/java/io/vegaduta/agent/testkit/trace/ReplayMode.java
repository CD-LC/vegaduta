package io.vegaduta.agent.testkit.trace;

import io.vegaduta.agent.api.Experimental;

/** How a {@link ReplayTool} treats a call whose arguments were never recorded. */
@Experimental
public enum ReplayMode {
  /** Throw a {@link ReplayMismatchException} describing the nearest recorded call. */
  STRICT,
  /**
   * Serve the next not-yet-replayed entry recorded for the same tool, whatever its arguments; throw
   * only when the tool has no entries left.
   */
  LENIENT
}
