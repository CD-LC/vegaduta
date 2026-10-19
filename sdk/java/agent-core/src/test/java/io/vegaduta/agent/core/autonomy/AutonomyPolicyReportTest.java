package io.vegaduta.agent.core.autonomy;

import static org.assertj.core.api.Assertions.assertThat;

import io.vegaduta.agent.api.audit.AuditSink;
import io.vegaduta.agent.api.autonomy.AutonomyLevel;
import io.vegaduta.agent.api.risk.RiskLabel;
import io.vegaduta.agent.api.risk.ToolClassification;
import java.util.List;
import org.junit.jupiter.api.Test;

class AutonomyPolicyReportTest {

  @Test
  void listsLevelsAgentsRequirementsAndReach() {
    AutonomyRegistry registry =
        new AutonomyRegistry(AutonomyLevel.L3_ACT_BOUNDED, AuditSink.discard());
    registry.set("zeta", AutonomyLevel.L4_ACT_AUTONOMOUS, "ops", "trusted");
    registry.set("alpha", AutonomyLevel.L1_OBSERVE, "ops", "incident");
    List<ToolClassification> tools =
        List.of(
            ToolClassification.builder("send_email")
                .risk(RiskLabel.HIGH)
                .sideEffecting(true)
                .build(),
            ToolClassification.readOnly("search"));

    String md = new AutonomyPolicyReport(registry, tools).markdown();

    assertThat(md).startsWith("# Agent autonomy policy\n");
    assertThat(md).contains("Default level for unregistered agents: `L3_ACT_BOUNDED`");
    // Agents sorted by id.
    assertThat(md.indexOf("| `alpha` | `L1_OBSERVE` |"))
        .isGreaterThan(0)
        .isLessThan(md.indexOf("| `zeta` | `L4_ACT_AUTONOMOUS` |"));
    // Tools sorted by name, with their minimum level.
    assertThat(md).contains("| `search` | LOW | no | `L1_OBSERVE` | per level |");
    assertThat(md).contains("| `send_email` | HIGH | yes | `L4_ACT_AUTONOMOUS` | per level |");
    // Reach sections.
    assertThat(md)
        .contains("### `L1_OBSERVE`\n\n- **Executes:** `search`\n- **Needs approval:** none\n")
        .contains("- **Out of reach:** `send_email` (autonomy:L1_OBSERVE<L4_ACT_AUTONOMOUS)")
        .contains("- **Needs approval:** `send_email` (autonomy:recommend_only)")
        .contains("- **Needs approval:** `send_email` (autonomy:bounded_high_risk)")
        .contains("### `L4_ACT_AUTONOMOUS`\n\n- **Executes:** `search`, `send_email`\n");
  }

  @Test
  void isDeterministicAndHandlesEmptyInputs() {
    AutonomyRegistry registry =
        new AutonomyRegistry(AutonomyLevel.L2_RECOMMEND, AuditSink.discard());
    AutonomyPolicyReport report = new AutonomyPolicyReport(registry, List.of());
    String first = report.markdown();
    assertThat(first).isEqualTo(report.markdown());
    assertThat(first)
        .contains("_No agents registered; all run at the default level._")
        .contains("_No tool classifications supplied._");
  }

  @Test
  void hostileAgentAndToolNamesCannotReshapeTheReport() {
    AutonomyRegistry registry =
        new AutonomyRegistry(AutonomyLevel.L3_ACT_BOUNDED, AuditSink.discard());
    registry.set("a`|x\n| `evil` | `L4_ACT_AUTONOMOUS` |", AutonomyLevel.L1_OBSERVE, "o", "r");
    List<ToolClassification> tools =
        List.of(ToolClassification.builder("t|x\ny").risk(RiskLabel.LOW).build());

    String md = new AutonomyPolicyReport(registry, tools).markdown();

    assertThat(md).doesNotContain("| `evil` |");
    assertThat(md).doesNotContain("\n| `evil`");
    // No raw backtick inside a code span and no unescaped pipe from the names.
    assertThat(md).contains("| `a'\\|x \\| 'evil' \\| 'L4_ACT_AUTONOMOUS' \\|` | `L1_OBSERVE` |");
    assertThat(md).contains("- **Executes:** `t|x y`");
  }
}
