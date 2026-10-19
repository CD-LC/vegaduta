package io.vegaduta.agent.testkit.trace;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.tool.ToolStatus;
import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One recorded tool call.
 *
 * @param seq position in the trace, starting at 1
 * @param toolName the tool that was called
 * @param argumentsJson the arguments exactly as the tool received them
 * @param status how the call ended
 * @param output what the model received (the tool output, or the governance explanation)
 * @param reason the machine-readable refusal reason, or {@code null}
 * @param at when the call started (volatile; ignored when traces are compared)
 * @param runId the run the call belonged to (volatile; ignored when traces are compared)
 * @param agentId the agent, or {@code null} (volatile; ignored when traces are compared)
 */
@Experimental
public record TraceEntry(
    int seq,
    String toolName,
    String argumentsJson,
    ToolStatus status,
    String output,
    @Nullable String reason,
    Instant at,
    String runId,
    @Nullable String agentId) {

  public TraceEntry {
    Objects.requireNonNull(toolName, "toolName");
    Objects.requireNonNull(argumentsJson, "argumentsJson");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(output, "output");
    Objects.requireNonNull(at, "at");
    Objects.requireNonNull(runId, "runId");
  }

  /** The canonical form of the arguments; see {@link TraceJson#canonicalize(String)}. */
  public String canonicalArguments() {
    return TraceJson.canonicalize(argumentsJson);
  }

  /** {@code true} when the tool ran (or was served from the journal) and returned output. */
  public boolean succeeded() {
    return status == ToolStatus.OK || status == ToolStatus.REPLAYED;
  }

  /** A copy with a different position. */
  public TraceEntry withSeq(int newSeq) {
    return new TraceEntry(
        newSeq, toolName, argumentsJson, status, output, reason, at, runId, agentId);
  }

  /** A copy with different arguments (for redaction before comparison). */
  public TraceEntry withArgumentsJson(String newArguments) {
    return new TraceEntry(seq, toolName, newArguments, status, output, reason, at, runId, agentId);
  }

  /** A short one-line description for assertion and diff messages. */
  public String describe() {
    return "#" + seq + " " + toolName + "(" + canonicalArguments() + ") -> " + status;
  }
}
