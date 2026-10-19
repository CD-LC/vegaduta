package io.vegaduta.agent.api.guardrail;

import io.vegaduta.agent.api.GovernanceException;
import java.util.List;

/** Thrown by adapters that stop a turn on a blocking violation. */
public class GuardrailViolationException extends GovernanceException {

  private static final long serialVersionUID = 1L;

  private final GuardrailStage stage;
  private final transient List<GuardrailViolation> violations;

  public GuardrailViolationException(GuardrailStage stage, List<GuardrailViolation> violations) {
    super("Guardrail blocked content at " + stage + ": " + violations);
    this.stage = stage;
    this.violations = List.copyOf(violations);
  }

  public GuardrailStage stage() {
    return stage;
  }

  public List<GuardrailViolation> violations() {
    return violations;
  }
}
