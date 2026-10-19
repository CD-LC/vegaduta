package io.vegaduta.agent.core.observe;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.risk.ToolClassification;
import io.vegaduta.agent.api.tool.Tool;
import io.vegaduta.agent.api.tool.ToolCall;
import io.vegaduta.agent.api.tool.ToolDescriptor;
import io.vegaduta.agent.core.Governance;
import io.vegaduta.agent.core.GovernanceMode;
import io.vegaduta.agent.core.GovernancePolicy;
import io.vegaduta.agent.core.audit.InMemoryAuditSink;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class ObserversTest {

  @RegisterExtension static final OpenTelemetryExtension otel = OpenTelemetryExtension.create();

  private static final Tool ECHO =
      new Tool() {
        @Override
        public ToolDescriptor descriptor() {
          return ToolDescriptor.of("echo", "echo");
        }

        @Override
        public String call(String argumentsJson, RunContext context) {
          return argumentsJson;
        }
      };

  @Test
  void micrometerAndOpenTelemetryObserveEveryCall() {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    Governance governance =
        Governance.builder()
            .policy(GovernancePolicy.builder().mode(GovernanceMode.STRICT).build())
            .classification(ToolClassification.readOnly("echo"))
            .auditSink(new InMemoryAuditSink())
            .observer(new MicrometerToolCallObserver(meters))
            .observer(OpenTelemetryToolCallObserver.of(otel.getOpenTelemetry()))
            .build();
    RunContext ctx = RunContext.builder().sessionId("s1").agentId("a1").tenantId("t1").build();

    governance.govern(ECHO).invoke(ToolCall.of("echo", "{}"), ctx);
    governance.govern(ECHO).invoke(ToolCall.of("mystery", "{}"), ctx);

    assertThat(meters.find(MicrometerToolCallObserver.CALLS).tag("status", "OK").timer().count())
        .isEqualTo(1);
    assertThat(
            meters.find(MicrometerToolCallObserver.CALLS).tag("status", "DENIED").timer().count())
        .isEqualTo(1);

    List<SpanData> spans = otel.getSpans();
    assertThat(spans).hasSize(2);
    SpanData ok = spans.get(0);
    assertThat(ok.getName()).isEqualTo("execute_tool echo");
    assertThat(ok.getKind()).isEqualTo(SpanKind.INTERNAL);
    assertThat(ok.getAttributes().get(OpenTelemetryToolCallObserver.OPERATION_NAME))
        .isEqualTo("execute_tool");
    assertThat(ok.getAttributes().get(OpenTelemetryToolCallObserver.CONVERSATION_ID))
        .isEqualTo("s1");
    assertThat(ok.getAttributes().get(OpenTelemetryToolCallObserver.AGENT_ID)).isEqualTo("a1");
    assertThat(ok.getAttributes().get(OpenTelemetryToolCallObserver.STATUS)).isEqualTo("OK");
    assertThat(spans.get(1).getAttributes().get(OpenTelemetryToolCallObserver.STATUS))
        .isEqualTo("DENIED");
  }
}
