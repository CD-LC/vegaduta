package io.vegaduta.agent.testkit.trace;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.tool.Tool;
import io.vegaduta.agent.api.tool.ToolDescriptor;
import io.vegaduta.agent.api.tool.ToolStatus;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/**
 * A {@link Tool} that answers from a recorded {@link Trace} instead of running anything.
 *
 * <p>Calls are matched on {@code (toolName, canonical argumentsJson)}. Entries with identical
 * arguments are served in recorded order, so a tool that was called twice with the same input and
 * answered differently replays both answers; once they are used up the last one is served again. An
 * unmatched call is handled per {@link ReplayMode}.
 *
 * <p>What a served entry does depends on its recorded status: a successful entry ({@link
 * ToolStatus#OK}, {@link ToolStatus#REPLAYED}) returns its output; an {@link ToolStatus#ERROR}
 * entry throws an {@link IllegalStateException} carrying the recorded message, so the agent sees
 * the failure again. When the entry was recorded above governance its output is already the
 * explanation the pipeline produced, and the replay wraps it once more; {@link TraceDiff} therefore
 * compares {@code ERROR} entries by status only. Any other status (a denial, a pending approval, a
 * budget refusal) returns the explanation text the model saw at recording time.
 *
 * <p>Under governance a {@link ReplayMismatchException} thrown here is caught by the pipeline and
 * handed to the model as a {@code tool_failed} result; the first mismatch is kept in {@link
 * #firstMismatch()} so {@code TraceKit} and {@code TraceAssert} can surface it with its diff.
 *
 * <p>Thread-safe.
 */
@Experimental
public final class ReplayTool implements Tool {

  private final ToolDescriptor descriptor;
  private final ReplayMode mode;
  private final List<TraceEntry> recorded;
  private final Map<String, Deque<TraceEntry>> byArguments = new LinkedHashMap<>();
  private final Map<String, TraceEntry> lastServed = new LinkedHashMap<>();
  private final Deque<TraceEntry> unconsumed = new ArrayDeque<>();
  private final List<String> served = new ArrayList<>();
  private final AtomicReference<ReplayMismatchException> firstMismatch = new AtomicReference<>();

  private ReplayTool(ToolDescriptor descriptor, List<TraceEntry> recorded, ReplayMode mode) {
    this.descriptor = descriptor;
    this.mode = mode;
    this.recorded = List.copyOf(recorded);
    for (TraceEntry e : this.recorded) {
      byArguments.computeIfAbsent(e.canonicalArguments(), k -> new ArrayDeque<>()).add(e);
      unconsumed.add(e);
    }
  }

  /** A strict replay of every entry for {@code toolName} in the trace. */
  public static ReplayTool of(Trace trace, String toolName) {
    return of(trace, toolName, ReplayMode.STRICT);
  }

  public static ReplayTool of(Trace trace, String toolName, ReplayMode mode) {
    return of(trace, ToolDescriptor.of(toolName, "replay of recorded tool " + toolName), mode);
  }

  /** A replay with the host's own descriptor (schema, metadata) so the model sees the real tool. */
  public static ReplayTool of(Trace trace, ToolDescriptor descriptor, ReplayMode mode) {
    Objects.requireNonNull(trace, "trace");
    Objects.requireNonNull(descriptor, "descriptor");
    Objects.requireNonNull(mode, "mode");
    return new ReplayTool(descriptor, trace.entriesFor(descriptor.name()), mode);
  }

  @Override
  public ToolDescriptor descriptor() {
    return descriptor;
  }

  public ReplayMode mode() {
    return mode;
  }

  /** The canonical arguments of every call served so far, in order. */
  public synchronized List<String> servedArguments() {
    return List.copyOf(served);
  }

  /** The recorded entries this tool can serve. */
  public List<TraceEntry> recorded() {
    return recorded;
  }

  /** The first unmatched call, if any; set even when governance swallowed the exception. */
  public Optional<ReplayMismatchException> firstMismatch() {
    return Optional.ofNullable(firstMismatch.get());
  }

  @Override
  public synchronized String call(String argumentsJson, RunContext context) {
    String canonical = TraceJson.canonicalize(argumentsJson);
    TraceEntry entry;
    try {
      entry = match(canonical);
    } catch (ReplayMismatchException e) {
      firstMismatch.compareAndSet(null, e);
      throw e;
    }
    served.add(canonical);
    return serve(entry);
  }

  private TraceEntry match(String canonical) {
    Deque<TraceEntry> queue = byArguments.get(canonical);
    if (queue != null) {
      TraceEntry next = queue.poll();
      if (next != null) {
        unconsumed.remove(next);
        lastServed.put(canonical, next);
        return next;
      }
      TraceEntry last = lastServed.get(canonical);
      if (last != null) {
        return last;
      }
    }
    if (mode == ReplayMode.LENIENT) {
      TraceEntry next = unconsumed.poll();
      if (next != null) {
        Deque<TraceEntry> owner = byArguments.get(next.canonicalArguments());
        if (owner != null) {
          owner.remove(next);
        }
        lastServed.put(next.canonicalArguments(), next);
        return next;
      }
    }
    throw mismatch(canonical);
  }

  private static String serve(TraceEntry entry) {
    if (entry.status() == ToolStatus.ERROR) {
      throw new IllegalStateException(
          "replayed failure of tool '" + entry.toolName() + "': " + entry.output());
    }
    return entry.output();
  }

  private ReplayMismatchException mismatch(String canonical) {
    List<String> recordedArgs = recorded.stream().map(TraceEntry::canonicalArguments).toList();
    StringBuilder sb = new StringBuilder();
    sb.append("No recorded call matches ")
        .append(descriptor.name())
        .append('(')
        .append(canonical)
        .append(")");
    sb.append(mode == ReplayMode.LENIENT ? " and no recorded entries are left" : "").append(".\n");
    if (recordedArgs.isEmpty()) {
      sb.append("The trace has no calls to '").append(descriptor.name()).append("' at all.");
      return new ReplayMismatchException(descriptor.name(), canonical, recordedArgs, sb.toString());
    }
    sb.append("Recorded calls to '").append(descriptor.name()).append("':\n");
    TraceEntry closest = closest(canonical);
    for (TraceEntry e : recorded) {
      sb.append("  ").append(e.describe());
      if (e == closest) {
        sb.append("   <- closest");
      }
      sb.append('\n');
    }
    if (closest != null) {
      sb.append("Diff against closest (- recorded, + actual):\n");
      for (String line : ArgumentDiff.lines(closest.canonicalArguments(), canonical)) {
        sb.append("  ").append(line).append('\n');
      }
    }
    return new ReplayMismatchException(descriptor.name(), canonical, recordedArgs, sb.toString());
  }

  private @Nullable TraceEntry closest(String canonical) {
    TraceEntry best = null;
    int bestDistance = Integer.MAX_VALUE;
    for (TraceEntry e : recorded) {
      int d = ArgumentDiff.distance(e.canonicalArguments(), canonical);
      if (d < bestDistance) {
        bestDistance = d;
        best = e;
      }
    }
    return best;
  }
}
