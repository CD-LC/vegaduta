package io.vegaduta.agent.core.journal;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.GovernanceException;
import java.time.Instant;
import java.util.Objects;

/**
 * Raised by {@link ReconcilingEffectJournal#lookup} under {@link UnknownEffectPolicy#HOLD} when a
 * call was interrupted and the reconciler cannot say whether its effect happened.
 *
 * <p>{@code GovernedTool} consults the journal before the gate and outside the try/catch that wraps
 * the delegate call, so this exception propagates out of {@code invoke} unless the pipeline catches
 * it. Hosts that call the journal directly should treat it as "do not retry; a human must look".
 * The intended pipeline handling is {@code ToolResult.error("effect_unresolved", ...)}.
 */
@Experimental
public class EffectUnresolvedException extends GovernanceException {

  private static final long serialVersionUID = 1L;

  private final String key;
  private final Instant since;
  private final String reason;

  public EffectUnresolvedException(String key, Instant since, String reason) {
    super(
        "Effect "
            + key
            + " was interrupted at "
            + since
            + " and could not be reconciled ("
            + reason
            + "); held to prevent a duplicate side effect");
    this.key = Objects.requireNonNull(key, "key");
    this.since = Objects.requireNonNull(since, "since");
    this.reason = Objects.requireNonNull(reason, "reason");
  }

  /** The effect key that is on hold. */
  public String key() {
    return key;
  }

  /** When the interrupted call began. */
  public Instant since() {
    return since;
  }

  /** The reconciler's reason for not knowing. */
  public String reason() {
    return reason;
  }
}
