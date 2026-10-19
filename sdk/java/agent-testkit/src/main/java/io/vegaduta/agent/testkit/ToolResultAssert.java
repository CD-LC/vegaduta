package io.vegaduta.agent.testkit;

import io.vegaduta.agent.api.tool.ToolResult;
import io.vegaduta.agent.api.tool.ToolStatus;
import org.assertj.core.api.AbstractAssert;

/** Assertions over a {@link ToolResult}. */
public final class ToolResultAssert extends AbstractAssert<ToolResultAssert, ToolResult> {

  ToolResultAssert(ToolResult actual) {
    super(actual, ToolResultAssert.class);
  }

  public ToolResultAssert hasStatus(ToolStatus status) {
    isNotNull();
    if (actual.status() != status) {
      failWithMessage(
          "Expected status %s but was %s (reason %s, output: %s)",
          status, actual.status(), actual.reason(), actual.output());
    }
    return this;
  }

  public ToolResultAssert isOk() {
    return hasStatus(ToolStatus.OK);
  }

  public ToolResultAssert isReplayed() {
    return hasStatus(ToolStatus.REPLAYED);
  }

  public ToolResultAssert isDenied() {
    return hasStatus(ToolStatus.DENIED);
  }

  public ToolResultAssert isDeniedBecause(String reason) {
    isDenied();
    if (!reason.equals(actual.reason())) {
      failWithMessage("Expected denial reason %s but was %s", reason, actual.reason());
    }
    return this;
  }

  public ToolResultAssert isPendingApproval() {
    hasStatus(ToolStatus.PENDING_APPROVAL);
    if (actual.approvalId() == null) {
      failWithMessage("Expected an approval id on a PENDING_APPROVAL result");
    }
    return this;
  }

  public ToolResultAssert isBudgetExceeded() {
    return hasStatus(ToolStatus.BUDGET_EXCEEDED);
  }

  public ToolResultAssert isError() {
    return hasStatus(ToolStatus.ERROR);
  }

  public ToolResultAssert hasOutputContaining(String fragment) {
    isNotNull();
    if (!actual.output().contains(fragment)) {
      failWithMessage("Expected output to contain <%s> but was <%s>", fragment, actual.output());
    }
    return this;
  }

  public ToolResultAssert hasOutput(String output) {
    isNotNull();
    if (!actual.output().equals(output)) {
      failWithMessage("Expected output <%s> but was <%s>", output, actual.output());
    }
    return this;
  }
}
