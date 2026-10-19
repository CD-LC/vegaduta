package io.vegaduta.agent.testkit;

import io.vegaduta.agent.api.audit.AuditEvent;
import io.vegaduta.agent.api.tool.ToolResult;
import io.vegaduta.agent.core.audit.InMemoryAuditSink;
import java.util.List;

/**
 * Entry point: {@code import static io.vegaduta.agent.testkit.GovernanceAssertions.assertThat;}.
 */
public final class GovernanceAssertions {

  private GovernanceAssertions() {}

  public static AuditAssert assertThat(InMemoryAuditSink sink) {
    return new AuditAssert(sink.events());
  }

  public static AuditAssert assertThat(GovernanceTestKit kit) {
    return new AuditAssert(kit.events());
  }

  public static AuditAssert assertThatEvents(List<AuditEvent> events) {
    return new AuditAssert(events);
  }

  public static ToolResultAssert assertThat(ToolResult result) {
    return new ToolResultAssert(result);
  }
}
