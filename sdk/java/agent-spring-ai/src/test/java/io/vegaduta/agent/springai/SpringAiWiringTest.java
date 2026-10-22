package io.vegaduta.agent.springai;

import static org.assertj.core.api.Assertions.assertThat;

import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.risk.RiskLabel;
import io.vegaduta.agent.api.risk.ToolClassification;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** The bean-to-classification wiring and the thread-bound RunContext. */
class SpringAiWiringTest {

  @Test
  void scannerFindsGovernedToolsAndSkipsUngovernedOnes() {
    List<ToolClassification> found = GovernedMethodScanner.scan(Payments.class);

    Map<String, ToolClassification> byName =
        found.stream().collect(Collectors.toMap(ToolClassification::toolName, c -> c));
    assertThat(byName)
        .containsKeys("refund", "order_status")
        .doesNotContainKey("helper"); // @Tool but no @Governed

    ToolClassification refund = byName.get("refund");
    assertThat(refund.risk()).isEqualTo(RiskLabel.HIGH);
    assertThat(refund.requiresApproval()).isTrue();
    assertThat(refund.idempotent()).isTrue();

    ToolClassification status = byName.get("order_status");
    assertThat(status.sideEffecting()).isFalse();
    assertThat(status.readsPrivateData()).isTrue();
  }

  @Test
  void runContextHolderBindsRestoresAndUnbinds() {
    assertThat(RunContextHolder.current()).isEmpty();

    RunContext outer = RunContext.builder().principal("outer").build();
    RunContext inner = RunContext.builder().principal("inner").build();

    try (RunContextHolder.Scope ignored = RunContextHolder.bind(outer)) {
      assertThat(RunContextHolder.current()).containsSame(outer);
      try (RunContextHolder.Scope ignored2 = RunContextHolder.bind(inner)) {
        assertThat(RunContextHolder.current()).containsSame(inner);
      }
      // The outer binding is restored when the inner scope closes.
      assertThat(RunContextHolder.current()).containsSame(outer);
    }
    assertThat(RunContextHolder.current()).isEmpty();
  }
}
