package io.vegaduta.agent.testkit;

import static io.vegaduta.agent.testkit.GovernanceAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.risk.AsiRisk;
import io.vegaduta.agent.api.risk.RiskLabel;
import io.vegaduta.agent.api.risk.ToolClassification;
import io.vegaduta.agent.api.tool.ToolResult;
import org.junit.jupiter.api.Test;

class GovernanceTestKitTest {

  @Test
  void approvalFlowReadsLikeASpecification() {
    GovernanceTestKit kit =
        GovernanceTestKit.strict()
            .classification(
                ToolClassification.builder("refund")
                    .risk(RiskLabel.HIGH)
                    .risk(AsiRisk.ASI02)
                    .build())
            .build();
    FakeTool refund = FakeTool.returning("refund", "refunded");
    RunContext alice = RunContext.builder().principal("alice").runId("r1").build();

    ToolResult first = kit.invoke(refund, "{\"order\":1}", alice);
    assertThat(first).isPendingApproval().hasOutputContaining("awaiting approval");
    assertThat(kit).hasPendingApprovalFor("refund").coversRisks(AsiRisk.ASI02, AsiRisk.ASI09);

    kit.approveAll("bob");
    assertThat(kit).hasSucceeded("refund").allHaveRunId("r1");
    org.assertj.core.api.Assertions.assertThat(refund.callCount()).isEqualTo(1);
  }

  @Test
  void denialsAndErrorsAreAssertable() {
    GovernanceTestKit kit = GovernanceTestKit.strict().build();

    assertThat(kit.invoke(FakeTool.echo("unknown"), "{}")).isDeniedBecause("unclassified");
    assertThat(kit).hasDenied("unknown");
    assertThatThrownBy(() -> assertThat(kit).hasNoDenials()).isInstanceOf(AssertionError.class);

    GovernanceTestKit warn = GovernanceTestKit.warn().build();
    assertThat(warn.invoke(FakeTool.failing("boom", new IllegalStateException("x")), "{}"))
        .isError();
  }
}
