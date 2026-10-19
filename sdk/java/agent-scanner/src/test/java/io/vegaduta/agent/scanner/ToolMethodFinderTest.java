package io.vegaduta.agent.scanner;

import static org.assertj.core.api.Assertions.assertThat;

import io.vegaduta.agent.scanner.fixtures.BillingTools;
import io.vegaduta.agent.scanner.fixtures.LegacyTools;
import io.vegaduta.agent.scanner.fixtures.MailTools;
import io.vegaduta.agent.scanner.fixtures.WebTools;
import java.util.List;
import org.junit.jupiter.api.Test;

class ToolMethodFinderTest {

  private final ToolMethodFinder finder = new ToolMethodFinder();

  @Test
  void findsToolAndGovernedMethodsAndSkipsHelpersAndStatics() {
    List<FoundTool> found =
        finder.find(List.of(BillingTools.class, MailTools.class, LegacyTools.class));

    assertThat(found)
        .extracting(FoundTool::toolName)
        .containsExactlyInAnyOrder(
            "refund_order",
            "lookupOrder",
            "sendEmail",
            "postWebhook",
            "deleteCustomer",
            "legacy_export");
  }

  @Test
  void toolNameComesFromAnnotationAttributeElseMethodName() {
    List<FoundTool> found = finder.find(List.of(BillingTools.class));

    FoundTool refund = byName(found, "refund_order");
    assertThat(refund.method().getName()).isEqualTo("refund");
    assertThat(refund.description()).isEqualTo("Refund an order");
    assertThat(refund.toolAnnotation()).isEqualTo("io.vegaduta.agent.scanner.fixtures.Tool");
    assertThat(refund.governed()).isTrue();
    assertThat(refund.location()).isEqualTo(BillingTools.class.getName() + "#refund");
  }

  @Test
  void governedOnlyMethodIsFoundWithoutToolAnnotation() {
    FoundTool webhook = byName(finder.find(List.of(MailTools.class)), "postWebhook");

    assertThat(webhook.toolAnnotation()).isNull();
    assertThat(webhook.description()).isNull();
    assertThat(webhook.governed()).isTrue();
  }

  @Test
  void classLevelGovernedMarksMethodsAsGoverned() {
    FoundTool fetch = byName(finder.find(List.of(WebTools.class)), "fetchUrl");

    assertThat(fetch.governed()).isTrue();
  }

  @Test
  void unannotatedFrameworkToolIsNotGoverned() {
    FoundTool delete = byName(finder.find(List.of(LegacyTools.class)), "deleteCustomer");

    assertThat(delete.governed()).isFalse();
    assertThat(delete.description()).isEqualTo("Delete a customer record");
  }

  private static FoundTool byName(List<FoundTool> found, String name) {
    return found.stream()
        .filter(t -> t.toolName().equals(name))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no tool " + name));
  }
}
