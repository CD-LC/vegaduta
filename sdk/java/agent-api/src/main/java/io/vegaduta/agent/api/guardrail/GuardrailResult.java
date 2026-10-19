package io.vegaduta.agent.api.guardrail;

import java.util.List;
import java.util.Objects;

/**
 * The outcome of checking one piece of content.
 *
 * @param content the content to continue with: the original, or a redacted version
 * @param violations every matched rule; empty when clean
 */
public record GuardrailResult(String content, List<GuardrailViolation> violations) {

  public GuardrailResult {
    Objects.requireNonNull(content, "content");
    violations = List.copyOf(violations);
  }

  public static GuardrailResult pass(String content) {
    return new GuardrailResult(content, List.of());
  }

  public boolean blocked() {
    return violations.stream().anyMatch(v -> v.action() == GuardrailAction.BLOCK);
  }

  public boolean clean() {
    return violations.isEmpty();
  }
}
