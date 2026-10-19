package io.vegaduta.agent.api.hook;

/**
 * Execution points where hooks run. Names track the OWASP Agent Control Standard's hook vocabulary
 * where it exists; see the SDK documentation for the mapping.
 */
public enum HookPoint {
  BEFORE_TOOL_CALL,
  AFTER_TOOL_CALL,
  ON_TOOL_DENIED,
  ON_APPROVAL_REQUESTED,
  ON_APPROVAL_DECIDED,
  ON_BUDGET_EXCEEDED,
  ON_GUARDRAIL_VIOLATION,
  BEFORE_MODEL_CALL,
  AFTER_MODEL_CALL
}
