package io.vegaduta.agent.core.autonomy;

import static org.assertj.core.api.Assertions.assertThat;

import io.vegaduta.agent.api.autonomy.AutonomyLevel;
import io.vegaduta.agent.api.autonomy.AutonomyRequirement;
import io.vegaduta.agent.api.risk.RiskLabel;
import io.vegaduta.agent.api.risk.ToolClassification;
import org.junit.jupiter.api.Test;

class AutonomyRequirementTest {

  @Test
  void readOnlyToolsNeedOnlyObserve() {
    AutonomyRequirement r =
        AutonomyRequirement.forClassification(ToolClassification.readOnly("search"));
    assertThat(r.minimumLevel()).isEqualTo(AutonomyLevel.L1_OBSERVE);
    assertThat(r.approvalRequired()).isFalse();
    assertThat(r.satisfiedBy(AutonomyLevel.L1_OBSERVE)).isTrue();
  }

  @Test
  void lowAndMediumSideEffectsNeedBoundedAction() {
    for (RiskLabel risk : new RiskLabel[] {RiskLabel.LOW, RiskLabel.MEDIUM}) {
      AutonomyRequirement r =
          AutonomyRequirement.forClassification(
              ToolClassification.builder("write").risk(risk).sideEffecting(true).build());
      assertThat(r.minimumLevel()).isEqualTo(AutonomyLevel.L3_ACT_BOUNDED);
      assertThat(r.approvalRequired()).isFalse();
      assertThat(r.satisfiedBy(AutonomyLevel.L2_RECOMMEND)).isFalse();
    }
  }

  @Test
  void highAndCriticalSideEffectsNeedFullAutonomy() {
    for (RiskLabel risk : new RiskLabel[] {RiskLabel.HIGH, RiskLabel.CRITICAL}) {
      AutonomyRequirement r =
          AutonomyRequirement.forClassification(
              ToolClassification.builder("pay").risk(risk).sideEffecting(true).build());
      assertThat(r.minimumLevel()).isEqualTo(AutonomyLevel.L4_ACT_AUTONOMOUS);
      assertThat(r.approvalRequired()).isEqualTo(risk == RiskLabel.CRITICAL);
    }
  }

  @Test
  void requiresApprovalKeepsHighRiskAtBoundedWithApproval() {
    AutonomyRequirement r =
        AutonomyRequirement.forClassification(
            ToolClassification.builder("wire")
                .risk(RiskLabel.CRITICAL)
                .sideEffecting(true)
                .requiresApproval(true)
                .build());
    assertThat(r.minimumLevel()).isEqualTo(AutonomyLevel.L3_ACT_BOUNDED);
    assertThat(r.approvalRequired()).isTrue();
  }

  @Test
  void levelsAreOrdered() {
    assertThat(AutonomyLevel.L4_ACT_AUTONOMOUS.atLeast(AutonomyLevel.L1_OBSERVE)).isTrue();
    assertThat(AutonomyLevel.L1_OBSERVE.below(AutonomyLevel.L2_RECOMMEND)).isTrue();
    assertThat(AutonomyLevel.parse(" l3_act_bounded ")).isEqualTo(AutonomyLevel.L3_ACT_BOUNDED);
  }
}
