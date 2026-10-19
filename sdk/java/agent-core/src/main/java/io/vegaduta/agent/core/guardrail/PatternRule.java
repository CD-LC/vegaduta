package io.vegaduta.agent.core.guardrail;

import com.google.re2j.Pattern;
import io.vegaduta.agent.api.guardrail.GuardrailAction;
import io.vegaduta.agent.api.guardrail.GuardrailStage;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * One regular-expression rule. Patterns are RE2 (linear time), so a hostile input cannot turn the
 * guardrail into a denial of service.
 *
 * @param code stable identifier, for example {@code secret.aws_access_key}
 * @param pattern the RE2 pattern
 * @param action what to do on a match
 * @param message explanation for audit and for the model
 * @param stages where the rule applies
 * @param replacement text that replaces a match when the action is {@link GuardrailAction#REDACT}
 */
public record PatternRule(
    String code,
    Pattern pattern,
    GuardrailAction action,
    String message,
    Set<GuardrailStage> stages,
    String replacement) {

  public PatternRule {
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(pattern, "pattern");
    Objects.requireNonNull(action, "action");
    Objects.requireNonNull(message, "message");
    Objects.requireNonNull(replacement, "replacement");
    stages = Set.copyOf(stages.isEmpty() ? EnumSet.allOf(GuardrailStage.class) : stages);
  }

  public static PatternRule of(
      String code, String regex, GuardrailAction action, String message, GuardrailStage... stages) {
    return new PatternRule(
        code, Pattern.compile(regex), action, message, Set.of(stages), "[redacted:" + code + "]");
  }

  public boolean appliesTo(GuardrailStage stage) {
    return stages.contains(stage);
  }
}
