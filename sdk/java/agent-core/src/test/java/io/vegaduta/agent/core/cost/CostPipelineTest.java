package io.vegaduta.agent.core.cost;

import static org.assertj.core.api.Assertions.assertThat;

import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.approval.ApprovalRequest;
import io.vegaduta.agent.api.audit.AuditEvent;
import io.vegaduta.agent.api.audit.AuditOutcome;
import io.vegaduta.agent.api.budget.BudgetCharge;
import io.vegaduta.agent.api.risk.RiskLabel;
import io.vegaduta.agent.api.risk.ToolClassification;
import io.vegaduta.agent.api.tool.Tool;
import io.vegaduta.agent.api.tool.ToolCall;
import io.vegaduta.agent.api.tool.ToolDescriptor;
import io.vegaduta.agent.api.tool.ToolResult;
import io.vegaduta.agent.api.tool.ToolStatus;
import io.vegaduta.agent.core.Governance;
import io.vegaduta.agent.core.GovernanceMode;
import io.vegaduta.agent.core.GovernancePolicy;
import io.vegaduta.agent.core.GovernedTool;
import io.vegaduta.agent.core.audit.InMemoryAuditSink;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The observer and the audit sink plugged into a real {@link Governance} pipeline. */
class CostPipelineTest {

  private static final CostModel MODEL =
      CostModel.builder()
          .toolCost("echo", "0.010")
          .toolCost("wire", "0.250")
          .defaultToolCost("0.001")
          .humanReviewCost("2.00")
          .build();

  private static Tool tool(String name) {
    return new Tool() {
      @Override
      public ToolDescriptor descriptor() {
        return ToolDescriptor.of(name, name);
      }

      @Override
      public String call(String argumentsJson, RunContext context) {
        return argumentsJson;
      }
    };
  }

  @Test
  void observerRecordsSuccessDeniedAndBudgetExceeded() {
    CostLedger ledger = new CostLedger(MODEL);
    CostAwareBudgetProvider budgets =
        CostAwareBudgetProvider.inMemory(MODEL, new BudgetCharge(2, 0, BigDecimal.ZERO));
    Governance governance =
        Governance.builder()
            .policy(GovernancePolicy.builder().mode(GovernanceMode.STRICT).build())
            .classification(ToolClassification.readOnly("echo"))
            .budgets(budgets)
            .auditSink(new InMemoryAuditSink())
            .observer(new CostObserver(ledger))
            .build();
    RunContext ctx = CostAttribution.tag(RunContext.ofRun("run-1"), "payments", "refunds");
    GovernedTool echo = governance.govern(tool("echo"));

    ToolResult ok = echo.invoke(ToolCall.of("echo", "{\"a\":1}"), ctx);
    GovernedTool mystery = governance.govern(tool("mystery"));
    ToolResult denied = mystery.invoke(ToolCall.of("mystery", "{}"), ctx);
    ToolResult ok2 = echo.invoke(ToolCall.of("echo", "{\"a\":2}"), ctx);
    ToolResult exceeded = echo.invoke(ToolCall.of("echo", "{\"a\":3}"), ctx);

    assertThat(ok.status()).isEqualTo(ToolStatus.OK);
    assertThat(denied.status()).isEqualTo(ToolStatus.DENIED);
    assertThat(ok2.status()).isEqualTo(ToolStatus.OK);
    assertThat(exceeded.status()).isEqualTo(ToolStatus.BUDGET_EXCEEDED);

    assertThat(ledger.toolCalls()).hasSize(4);
    assertThat(ledger.toolCalls().get(0).usd()).isEqualByComparingTo("0.010");
    assertThat(ledger.toolCalls().get(0).status()).isEqualTo(ToolStatus.OK);
    assertThat(ledger.toolCalls().get(1).usd()).isEqualByComparingTo("0");
    assertThat(ledger.toolCalls().get(1).status()).isEqualTo(ToolStatus.DENIED);
    assertThat(ledger.toolCalls().get(3).usd()).isEqualByComparingTo("0");
    assertThat(ledger.toolCalls().get(3).status()).isEqualTo(ToolStatus.BUDGET_EXCEEDED);
    assertThat(ledger.toolUsd()).isEqualByComparingTo("0.020");

    ShowbackReport report = ShowbackReport.from(ledger);
    ShowbackReport.Row payments = report.byTeam().get(0);
    assertThat(payments.key()).isEqualTo("payments");
    assertThat(payments.runs()).isEqualTo(1);
    assertThat(payments.toolCalls()).isEqualTo(4);
    assertThat(payments.denied()).isEqualTo(1);
    assertThat(payments.budgetExceeded()).isEqualTo(1);
  }

  @Test
  void auditSinkChargesHumanReviewOnApprovalDecisions() {
    CostLedger ledger = new CostLedger(MODEL);
    Governance governance =
        Governance.builder()
            .policy(GovernancePolicy.builder().mode(GovernanceMode.STRICT).build())
            .classification(
                ToolClassification.builder("wire")
                    .risk(RiskLabel.HIGH)
                    .requiresApproval(true)
                    .build())
            .auditSink(new InMemoryAuditSink())
            .auditSink(new CostAuditSink(ledger))
            .observer(new CostObserver(ledger))
            .build();
    RunContext ctx = RunContext.builder().runId("run-9").principal("agent-user").build();
    GovernedTool wire = governance.govern(tool("wire"));

    ToolResult pending = wire.invoke(ToolCall.of("wire", "{\"amount\":100}"), ctx);
    assertThat(pending.status()).isEqualTo(ToolStatus.PENDING_APPROVAL);
    // Parked for approval: the tool did not run, so nothing is charged yet.
    assertThat(ledger.toolUsd()).isEqualByComparingTo("0");
    assertThat(ledger.reviews()).isEmpty();

    ApprovalRequest decided =
        governance.approvals().approve(UUID.fromString(pending.approvalId()), "reviewer");
    assertThat(decided.status().name()).isEqualTo("EXECUTED");

    // One human decision was charged, once: APPROVED counts, the EXECUTED follow-up does not.
    assertThat(ledger.reviews()).hasSize(1);
    assertThat(ledger.reviews().get(0).approvalId()).isEqualTo(pending.approvalId());
    assertThat(ledger.reviews().get(0).context().runId()).isEqualTo("run-9");
    assertThat(ledger.reviewUsd()).isEqualByComparingTo("2.00");
  }

  @Test
  void auditSinkIgnoresEventsThatAreNotHumanDecisions() {
    CostLedger ledger = new CostLedger(MODEL);
    CostAuditSink sink = new CostAuditSink(ledger);
    RunContext ctx = RunContext.ofRun("r");

    sink.emit(AuditEvent.builder("tool.call", "tool", "echo").context(ctx).build());
    sink.emit(
        AuditEvent.builder("approval.requested", "approval", "a1")
            .context(ctx)
            .outcome(AuditOutcome.PENDING_APPROVAL)
            .build());
    sink.emit(
        AuditEvent.builder("approval.decided", "approval", "a1")
            .context(ctx)
            .outcome(AuditOutcome.EXPIRED)
            .build());
    assertThat(ledger.reviews()).isEmpty();

    sink.emit(
        AuditEvent.builder("approval.decided", "approval", "a1")
            .context(ctx)
            .outcome(AuditOutcome.REJECTED)
            .build());
    assertThat(ledger.reviews()).hasSize(1);
  }

  @Test
  void costAwareBudgetProviderEstimatesPerTool() {
    CostAwareBudgetProvider budgets =
        CostAwareBudgetProvider.inMemory(MODEL, new BudgetCharge(10, 0, new BigDecimal("0.30")));

    BudgetCharge wire = budgets.estimateFor(ToolClassification.readOnly("wire"));
    assertThat(wire.toolCalls()).isEqualTo(1);
    assertThat(wire.usd()).isEqualByComparingTo("0.250");
    assertThat(budgets.estimateFor("unlisted").usd()).isEqualByComparingTo("0.001");

    RunContext ctx = RunContext.ofRun("b1");
    assertThat(budgets.budgetFor(ctx)).isSameAs(budgets.budgetFor(ctx));
    budgets.budgetFor(ctx).reserve(wire).commit(wire);
    assertThat(budgets.budgetFor(ctx).spent().usd()).isEqualByComparingTo("0.250");
    assertThat(budgets.budgetFor(ctx).exhausted()).isFalse();
    assertThat(budgets.budgetFor(RunContext.ofRun("b2")).spent().usd()).isEqualByComparingTo("0");
  }
}
