package io.vegaduta.agent.core.halt;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.gate.ToolDispatchGate;
import io.vegaduta.agent.api.gate.Verdict;
import io.vegaduta.agent.api.tool.ToolCall;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A {@link ToolDispatchGate} that denies every call under an active halt and otherwise defers to a
 * delegate gate (or allows, when there is none). Put it first in the gate chain so a halt wins over
 * allow lists, classifications and approvals:
 *
 * <pre>{@code
 * HaltSwitch halt = new HaltSwitch(auditSink);
 * Governance governance = Governance.builder()
 *     .gate(new HaltGate(halt, new PolicyToolDispatchGate(resolver)))
 *     .build();
 * }</pre>
 *
 * <p>The denial reason is {@code halted:<haltId>:<reason>}; {@link #isHaltReason(String)} tells a
 * reason produced here from any other gate's.
 */
@Experimental
public final class HaltGate implements ToolDispatchGate {

  public static final String REASON_PREFIX = "halted:";

  private final HaltSwitch haltSwitch;
  private final @Nullable ToolDispatchGate delegate;

  /** A gate that allows everything that is not halted. */
  public HaltGate(HaltSwitch haltSwitch) {
    this(haltSwitch, null);
  }

  /** A gate that denies what is halted and lets {@code delegate} decide the rest. */
  public HaltGate(HaltSwitch haltSwitch, @Nullable ToolDispatchGate delegate) {
    this.haltSwitch = Objects.requireNonNull(haltSwitch, "haltSwitch");
    this.delegate = delegate;
  }

  @Override
  public Verdict check(ToolCall call, RunContext context) {
    Optional<HaltRecord> halted = haltSwitch.isHalted(context);
    if (halted.isPresent()) {
      return Verdict.deny(reasonFor(halted.get()));
    }
    return delegate == null ? Verdict.allow() : delegate.check(call, context);
  }

  public HaltSwitch haltSwitch() {
    return haltSwitch;
  }

  /** The denial reason this gate produces for {@code record}. */
  public static String reasonFor(HaltRecord record) {
    return REASON_PREFIX + record.id() + ":" + record.reason();
  }

  /** Whether a verdict or {@code ToolResult} reason came from a halt. */
  public static boolean isHaltReason(@Nullable String reason) {
    return reason != null && reason.startsWith(REASON_PREFIX);
  }
}
