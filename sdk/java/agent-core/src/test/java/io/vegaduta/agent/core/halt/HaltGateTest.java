package io.vegaduta.agent.core.halt;

import static org.assertj.core.api.Assertions.assertThat;

import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.audit.AuditSink;
import io.vegaduta.agent.api.gate.ToolDispatchGate;
import io.vegaduta.agent.api.gate.Verdict;
import io.vegaduta.agent.api.tool.ToolCall;
import org.junit.jupiter.api.Test;

class HaltGateTest {

  private final HaltSwitch halt = new HaltSwitch(AuditSink.discard());
  private final RunContext ctx = RunContext.builder().agentId("a1").build();
  private final ToolCall call = ToolCall.of("search", "{}");

  @Test
  void deniesWhileHaltedAndAllowsAfterRelease() {
    HaltGate gate = new HaltGate(halt);
    assertThat(gate.check(call, ctx)).isEqualTo(Verdict.allow());

    HaltRecord record = halt.engage(HaltScope.agent("a1"), "ops", "stop it");
    Verdict verdict = gate.check(call, ctx);
    assertThat(verdict.outcome()).isEqualTo(Verdict.Outcome.DENY);
    assertThat(verdict.reason()).isEqualTo("halted:" + record.id() + ":stop it");
    assertThat(HaltGate.isHaltReason(verdict.reason())).isTrue();

    halt.release(record.id(), "ops");
    assertThat(gate.check(call, ctx).allowed()).isTrue();
  }

  @Test
  void defersToDelegateWhenNotHalted() {
    ToolDispatchGate delegate = (c, context) -> Verdict.requireApproval("risk_threshold");
    HaltGate gate = new HaltGate(halt, delegate);

    assertThat(gate.check(call, ctx)).isEqualTo(Verdict.requireApproval("risk_threshold"));

    halt.engage(HaltScope.global(), "ops", "all stop");
    assertThat(gate.check(call, ctx).outcome()).isEqualTo(Verdict.Outcome.DENY);
    assertThat(HaltGate.isHaltReason("deny_list")).isFalse();
    assertThat(HaltGate.isHaltReason(null)).isFalse();
  }

  @Test
  void haltOnAnotherAgentDoesNotApply() {
    HaltGate gate = new HaltGate(halt);
    halt.engage(HaltScope.agent("someone-else"), "ops", "x");
    assertThat(gate.check(call, ctx).allowed()).isTrue();
  }
}
