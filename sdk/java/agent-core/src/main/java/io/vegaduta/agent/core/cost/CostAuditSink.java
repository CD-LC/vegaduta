package io.vegaduta.agent.core.cost;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.audit.AuditEvent;
import io.vegaduta.agent.api.audit.AuditOutcome;
import io.vegaduta.agent.api.audit.AuditSink;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns human approval decisions into {@link CostLedger.ReviewCost} entries. The approval gate
 * audits every decision as {@code actionType = "approval.decided"} with outcome {@link
 * AuditOutcome#APPROVED} or {@link AuditOutcome#REJECTED} and the approval id as target; each such
 * event is one unit of human review at the cost model's {@link CostModel#humanReviewCost()}.
 * Expiry, execution and failure events are not review work and are ignored.
 *
 * <p>Register it with {@code Governance.builder().auditSink(new CostAuditSink(ledger))} alongside
 * the sink that keeps the audit trail.
 */
@Experimental
public final class CostAuditSink implements AuditSink {

  private static final Logger log = LoggerFactory.getLogger(CostAuditSink.class);

  /** Prefix of every approval-related audit action type. */
  public static final String APPROVAL_ACTION_PREFIX = "approval.";

  private final CostLedger ledger;

  public CostAuditSink(CostLedger ledger) {
    this.ledger = Objects.requireNonNull(ledger, "ledger");
  }

  public CostLedger ledger() {
    return ledger;
  }

  @Override
  public void emit(AuditEvent event) {
    if (!isReviewDecision(event)) {
      return;
    }
    try {
      ledger.recordReview(event.context(), event.targetId());
    } catch (RuntimeException e) {
      log.warn("Cost ledger rejected review of approval {}: {}", event.targetId(), e.toString());
    }
  }

  /** True for an approval event that a human decided one way or the other. */
  public static boolean isReviewDecision(AuditEvent event) {
    return event.actionType().startsWith(APPROVAL_ACTION_PREFIX)
        && (event.outcome() == AuditOutcome.APPROVED || event.outcome() == AuditOutcome.REJECTED);
  }
}
