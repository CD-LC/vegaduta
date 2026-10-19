package io.vegaduta.agent.core.gate;

import static org.assertj.core.api.Assertions.assertThat;

import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.gate.ToolDispatchGate;
import io.vegaduta.agent.api.gate.Verdict;
import io.vegaduta.agent.api.tool.ToolCall;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CompositeToolDispatchGateTest {

  private static final ToolCall CALL = ToolCall.of("t", "{}");
  private static final RunContext CTX = RunContext.anonymous();

  private static ToolDispatchGate of(Verdict v) {
    return (call, context) -> v;
  }

  @Test
  void firstDenyWinsEvenAfterAnApprovalAndStopsEvaluation() {
    AtomicInteger later = new AtomicInteger();
    ToolDispatchGate counting =
        (call, context) -> {
          later.incrementAndGet();
          return Verdict.allow();
        };
    ToolDispatchGate gate =
        CompositeToolDispatchGate.of(
            of(Verdict.requireApproval("a")), of(Verdict.deny("d")), counting);

    Verdict v = gate.check(CALL, CTX);

    assertThat(v.outcome()).isEqualTo(Verdict.Outcome.DENY);
    assertThat(v.reason()).isEqualTo("d");
    assertThat(later).as("gates after the deny are not consulted").hasValue(0);
  }

  @Test
  void firstApprovalIsKeptWhenALaterGateAllows() {
    ToolDispatchGate gate =
        CompositeToolDispatchGate.of(
            of(Verdict.allow()),
            of(Verdict.requireApproval("first")),
            of(Verdict.requireApproval("second")),
            of(Verdict.allow()));

    Verdict v = gate.check(CALL, CTX);

    assertThat(v.outcome()).isEqualTo(Verdict.Outcome.REQUIRE_APPROVAL);
    assertThat(v.reason()).isEqualTo("first");
  }

  @Test
  void allAllowIsAllowAndASingleGateIsReturnedUnwrapped() {
    ToolDispatchGate only = of(Verdict.allow());
    assertThat(CompositeToolDispatchGate.of(only)).isSameAs(only);
    assertThat(CompositeToolDispatchGate.of(List.of()).check(CALL, CTX)).isEqualTo(Verdict.allow());
    ToolDispatchGate two = CompositeToolDispatchGate.of(of(Verdict.allow()), of(Verdict.allow()));
    assertThat(two).isInstanceOf(CompositeToolDispatchGate.class);
    assertThat(two.check(CALL, CTX)).isEqualTo(Verdict.allow());
    assertThat(((CompositeToolDispatchGate) two).gates()).hasSize(2);
  }
}
