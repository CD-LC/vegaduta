package io.vegaduta.agent.springai;

import static org.assertj.core.api.Assertions.assertThat;

import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.approval.ApprovalRequest;
import io.vegaduta.agent.api.approval.ApprovalStatus;
import io.vegaduta.agent.api.audit.AuditEvent;
import io.vegaduta.agent.api.audit.AuditOutcome;
import io.vegaduta.agent.api.risk.RiskLabel;
import io.vegaduta.agent.api.risk.ToolClassification;
import io.vegaduta.agent.core.Governance;
import io.vegaduta.agent.core.GovernanceMode;
import io.vegaduta.agent.core.GovernancePolicy;
import io.vegaduta.agent.core.audit.InMemoryAuditSink;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

class GovernedToolCallbackTest {

  private final Payments payments = new Payments();
  private final InMemoryAuditSink audit = new InMemoryAuditSink();
  private final List<ToolClassification> scanned = GovernedMethodScanner.scan(payments);
  private final Governance governance =
      Governance.builder()
          .policy(GovernancePolicy.builder().mode(GovernanceMode.STRICT).build())
          .classifications(scanned)
          .auditSink(audit)
          .build();
  private final ToolGovernor governor = new ToolGovernor(governance);
  private final List<ToolCallback> raw =
      Arrays.asList(
          MethodToolCallbackProvider.builder().toolObjects(payments).build().getToolCallbacks());

  private ToolCallback find(List<ToolCallback> callbacks, String name) {
    return callbacks.stream()
        .filter(c -> c.getToolDefinition().name().equals(name))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void scannerReadsGovernedAnnotationsWithSpringAiToolNames() {
    assertThat(scanned)
        .extracting(ToolClassification::toolName)
        .containsExactlyInAnyOrder("refund", "order_status");
    ToolClassification refund =
        scanned.stream().filter(c -> c.toolName().equals("refund")).findFirst().orElseThrow();
    assertThat(refund.risk()).isEqualTo(RiskLabel.HIGH);
    assertThat(refund.requiresApproval()).isTrue();
    assertThat(refund.idempotent()).isTrue();
  }

  @Test
  void governedCallbackKeepsDefinitionAndGatesCalls() {
    List<ToolCallback> governed = governor.governAll(raw);
    ToolCallback refund = find(governed, "refund");
    ToolCallback status = find(governed, "order_status");
    ToolCallback helper = find(governed, "helper");

    assertThat(refund).isInstanceOf(GovernedToolCallback.class);
    assertThat(refund.getToolDefinition().description()).isEqualTo("Refund an order");
    assertThat(refund.getToolDefinition().inputSchema()).contains("orderId");

    RunContext ctx = RunContext.builder().principal("alice").build();
    ToolContext toolContext = new ToolContext(Map.of(RunContextResolver.TOOL_CONTEXT_KEY, ctx));

    assertThat(status.call("{\"orderId\":\"42\"}", toolContext)).contains("order 42 shipped");
    String reply = refund.call("{\"orderId\":\"42\",\"cents\":500}", toolContext);
    assertThat(reply).contains("requires human approval").contains("has NOT been executed");
    assertThat(payments.refunds).hasValue(0);
    assertThat(helper.call("{}", toolContext)).contains("not permitted");

    ApprovalRequest pending = governance.approvals().pending(null, 10).get(0);
    assertThat(pending.requestedBy()).isEqualTo("alice");
    ApprovalRequest done =
        governance.approvals().approve(UUID.fromString(pending.id().toString()), "bob");
    assertThat(done.status()).isEqualTo(ApprovalStatus.EXECUTED);
    assertThat(done.resultJson()).contains("refunded 500 for 42");
    assertThat(payments.refunds).hasValue(1);
    assertThat(audit.events())
        .extracting(AuditEvent::outcome)
        .contains(AuditOutcome.PENDING_APPROVAL, AuditOutcome.APPROVED);
  }

  @Test
  void governingIsIdempotentAndWorksForProviders() {
    ToolCallback once = governor.govern(raw.get(0));
    assertThat(governor.govern(raw.get(0))).isSameAs(once);
    assertThat(governor.govern(once)).isSameAs(once);

    ToolCallbackProvider provider = governor.govern(ToolCallbackProvider.from(raw));
    assertThat(provider.getToolCallbacks()).allMatch(c -> c instanceof GovernedToolCallback);
    assertThat(governor.govern(provider)).isSameAs(provider);
  }

  @Test
  @SuppressWarnings("try")
  void fallsBackToHolderThenAnonymousContext() {
    ToolCallback status = governor.govern(find(raw, "order_status"));
    RunContext bound = RunContext.builder().principal("carol").build();
    try (RunContextHolder.Scope ignored = RunContextHolder.bind(bound)) {
      status.call("{\"orderId\":\"1\"}");
    }
    assertThat(audit.events().get(audit.events().size() - 1).actorId()).isEqualTo("carol");
    status.call("{\"orderId\":\"1\"}");
    assertThat(audit.events().get(audit.events().size() - 1).actorId()).isEqualTo("anonymous");
  }
}
