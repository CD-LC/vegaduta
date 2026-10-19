package io.vegaduta.agent.api.guardrail;

import java.util.Objects;

/**
 * One matched rule.
 *
 * @param code stable identifier of the rule, for example {@code "secret.aws_access_key"}
 * @param message human-readable explanation
 * @param action what the rule asks for
 */
public record GuardrailViolation(String code, String message, GuardrailAction action) {

  public GuardrailViolation {
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(message, "message");
    Objects.requireNonNull(action, "action");
  }
}
