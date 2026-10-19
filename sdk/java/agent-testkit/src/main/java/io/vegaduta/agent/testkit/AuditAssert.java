package io.vegaduta.agent.testkit;

import io.vegaduta.agent.api.audit.AuditEvent;
import io.vegaduta.agent.api.audit.AuditOutcome;
import io.vegaduta.agent.api.risk.AsiRisk;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.assertj.core.api.AbstractAssert;

/** Assertions over a list of audit events. */
public final class AuditAssert extends AbstractAssert<AuditAssert, List<AuditEvent>> {

  AuditAssert(List<AuditEvent> actual) {
    super(actual, AuditAssert.class);
  }

  public AuditAssert hasEvent(String actionType, AuditOutcome outcome) {
    return has(
        e -> e.actionType().equals(actionType) && e.outcome() == outcome,
        "an event " + actionType + " with outcome " + outcome);
  }

  public AuditAssert hasToolOutcome(String toolName, AuditOutcome outcome) {
    return has(
        e ->
            e.actionType().equals("tool.call")
                && e.targetId().equals(toolName)
                && e.outcome() == outcome,
        "a tool.call on '" + toolName + "' with outcome " + outcome);
  }

  public AuditAssert hasPendingApprovalFor(String toolName) {
    return hasToolOutcome(toolName, AuditOutcome.PENDING_APPROVAL);
  }

  public AuditAssert hasDenied(String toolName) {
    return hasToolOutcome(toolName, AuditOutcome.DENIED);
  }

  public AuditAssert hasSucceeded(String toolName) {
    return hasToolOutcome(toolName, AuditOutcome.SUCCESS);
  }

  public AuditAssert hasReplayed(String toolName) {
    return hasToolOutcome(toolName, AuditOutcome.REPLAYED);
  }

  public AuditAssert hasNoDenials() {
    isNotNull();
    List<AuditEvent> denials =
        actual.stream().filter(e -> e.outcome() == AuditOutcome.DENIED).toList();
    if (!denials.isEmpty()) {
      failWithMessage("Expected no DENIED events but found %s", denials);
    }
    return this;
  }

  public AuditAssert hasNoOutcome(AuditOutcome outcome) {
    isNotNull();
    if (actual.stream().anyMatch(e -> e.outcome() == outcome)) {
      failWithMessage("Expected no %s events but found some in %s", outcome, actual);
    }
    return this;
  }

  /** Every given OWASP ASI item is tagged on at least one event. */
  public AuditAssert coversRisks(AsiRisk... risks) {
    isNotNull();
    Set<AsiRisk> seen = EnumSet.noneOf(AsiRisk.class);
    actual.forEach(e -> seen.addAll(e.risks()));
    for (AsiRisk risk : risks) {
      if (!seen.contains(risk)) {
        failWithMessage(
            "Expected an event tagged %s (%s) but risks seen were %s", risk, risk.title(), seen);
      }
    }
    return this;
  }

  public AuditAssert allHaveRunId(String runId) {
    isNotNull();
    for (AuditEvent e : actual) {
      if (!runId.equals(e.context().runId())) {
        failWithMessage("Event %s has run id %s, expected %s", e.id(), e.context().runId(), runId);
      }
    }
    return this;
  }

  private AuditAssert has(Predicate<AuditEvent> predicate, String description) {
    isNotNull();
    if (actual.stream().noneMatch(predicate)) {
      failWithMessage("Expected %s but the audit trail was %s", description, summary());
    }
    return this;
  }

  private String summary() {
    return actual.stream()
        .map(e -> e.actionType() + "(" + e.targetId() + ")=" + e.outcome())
        .toList()
        .toString();
  }
}
