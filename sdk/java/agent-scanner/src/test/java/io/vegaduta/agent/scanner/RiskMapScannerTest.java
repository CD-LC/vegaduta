package io.vegaduta.agent.scanner;

import static org.assertj.core.api.Assertions.assertThat;

import io.vegaduta.agent.api.risk.AsiRisk;
import io.vegaduta.agent.api.risk.RiskLabel;
import io.vegaduta.agent.core.GovernanceMode;
import io.vegaduta.agent.core.GovernancePolicy;
import io.vegaduta.agent.scanner.fixtures.BillingTools;
import io.vegaduta.agent.scanner.fixtures.LegacyTools;
import io.vegaduta.agent.scanner.fixtures.MailTools;
import io.vegaduta.agent.scanner.fixtures.WebTools;
import java.util.List;
import org.junit.jupiter.api.Test;

class RiskMapScannerTest {

  private final RiskMapScanner scanner = new RiskMapScanner();

  @Test
  void flagsUnclassifiedToolsWithPolicyDefaultAssumed() {
    RiskMap map = scanner.scan(List.of(LegacyTools.class));

    assertThat(map.unclassified()).containsExactlyInAnyOrder("deleteCustomer", "legacy_export");
    ScannedTool delete = tool(map, "deleteCustomer");
    assertThat(delete.classified()).isFalse();
    assertThat(delete.classification().risk()).isEqualTo(RiskLabel.MEDIUM);
    assertThat(delete.classification().sideEffecting()).isTrue();
    assertThat(delete.approvalGated()).isFalse();
    assertThat(map.deductions())
        .extracting(RiskMap.Deduction::code)
        .contains("UNCLASSIFIED_TOOLS", "APPROVAL_GAP");
  }

  @Test
  void classifiedToolsReadTheirGovernedAnnotation() {
    RiskMap map = scanner.scan(List.of(BillingTools.class));

    ScannedTool refund = tool(map, "refund_order");
    assertThat(refund.classified()).isTrue();
    assertThat(refund.classification().risk()).isEqualTo(RiskLabel.HIGH);
    assertThat(refund.classification().requiresApproval()).isTrue();
    assertThat(refund.approvalGated()).isTrue();
    assertThat(refund.classification().risks()).containsExactly(AsiRisk.ASI02, AsiRisk.ASI05);
    assertThat(tool(map, "lookupOrder").classification().readsPrivateData()).isTrue();
  }

  @Test
  void fullyGovernedAndGatedSetScoresOneHundred() {
    RiskMap map = scanner.scan(List.of(BillingTools.class));

    assertThat(map.unclassified()).isEmpty();
    assertThat(map.approvalCoverage()).isEqualTo(100);
    assertThat(map.trifecta().holds()).isFalse();
    assertThat(map.deductions()).isEmpty();
    assertThat(map.score()).isEqualTo(100);
  }

  @Test
  void approvalCoverageCountsSideEffectingToolsOnly() {
    // MailTools: sendEmail MEDIUM (not gated), postWebhook HIGH (gated by threshold).
    RiskMap map = scanner.scan(List.of(MailTools.class));

    assertThat(map.sideEffectingCount()).isEqualTo(2);
    assertThat(map.approvalGatedCount()).isEqualTo(1);
    assertThat(map.approvalCoverage()).isEqualTo(50);
    assertThat(deduction(map, "APPROVAL_GAP").points()).isEqualTo(10);
  }

  @Test
  void detectsLethalTrifectaAndUngatedSink() {
    RiskMap map = scanner.scan(List.of(BillingTools.class, WebTools.class, MailTools.class));

    assertThat(map.trifecta().holds()).isTrue();
    assertThat(map.trifecta().privateReaders()).containsExactly("lookupOrder");
    assertThat(map.trifecta().untrustedConsumers()).containsExactly("fetchUrl");
    assertThat(map.trifecta().sinks()).containsExactlyInAnyOrder("sendEmail", "postWebhook");
    assertThat(deduction(map, "LETHAL_TRIFECTA").points())
        .isEqualTo(RiskMapScanner.TRIFECTA_POINTS);
    assertThat(deduction(map, "UNGATED_SINK").reason()).contains("sendEmail");
    assertThat(map.score()).isLessThan(60);
  }

  @Test
  void strictModeSoftensTrifectaBecauseSinksAreForcedToApproval() {
    RiskMapScanner strict =
        new RiskMapScanner(GovernancePolicy.builder().mode(GovernanceMode.STRICT).build());

    RiskMap map = strict.scan(List.of(BillingTools.class, WebTools.class, MailTools.class));

    assertThat(deduction(map, "LETHAL_TRIFECTA").points())
        .isEqualTo(RiskMapScanner.TRIFECTA_STRICT_POINTS);
    assertThat(map.deductions()).extracting(RiskMap.Deduction::code).doesNotContain("UNGATED_SINK");
  }

  @Test
  void lowerApprovalThresholdRaisesCoverage() {
    RiskMapScanner strictThreshold =
        new RiskMapScanner(
            GovernancePolicy.builder().approvalRequiredFrom(RiskLabel.MEDIUM).build());

    RiskMap map = strictThreshold.scan(List.of(MailTools.class));

    assertThat(map.approvalCoverage()).isEqualTo(100);
  }

  @Test
  void asiExposureCombinesDeclaredAndImpliedItems() {
    RiskMap map = scanner.scan(List.of(BillingTools.class, WebTools.class));

    assertThat(map.asiExposure().get(AsiRisk.ASI05)).containsExactly("refund_order");
    assertThat(map.asiExposure().get(AsiRisk.ASI03)).containsExactly("lookupOrder");
    assertThat(map.asiExposure().get(AsiRisk.ASI01)).containsExactly("fetchUrl");
    assertThat(map.asiExposure().get(AsiRisk.ASI06)).containsExactly("fetchUrl");
    assertThat(map.asiExposure().get(AsiRisk.ASI02)).containsExactly("refund_order");
    assertThat(map.asiExposure().get(AsiRisk.ASI10)).isEmpty();
    assertThat(map.asiItemsExposed()).isEqualTo(5);
    assertThat(deduction(map, "NO_ASI_TAGS").points()).isEqualTo(2);
  }

  @Test
  void scoreNeverGoesBelowZeroAndDeductionsAreOrdered() {
    RiskMap map =
        scanner.scan(
            List.of(BillingTools.class, WebTools.class, MailTools.class, LegacyTools.class));

    assertThat(map.score()).isBetween(0, 100);
    List<Integer> points = map.deductions().stream().map(RiskMap.Deduction::points).toList();
    assertThat(points).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    int sum = points.stream().mapToInt(Integer::intValue).sum();
    assertThat(map.score()).isEqualTo(Math.max(0, 100 - sum));
  }

  @Test
  void markdownAndJsonCarryTheReport() {
    RiskMap map = scanner.scan(List.of(BillingTools.class, WebTools.class, MailTools.class));

    String md = map.toMarkdown();
    assertThat(md)
        .contains("# VegaDuta risk map")
        .contains("Readiness score: " + map.score() + "/100")
        .contains("`refund_order`")
        .contains("| Lethal trifecta | PRESENT |")
        .contains("ASI02 | Tool Misuse and Exploitation");

    String json = map.toJson();
    assertThat(json)
        .startsWith("{\"score\":" + map.score())
        .contains("\"name\":\"refund_order\"")
        .contains("\"lethalTrifecta\":{\"holds\":true")
        .contains("\"risks\":[\"ASI02\",\"ASI05\"]")
        .contains("\"description\":null");
  }

  private static ScannedTool tool(RiskMap map, String name) {
    return map.tools().stream()
        .filter(t -> t.toolName().equals(name))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no tool " + name));
  }

  private static RiskMap.Deduction deduction(RiskMap map, String code) {
    return map.deductions().stream()
        .filter(d -> d.code().equals(code))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no deduction " + code + " in " + map.deductions()));
  }

  @Test
  void toolNamesWithPipesCannotBreakTheAsiTableOrTrifectaBullets() {
    RiskMap map =
        new RiskMapScanner(GovernancePolicy.defaults())
            .scanTools(
                new ToolMethodFinder()
                    .find(List.of(MailTools.class)).stream()
                        .map(
                            t ->
                                new FoundTool(
                                    "send|mail\nx",
                                    t.description(),
                                    t.method(),
                                    t.governed(),
                                    t.toolAnnotation()))
                        .toList());

    String md = map.toMarkdown();

    assertThat(md).doesNotContain("send|mail");
    assertThat(md).contains("send\\|mail x");
  }
}
