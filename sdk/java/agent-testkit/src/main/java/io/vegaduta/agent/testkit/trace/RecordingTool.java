package io.vegaduta.agent.testkit.trace;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.tool.Tool;
import io.vegaduta.agent.api.tool.ToolCall;
import io.vegaduta.agent.api.tool.ToolDescriptor;
import io.vegaduta.agent.api.tool.ToolStatus;
import java.time.Instant;
import java.util.Objects;

/**
 * Wraps a {@link Tool} and records what it is called with and what it returns, below governance.
 *
 * <p>Use this when the trace must contain the raw tool output (before output guardrails, hooks or
 * the effect journal rewrite it) or when there is no governance in the loop at all:
 *
 * <pre>{@code
 * TraceRecorder recorder = new TraceRecorder();
 * Tool weather = RecordingTool.wrap(realWeatherTool, recorder);
 * Tool governed = governance.govern(weather);
 * }</pre>
 *
 * <p>Every successful call is recorded with {@link ToolStatus#OK}; a thrown exception is recorded
 * as {@link ToolStatus#ERROR} and rethrown.
 */
@Experimental
public final class RecordingTool implements Tool {

  private final Tool delegate;
  private final TraceRecorder recorder;

  private RecordingTool(Tool delegate, TraceRecorder recorder) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.recorder = Objects.requireNonNull(recorder, "recorder");
  }

  public static RecordingTool wrap(Tool delegate, TraceRecorder recorder) {
    return delegate instanceof RecordingTool already && already.recorder == recorder
        ? already
        : new RecordingTool(delegate, recorder);
  }

  public Tool delegate() {
    return delegate;
  }

  @Override
  public ToolDescriptor descriptor() {
    return delegate.descriptor();
  }

  @Override
  public String call(String argumentsJson, RunContext context) {
    Instant at = recorder.clock().instant();
    ToolCall call = ToolCall.of(delegate.name(), argumentsJson);
    try {
      String output = delegate.call(argumentsJson, context);
      recorder.record(call, context, ToolStatus.OK, output, null, at);
      return output;
    } catch (RuntimeException e) {
      recorder.record(call, context, ToolStatus.ERROR, String.valueOf(e), "exception", at);
      throw e;
    }
  }
}
