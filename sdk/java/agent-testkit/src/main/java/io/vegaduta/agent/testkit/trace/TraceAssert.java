package io.vegaduta.agent.testkit.trace;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.tool.ToolStatus;
import java.util.List;
import java.util.function.UnaryOperator;
import org.assertj.core.api.AbstractAssert;

/**
 * AssertJ assertions over a {@link Trace}.
 *
 * <pre>{@code
 * import static io.vegaduta.agent.testkit.trace.TraceAssert.assertThat;
 *
 * assertThat(recorder.trace())
 *     .hasCall("lookup_order")
 *     .hasCallCount("refund", 1)
 *     .callArgsContain("refund", "\"order\":42")
 *     .noCallAfterStatus(ToolStatus.DENIED)
 *     .matches(Trace.load(golden));
 * }</pre>
 */
@Experimental
public final class TraceAssert extends AbstractAssert<TraceAssert, Trace> {

  TraceAssert(Trace actual) {
    super(actual, TraceAssert.class);
  }

  public static TraceAssert assertThat(Trace actual) {
    return new TraceAssert(actual);
  }

  public static TraceAssert assertThat(TraceRecorder recorder) {
    return new TraceAssert(recorder.trace());
  }

  public TraceAssert isEmpty() {
    isNotNull();
    if (!actual.isEmpty()) {
      failWithMessage("Expected an empty trace but it had %s", summary());
    }
    return this;
  }

  public TraceAssert hasSize(int expected) {
    isNotNull();
    if (actual.size() != expected) {
      failWithMessage(
          "Expected %s entries but the trace had %s: %s", expected, actual.size(), summary());
    }
    return this;
  }

  /** At least one call to the tool. */
  public TraceAssert hasCall(String toolName) {
    isNotNull();
    if (actual.entriesFor(toolName).isEmpty()) {
      failWithMessage("Expected a call to '%s' but the trace was %s", toolName, summary());
    }
    return this;
  }

  public TraceAssert hasNoCall(String toolName) {
    isNotNull();
    if (!actual.entriesFor(toolName).isEmpty()) {
      failWithMessage("Expected no call to '%s' but the trace was %s", toolName, summary());
    }
    return this;
  }

  public TraceAssert hasCallCount(String toolName, int count) {
    isNotNull();
    int found = actual.entriesFor(toolName).size();
    if (found != count) {
      failWithMessage(
          "Expected %s call(s) to '%s' but found %s in %s", count, toolName, found, summary());
    }
    return this;
  }

  /** Some call to the tool has arguments containing {@code substring} (raw or canonical form). */
  public TraceAssert callArgsContain(String toolName, String substring) {
    hasCall(toolName);
    List<TraceEntry> calls = actual.entriesFor(toolName);
    boolean found =
        calls.stream()
            .anyMatch(
                e ->
                    e.argumentsJson().contains(substring)
                        || e.canonicalArguments().contains(substring));
    if (!found) {
      failWithMessage(
          "Expected a call to '%s' with arguments containing <%s> but the arguments were %s",
          toolName, substring, calls.stream().map(TraceEntry::canonicalArguments).toList());
    }
    return this;
  }

  /** Some call to the tool ended with the status. */
  public TraceAssert hasStatus(String toolName, ToolStatus status) {
    hasCall(toolName);
    if (actual.entriesFor(toolName).stream().noneMatch(e -> e.status() == status)) {
      failWithMessage(
          "Expected a call to '%s' with status %s but the trace was %s",
          toolName, status, summary());
    }
    return this;
  }

  /** No entry anywhere in the trace has the status. */
  public TraceAssert hasNoStatus(ToolStatus status) {
    isNotNull();
    List<TraceEntry> hits = actual.entries().stream().filter(e -> e.status() == status).toList();
    if (!hits.isEmpty()) {
      failWithMessage(
          "Expected no %s entries but found %s",
          status, hits.stream().map(TraceEntry::describe).toList());
    }
    return this;
  }

  /**
   * After the first entry with the status, nothing else is called. Catches an agent that carries on
   * after a denial or a pending approval.
   */
  public TraceAssert noCallAfterStatus(ToolStatus status) {
    isNotNull();
    List<TraceEntry> entries = actual.entries();
    for (int i = 0; i < entries.size(); i++) {
      if (entries.get(i).status() == status) {
        if (i + 1 < entries.size()) {
          failWithMessage(
              "Expected no call after %s (%s) but the agent went on with %s",
              status,
              entries.get(i).describe(),
              entries.subList(i + 1, entries.size()).stream().map(TraceEntry::describe).toList());
        }
        return this;
      }
    }
    return this;
  }

  /** The calls happen in this order (other calls may be interleaved). */
  public TraceAssert hasCallsInOrder(String... toolNames) {
    isNotNull();
    int next = 0;
    for (TraceEntry e : actual.entries()) {
      if (next < toolNames.length && e.toolName().equals(toolNames[next])) {
        next++;
      }
    }
    if (next < toolNames.length) {
      failWithMessage(
          "Expected calls in order %s but the trace was %s", List.of(toolNames), summary());
    }
    return this;
  }

  /** No differences from {@code expected} as judged by {@link TraceDiff}. */
  public TraceAssert matches(Trace expected) {
    return matches(expected, UnaryOperator.identity());
  }

  public TraceAssert matches(Trace expected, UnaryOperator<String> redactArgs) {
    isNotNull();
    TraceDiff diff = TraceDiff.compare(expected, actual, redactArgs);
    if (!diff.isEmpty()) {
      failWithMessage("Trace differs from the expected one:%n%s", diff.toMarkdown());
    }
    return this;
  }

  private String summary() {
    return actual.entries().stream().map(TraceEntry::describe).toList().toString();
  }
}
