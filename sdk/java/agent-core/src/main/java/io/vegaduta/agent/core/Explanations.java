package io.vegaduta.agent.core;

import io.vegaduta.agent.api.guardrail.GuardrailViolation;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The text the model receives instead of a tool result when governance intervenes. Written so a
 * model acts sensibly on it: do not retry, tell the user what happened.
 */
public final class Explanations {

  private Explanations() {}

  public static String denied(String toolName, String reason) {
    return "The tool '"
        + toolName
        + "' was not executed: it is not permitted in this context (reason: "
        + reason
        + "). Do not retry it. Tell the user it is unavailable and, if useful, suggest an"
        + " alternative.";
  }

  public static String pendingApproval(String toolName, String approvalId) {
    return "The tool '"
        + toolName
        + "' requires human approval and has NOT been executed. An approval request with id "
        + approvalId
        + " was recorded. Do not retry the call. Tell the user the action is awaiting approval"
        + " and will run once approved.";
  }

  public static String budgetExceeded(String toolName) {
    return "The tool '"
        + toolName
        + "' was not executed because this run has exhausted its budget. Do not call more tools."
        + " Summarise what was done so far and tell the user the limit was reached.";
  }

  public static String guardrailBlocked(String toolName, List<GuardrailViolation> violations) {
    String codes =
        violations.stream().map(GuardrailViolation::code).collect(Collectors.joining(", "));
    return "The call to '"
        + toolName
        + "' was blocked by a content guardrail ("
        + codes
        + "). Do not retry with the same content. Tell the user the request could not be"
        + " completed as asked.";
  }

  public static String outputBlocked(String toolName, List<GuardrailViolation> violations) {
    String codes =
        violations.stream().map(GuardrailViolation::code).collect(Collectors.joining(", "));
    return "The tool '"
        + toolName
        + "' returned content that a guardrail withheld ("
        + codes
        + "). Treat the result as unavailable.";
  }

  public static String failed(String toolName, Throwable error) {
    return "The tool '"
        + toolName
        + "' failed: "
        + error.getClass().getSimpleName()
        + (error.getMessage() == null ? "" : ": " + error.getMessage())
        + ". Tell the user and do not assume the action happened.";
  }
}
