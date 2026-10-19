package io.vegaduta.agent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vegaduta.agent.api.audit.AuditEvent;
import io.vegaduta.agent.api.audit.AuditOutcome;
import io.vegaduta.agent.api.budget.BudgetCharge;
import io.vegaduta.agent.api.risk.AsiRisk;
import io.vegaduta.agent.api.risk.RiskLabel;
import io.vegaduta.agent.api.risk.ToolClassification;
import io.vegaduta.agent.api.tool.ToolDescriptor;
import io.vegaduta.agent.api.tool.ToolResult;
import io.vegaduta.agent.api.tool.ToolStatus;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ApiContractTest {

  @Test
  void runContextIsImmutableAndDefaultsRunId() {
    RunContext ctx = RunContext.builder().tenantId("t1").attribute("k", "v").build();
    assertThat(ctx.runId()).isNotBlank();
    assertThat(ctx.attributes()).containsEntry("k", "v");
    assertThatThrownBy(() -> ctx.attributes().put("x", "y"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(ctx.principalOrAnonymous()).isEqualTo("anonymous");
  }

  @Test
  void classificationReplaySafety() {
    ToolClassification readOnly = ToolClassification.readOnly("search");
    assertThat(readOnly.replaySafe()).isTrue();
    ToolClassification refund =
        ToolClassification.builder("refund").risk(RiskLabel.HIGH).risk(AsiRisk.ASI02).build();
    assertThat(refund.replaySafe()).isFalse();
    assertThat(refund.toBuilder().idempotent(true).build().replaySafe()).isTrue();
    assertThat(refund.risks()).containsExactly(AsiRisk.ASI02);
  }

  @Test
  void riskLabelOrdering() {
    assertThat(RiskLabel.HIGH.atLeast(RiskLabel.MEDIUM)).isTrue();
    assertThat(RiskLabel.LOW.atLeast(RiskLabel.MEDIUM)).isFalse();
  }

  @Test
  void toolDescriptorRejectsBlankName() {
    assertThatThrownBy(() -> ToolDescriptor.of(" ", "d"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void toolResultFactories() {
    assertThat(ToolResult.ok("x").succeeded()).isTrue();
    assertThat(ToolResult.pendingApproval("id", "wait").status())
        .isEqualTo(ToolStatus.PENDING_APPROVAL);
    assertThat(ToolResult.pendingApproval("id", "wait").approvalId()).isEqualTo("id");
    assertThat(ToolResult.denied("deny_list", "no").succeeded()).isFalse();
  }

  @Test
  void budgetChargeArithmetic() {
    BudgetCharge sum = BudgetCharge.ONE_TOOL_CALL.plus(BudgetCharge.usd(new BigDecimal("0.5")));
    assertThat(sum.toolCalls()).isEqualTo(1);
    assertThat(sum.usd()).isEqualByComparingTo("0.5");
    assertThatThrownBy(() -> new BudgetCharge(-1, 0, BigDecimal.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void auditEventFlattensContractFieldsFirst() {
    RunContext ctx = RunContext.builder().tenantId("t1").principal("alice").build();
    AuditEvent event =
        AuditEvent.builder("tool.call", "tool", "refund")
            .context(ctx)
            .outcome(AuditOutcome.PENDING_APPROVAL)
            .risks(Set.of(AsiRisk.ASI02, AsiRisk.ASI09))
            .attribute("approvalId", "abc")
            .build();
    Map<String, String> flat = event.toFlatMap();
    assertThat(flat.keySet())
        .startsWith(
            "id", "createdAt", "actorId", "actionType", "targetType", "targetId", "outcome");
    assertThat(flat).containsEntry("actorId", "alice");
    assertThat(flat).containsEntry("tenantId", "t1");
    assertThat(flat).containsEntry("risks", "ASI02,ASI09");
    assertThat(flat).containsEntry("approvalId", "abc");
  }
}
