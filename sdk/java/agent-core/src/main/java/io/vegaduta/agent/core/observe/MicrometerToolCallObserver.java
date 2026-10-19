package io.vegaduta.agent.core.observe;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.observe.ToolCallObserver;
import io.vegaduta.agent.api.tool.ToolCall;
import io.vegaduta.agent.api.tool.ToolResult;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * Records {@code vegaduta.agent.tool.calls} (timer, tags {@code tool}, {@code status}) and {@code
 * vegaduta.agent.tool.errors} (counter, tag {@code tool}). Requires {@code micrometer-core} on the
 * classpath.
 */
public final class MicrometerToolCallObserver implements ToolCallObserver {

  public static final String CALLS = "vegaduta.agent.tool.calls";
  public static final String ERRORS = "vegaduta.agent.tool.errors";

  private final MeterRegistry registry;

  public MicrometerToolCallObserver(MeterRegistry registry) {
    this.registry = registry;
  }

  @Override
  public Scope onStart(ToolCall call, RunContext context) {
    long started = System.nanoTime();
    return new Scope() {
      private @Nullable String status;

      @Override
      public void onResult(ToolResult result) {
        status = result.status().name();
      }

      @Override
      public void onError(Throwable error) {
        status = "ERROR";
        registry.counter(ERRORS, "tool", call.toolName()).increment();
      }

      @Override
      public void close() {
        Timer.builder(CALLS)
            .tag("tool", call.toolName())
            .tag("status", status == null ? "UNKNOWN" : status)
            .register(registry)
            .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
      }
    };
  }
}
