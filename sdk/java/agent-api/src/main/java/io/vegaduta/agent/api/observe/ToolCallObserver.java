package io.vegaduta.agent.api.observe;

import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.tool.ToolCall;
import io.vegaduta.agent.api.tool.ToolResult;

/**
 * Sees every governed tool call start and end. Metrics (Micrometer) and tracing (OpenTelemetry
 * {@code gen_ai} spans) are implemented as observers in core; hosts can add their own.
 */
public interface ToolCallObserver {

  /** Called before the pipeline runs; the returned scope is closed when it ends. */
  Scope onStart(ToolCall call, RunContext context);

  interface Scope extends AutoCloseable {

    void onResult(ToolResult result);

    void onError(Throwable error);

    @Override
    void close();
  }

  /** An observer that does nothing. */
  static ToolCallObserver none() {
    return (call, context) ->
        new Scope() {
          @Override
          public void onResult(ToolResult result) {}

          @Override
          public void onError(Throwable error) {}

          @Override
          public void close() {}
        };
  }
}
