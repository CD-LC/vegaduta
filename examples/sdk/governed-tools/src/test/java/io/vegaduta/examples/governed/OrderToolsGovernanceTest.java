package io.vegaduta.examples.governed;

import static io.vegaduta.agent.testkit.GovernanceAssertions.assertThat;

import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.approval.ApprovalGate;
import io.vegaduta.agent.api.risk.AsiRisk;
import io.vegaduta.agent.core.Governance;
import io.vegaduta.agent.core.audit.InMemoryAuditSink;
import io.vegaduta.agent.springai.RunContextResolver;
import io.vegaduta.agent.springai.ToolGovernor;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.TestPropertySource;

/**
 * Governance is testable without a model: govern the tool callbacks the way the advisor would
 * and assert on the audit trail. No Ollama needed.
 */
@SpringBootTest
@TestPropertySource(properties = {"spring.ai.ollama.base-url=http://localhost:1", "vegaduta.agent.store.type=memory"})
class OrderToolsGovernanceTest {

  @TestConfiguration
  static class Recording {
    @Bean
    InMemoryAuditSink recordingAudit() {
      return new InMemoryAuditSink();
    }
  }

  @Autowired OrderTools tools;
  @Autowired ToolGovernor governor;
  @Autowired ApprovalGate approvals;
  @Autowired InMemoryAuditSink audit;
  @Autowired Governance governance;

  @Test
  void refundWaitsForAHumanAndStatusDoesNot() {
    ToolCallback[] callbacks = MethodToolCallbackProvider.builder().toolObjects(tools).build().getToolCallbacks();
    RunContext alice = RunContext.builder().principal("alice").agentId("support").build();
    ToolContext ctx = new ToolContext(Map.of(RunContextResolver.TOOL_CONTEXT_KEY, alice));

    for (ToolCallback raw : governor.governAll(java.util.List.of(callbacks), alice)) {
      String name = raw.getToolDefinition().name();
      if (name.equals("orderStatus")) {
        org.assertj.core.api.Assertions.assertThat(raw.call("{\"orderId\":\"42\"}", ctx)).contains("shipped");
      } else if (name.equals("refund")) {
        org.assertj.core.api.Assertions.assertThat(raw.call("{\"orderId\":\"42\",\"cents\":500}", ctx))
            .contains("awaiting approval");
      }
    }
    org.assertj.core.api.Assertions.assertThat(tools.refunded()).isEmpty();
    assertThat(audit).hasSucceeded("orderStatus").hasPendingApprovalFor("refund").coversRisks(AsiRisk.ASI02, AsiRisk.ASI09);

    approvals.pending(null, 10).forEach(r -> approvals.approve(r.id(), "bob"));

    org.assertj.core.api.Assertions.assertThat(tools.refunded()).containsEntry("42", 500L);
    assertThat(audit).hasNoDenials();
  }
}
