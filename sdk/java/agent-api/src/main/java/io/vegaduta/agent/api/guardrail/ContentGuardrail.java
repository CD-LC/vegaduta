package io.vegaduta.agent.api.guardrail;

import io.vegaduta.agent.api.RunContext;

/**
 * Inspects content at one or more {@link GuardrailStage}s. Implementations should be deterministic
 * and bounded in time; a pattern guardrail with linear-time regular expressions is the reference
 * implementation.
 */
public interface ContentGuardrail {

  /** A short name used in audit events and metrics. */
  String name();

  /** True when this guardrail wants to see content at {@code stage}. */
  boolean appliesTo(GuardrailStage stage);

  GuardrailResult evaluate(String content, GuardrailStage stage, RunContext context);
}
