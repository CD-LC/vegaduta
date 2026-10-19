package io.vegaduta.agent.api.guardrail;

/** Where in the turn a piece of content is being checked. */
public enum GuardrailStage {
  /** The user's message before it reaches the model. */
  INPUT,
  /** The model's reply before it reaches the user. */
  OUTPUT,
  /** The arguments the model wants to pass to a tool. */
  TOOL_ARGUMENTS,
  /** What a tool returned, before the model reads it. */
  TOOL_OUTPUT
}
