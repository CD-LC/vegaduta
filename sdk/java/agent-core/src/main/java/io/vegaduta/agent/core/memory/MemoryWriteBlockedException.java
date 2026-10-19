package io.vegaduta.agent.core.memory;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.GovernanceException;
import io.vegaduta.agent.api.guardrail.GuardrailViolation;
import java.util.List;

/** A memory write was refused because the value matched an injection or blocking rule. */
@Experimental
public final class MemoryWriteBlockedException extends GovernanceException {

  private static final long serialVersionUID = 1L;

  private final transient String key;
  private final transient List<GuardrailViolation> violations;

  public MemoryWriteBlockedException(String key, List<GuardrailViolation> violations) {
    super("Memory write to '" + key + "' blocked: " + codes(violations));
    this.key = key;
    this.violations = List.copyOf(violations);
  }

  public String key() {
    return key;
  }

  public List<GuardrailViolation> violations() {
    return violations;
  }

  static String codes(List<GuardrailViolation> violations) {
    StringBuilder sb = new StringBuilder();
    for (GuardrailViolation v : violations) {
      if (sb.length() > 0) {
        sb.append(',');
      }
      sb.append(v.code());
    }
    return sb.toString();
  }
}
