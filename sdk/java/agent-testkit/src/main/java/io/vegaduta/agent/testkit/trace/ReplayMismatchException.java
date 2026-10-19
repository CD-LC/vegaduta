package io.vegaduta.agent.testkit.trace;

import io.vegaduta.agent.api.Experimental;
import java.util.List;
import java.util.Objects;

/**
 * Thrown by a {@link ReplayTool} when the agent made a call the trace does not contain.
 *
 * <p>The message lists the actual call and every recorded call for the same tool, with a
 * field-level diff against the closest one, so the failing test explains what the agent did
 * differently.
 */
@Experimental
public final class ReplayMismatchException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String toolName;
  private final String actualArguments;
  private final List<String> recordedArguments;

  public ReplayMismatchException(
      String toolName, String actualArguments, List<String> recordedArguments, String detail) {
    super(detail);
    this.toolName = Objects.requireNonNull(toolName, "toolName");
    this.actualArguments = Objects.requireNonNull(actualArguments, "actualArguments");
    this.recordedArguments = List.copyOf(recordedArguments);
  }

  public String toolName() {
    return toolName;
  }

  /** The canonical arguments of the unmatched call. */
  public String actualArguments() {
    return actualArguments;
  }

  /** The canonical arguments of every recorded call for the tool, in order. */
  public List<String> recordedArguments() {
    return recordedArguments;
  }
}
