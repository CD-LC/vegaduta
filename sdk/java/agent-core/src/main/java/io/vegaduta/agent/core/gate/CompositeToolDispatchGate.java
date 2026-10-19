package io.vegaduta.agent.core.gate;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.gate.ToolDispatchGate;
import io.vegaduta.agent.api.gate.Verdict;
import io.vegaduta.agent.api.tool.ToolCall;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Runs several gates in order and merges their verdicts: the first {@code DENY} wins and stops the
 * evaluation, otherwise the first {@code REQUIRE_APPROVAL} is returned, otherwise {@code ALLOW}.
 *
 * <p>{@link io.vegaduta.agent.core.Governance} composes its safety gates with this class in a fixed
 * order (halt, mandate, autonomy, provenance, then the policy gate), so a kill switch is consulted
 * before any policy and a demoted agent cannot be re-admitted by a permissive policy.
 */
@Experimental
public final class CompositeToolDispatchGate implements ToolDispatchGate {

  private final List<ToolDispatchGate> gates;

  private CompositeToolDispatchGate(List<ToolDispatchGate> gates) {
    this.gates = List.copyOf(gates);
  }

  /** A gate over {@code gates}; a single gate is returned unwrapped, no gate allows everything. */
  public static ToolDispatchGate of(ToolDispatchGate... gates) {
    return of(List.of(gates));
  }

  /** A gate over {@code gates}; a single gate is returned unwrapped, no gate allows everything. */
  public static ToolDispatchGate of(List<ToolDispatchGate> gates) {
    Objects.requireNonNull(gates, "gates");
    gates.forEach(g -> Objects.requireNonNull(g, "gate"));
    if (gates.size() == 1) {
      return gates.get(0);
    }
    return new CompositeToolDispatchGate(gates);
  }

  /** The member gates in evaluation order. */
  public List<ToolDispatchGate> gates() {
    return gates;
  }

  @Override
  public Verdict check(ToolCall call, RunContext context) {
    @Nullable Verdict approval = null;
    for (ToolDispatchGate gate : gates) {
      Verdict v = gate.check(call, context);
      if (v.outcome() == Verdict.Outcome.DENY) {
        return v;
      }
      if (v.outcome() == Verdict.Outcome.REQUIRE_APPROVAL && approval == null) {
        approval = v;
      }
    }
    return approval != null ? approval : Verdict.allow();
  }
}
