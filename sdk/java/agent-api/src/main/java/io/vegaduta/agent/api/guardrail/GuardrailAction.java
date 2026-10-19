package io.vegaduta.agent.api.guardrail;

/** What to do when a guardrail matches. */
public enum GuardrailAction {
  /** Stop: the content must not pass. */
  BLOCK,
  /** Let it pass with the matched parts replaced. */
  REDACT,
  /** Let it pass, record a warning. */
  WARN
}
