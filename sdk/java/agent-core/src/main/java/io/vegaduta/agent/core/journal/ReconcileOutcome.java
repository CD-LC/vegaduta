package io.vegaduta.agent.core.journal;

import io.vegaduta.agent.api.Experimental;
import java.util.Objects;

/**
 * The host's answer to "did the interrupted side effect actually happen?".
 *
 * <p>{@link Completed} means the legacy system shows the effect (the payment posted, the order
 * exists): the journal records the result and later lookups replay it. {@link NotPerformed} means
 * the system shows no trace of it, so the call may safely run again. {@link Unknown} means the host
 * cannot tell; what happens then is decided by the {@link UnknownEffectPolicy}.
 */
@Experimental
public sealed interface ReconcileOutcome {

  /** The effect happened and this is its result, as the tool would have returned it. */
  record Completed(String result) implements ReconcileOutcome {
    public Completed {
      Objects.requireNonNull(result, "result");
    }
  }

  /** The effect never reached the target system. */
  record NotPerformed() implements ReconcileOutcome {}

  /** The host cannot determine whether the effect happened. */
  record Unknown(String reason) implements ReconcileOutcome {
    public Unknown {
      Objects.requireNonNull(reason, "reason");
    }
  }

  static ReconcileOutcome completed(String result) {
    return new Completed(result);
  }

  static ReconcileOutcome notPerformed() {
    return NOT_PERFORMED;
  }

  static ReconcileOutcome unknown(String reason) {
    return new Unknown(reason);
  }

  /** Shared instance of {@link NotPerformed}. */
  ReconcileOutcome NOT_PERFORMED = new NotPerformed();
}
