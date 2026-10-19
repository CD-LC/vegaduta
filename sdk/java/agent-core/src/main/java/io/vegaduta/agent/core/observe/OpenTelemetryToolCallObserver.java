package io.vegaduta.agent.core.observe;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.observe.ToolCallObserver;
import io.vegaduta.agent.api.tool.ToolCall;
import io.vegaduta.agent.api.tool.ToolResult;
import org.jspecify.annotations.Nullable;

/**
 * Emits one {@code execute_tool <name>} span per governed call with the OpenTelemetry GenAI
 * semantic-convention attributes (the conventions are still marked Development upstream; the
 * attribute names used here are the ones in semconv 1.3x) plus {@code vegaduta.*} attributes for
 * the governance outcome. Requires {@code opentelemetry-api} on the classpath.
 */
public final class OpenTelemetryToolCallObserver implements ToolCallObserver {

  public static final String INSTRUMENTATION_NAME = "io.vegaduta.agent";

  static final AttributeKey<String> OPERATION_NAME =
      AttributeKey.stringKey("gen_ai.operation.name");
  static final AttributeKey<String> TOOL_NAME = AttributeKey.stringKey("gen_ai.tool.name");
  static final AttributeKey<String> TOOL_CALL_ID = AttributeKey.stringKey("gen_ai.tool.call.id");
  static final AttributeKey<String> CONVERSATION_ID =
      AttributeKey.stringKey("gen_ai.conversation.id");
  static final AttributeKey<String> AGENT_ID = AttributeKey.stringKey("gen_ai.agent.id");
  static final AttributeKey<String> RUN_ID = AttributeKey.stringKey("vegaduta.run.id");
  static final AttributeKey<String> TENANT_ID = AttributeKey.stringKey("vegaduta.tenant.id");
  static final AttributeKey<String> STATUS = AttributeKey.stringKey("vegaduta.tool.status");
  static final AttributeKey<String> REASON = AttributeKey.stringKey("vegaduta.tool.reason");
  static final AttributeKey<String> APPROVAL_ID = AttributeKey.stringKey("vegaduta.approval.id");

  private final Tracer tracer;

  public OpenTelemetryToolCallObserver(Tracer tracer) {
    this.tracer = tracer;
  }

  public static OpenTelemetryToolCallObserver of(io.opentelemetry.api.OpenTelemetry otel) {
    return new OpenTelemetryToolCallObserver(otel.getTracer(INSTRUMENTATION_NAME));
  }

  @Override
  public ToolCallObserver.Scope onStart(ToolCall call, RunContext context) {
    Span span =
        tracer
            .spanBuilder("execute_tool " + call.toolName())
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute(OPERATION_NAME, "execute_tool")
            .setAttribute(TOOL_NAME, call.toolName())
            .setAttribute(TOOL_CALL_ID, call.callId())
            .setAttribute(RUN_ID, context.runId())
            .startSpan();
    setIfPresent(span, CONVERSATION_ID, context.sessionId());
    setIfPresent(span, AGENT_ID, context.agentId());
    setIfPresent(span, TENANT_ID, context.tenantId());
    io.opentelemetry.context.Scope otelScope = span.makeCurrent();
    return new ToolCallObserver.Scope() {
      @Override
      public void onResult(ToolResult result) {
        span.setAttribute(STATUS, result.status().name());
        setIfPresent(span, REASON, result.reason());
        setIfPresent(span, APPROVAL_ID, result.approvalId());
        if (!result.succeeded()) {
          span.setStatus(StatusCode.ERROR, result.status().name());
        }
      }

      @Override
      public void onError(Throwable error) {
        span.recordException(error);
        span.setStatus(StatusCode.ERROR, error.toString());
      }

      @Override
      public void close() {
        otelScope.close();
        span.end();
      }
    };
  }

  private static void setIfPresent(Span span, AttributeKey<String> key, @Nullable String value) {
    if (value != null) {
      span.setAttribute(key, value);
    }
  }
}
