package io.vegaduta.agent.core.guardrail;

import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.guardrail.ContentGuardrail;
import io.vegaduta.agent.api.guardrail.GuardrailResult;
import io.vegaduta.agent.api.guardrail.GuardrailStage;
import io.vegaduta.agent.api.guardrail.GuardrailViolation;
import java.util.ArrayList;
import java.util.List;

/** Runs every applicable guardrail in order; content flows from one to the next. */
public final class GuardrailChain implements ContentGuardrail {

  private final List<ContentGuardrail> guardrails;

  public GuardrailChain(List<ContentGuardrail> guardrails) {
    this.guardrails = List.copyOf(guardrails);
  }

  public static GuardrailChain builtIn() {
    return new GuardrailChain(
        List.of(BuiltInRules.secretsGuardrail(), BuiltInRules.promptInjectionGuardrail()));
  }

  public static GuardrailChain none() {
    return new GuardrailChain(List.of());
  }

  @Override
  public String name() {
    return "chain";
  }

  @Override
  public boolean appliesTo(GuardrailStage stage) {
    return guardrails.stream().anyMatch(g -> g.appliesTo(stage));
  }

  @Override
  public GuardrailResult evaluate(String content, GuardrailStage stage, RunContext context) {
    String current = content;
    List<GuardrailViolation> violations = new ArrayList<>();
    for (ContentGuardrail guardrail : guardrails) {
      if (!guardrail.appliesTo(stage)) {
        continue;
      }
      GuardrailResult result = guardrail.evaluate(current, stage, context);
      violations.addAll(result.violations());
      current = result.content();
      if (result.blocked()) {
        break;
      }
    }
    return new GuardrailResult(current, violations);
  }

  public List<ContentGuardrail> guardrails() {
    return guardrails;
  }
}
