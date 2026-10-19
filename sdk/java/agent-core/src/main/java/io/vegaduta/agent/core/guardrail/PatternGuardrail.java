package io.vegaduta.agent.core.guardrail;

import com.google.re2j.Matcher;
import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.guardrail.ContentGuardrail;
import io.vegaduta.agent.api.guardrail.GuardrailAction;
import io.vegaduta.agent.api.guardrail.GuardrailResult;
import io.vegaduta.agent.api.guardrail.GuardrailStage;
import io.vegaduta.agent.api.guardrail.GuardrailViolation;
import java.util.ArrayList;
import java.util.List;

/** Runs a list of {@link PatternRule}s over content. */
public final class PatternGuardrail implements ContentGuardrail {

  private final String name;
  private final List<PatternRule> rules;

  public PatternGuardrail(String name, List<PatternRule> rules) {
    this.name = name;
    this.rules = List.copyOf(rules);
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public boolean appliesTo(GuardrailStage stage) {
    return rules.stream().anyMatch(r -> r.appliesTo(stage));
  }

  @Override
  public GuardrailResult evaluate(String content, GuardrailStage stage, RunContext context) {
    List<GuardrailViolation> violations = new ArrayList<>();
    String current = content;
    for (PatternRule rule : rules) {
      if (!rule.appliesTo(stage)) {
        continue;
      }
      Matcher matcher = rule.pattern().matcher(current);
      if (!matcher.find()) {
        continue;
      }
      violations.add(new GuardrailViolation(rule.code(), rule.message(), rule.action()));
      if (rule.action() == GuardrailAction.REDACT) {
        current = matcher.replaceAll(Matcher.quoteReplacement(rule.replacement()));
      }
    }
    return new GuardrailResult(current, violations);
  }

  public List<PatternRule> rules() {
    return rules;
  }
}
