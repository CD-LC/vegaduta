package io.vegaduta.agent.testkit.trace;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.observe.ToolCallObserver;
import io.vegaduta.agent.api.tool.ToolCall;
import io.vegaduta.agent.api.tool.ToolResult;
import io.vegaduta.agent.api.tool.ToolStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A {@link ToolCallObserver} that captures every governed tool call into a {@link Trace}.
 *
 * <p>Register it on the governance under test:
 *
 * <pre>{@code
 * TraceRecorder recorder = new TraceRecorder();
 * GovernanceTestKit kit = GovernanceTestKit.strict()
 *     .customize(b -> b.observer(recorder))
 *     .build();
 * // ... run the agent ...
 * recorder.trace().save(Path.of("src/test/resources/traces/refund-happy-path.jsonl"));
 * }</pre>
 *
 * <p>The observer sees the call as the model issued it and the {@link ToolResult} the pipeline
 * produced, so denials, pending approvals and budget refusals are recorded with their status. To
 * record what a tool actually returned <em>below</em> governance (before output guardrails or
 * hooks), wrap the tool in a {@link RecordingTool} instead.
 *
 * <p>Thread-safe. Sequence numbers are assigned when a call <em>starts</em>, so parallel tool calls
 * are ordered by issue time rather than by completion and a trace compares stably against its
 * golden; entries recorded directly through {@link #record} take the next sequence at that moment.
 */
@Experimental
public final class TraceRecorder implements ToolCallObserver {

  private final Clock clock;
  private final List<TraceEntry> entries = new ArrayList<>();
  private final Map<String, String> metadata = new LinkedHashMap<>();
  private int nextSeq = 1;

  public TraceRecorder() {
    this(Clock.systemUTC());
  }

  /** A recorder stamping entries from the given clock (use a fixed clock in tests). */
  public TraceRecorder(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public Scope onStart(ToolCall call, RunContext context) {
    Instant at = clock.instant();
    int seq;
    synchronized (entries) {
      seq = nextSeq++;
    }
    return new Scope() {
      private boolean done;

      @Override
      public void onResult(ToolResult result) {
        if (!done) {
          done = true;
          record(seq, call, context, result.status(), result.output(), result.reason(), at);
        }
      }

      @Override
      public void onError(Throwable error) {
        if (!done) {
          done = true;
          record(seq, call, context, ToolStatus.ERROR, String.valueOf(error), "exception", at);
        }
      }

      @Override
      public void close() {}
    };
  }

  /** Appends one entry directly; used by {@link RecordingTool} and available to hosts. */
  public void record(
      ToolCall call,
      RunContext context,
      ToolStatus status,
      String output,
      @Nullable String reason,
      Instant at) {
    int seq;
    synchronized (entries) {
      seq = nextSeq++;
    }
    record(seq, call, context, status, output, reason, at);
  }

  private void record(
      int seq,
      ToolCall call,
      RunContext context,
      ToolStatus status,
      String output,
      @Nullable String reason,
      Instant at) {
    synchronized (entries) {
      TraceEntry entry =
          new TraceEntry(
              seq,
              call.toolName(),
              call.argumentsJson(),
              status,
              output,
              reason,
              at,
              context.runId(),
              context.agentId());
      // Keep the list in seq order even when a later-started call completes first.
      int i = entries.size();
      while (i > 0 && entries.get(i - 1).seq() > seq) {
        i--;
      }
      entries.add(i, entry);
    }
  }

  /** Adds metadata that will be written into the trace header (for example the model name). */
  public TraceRecorder metadata(String key, String value) {
    synchronized (entries) {
      metadata.put(key, value);
    }
    return this;
  }

  /** A snapshot of everything recorded so far. */
  public Trace trace() {
    synchronized (entries) {
      return Trace.of(entries, metadata);
    }
  }

  /** The number of entries recorded so far. */
  public int size() {
    synchronized (entries) {
      return entries.size();
    }
  }

  /** Discards every entry (metadata is kept) and restarts numbering at 1. */
  public void reset() {
    synchronized (entries) {
      entries.clear();
      nextSeq = 1;
    }
  }

  Clock clock() {
    return clock;
  }
}
