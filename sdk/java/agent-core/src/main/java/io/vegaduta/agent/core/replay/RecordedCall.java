package io.vegaduta.agent.core.replay;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.audit.AuditEvent;
import io.vegaduta.agent.api.audit.AuditOutcome;
import io.vegaduta.agent.api.gate.Verdict;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * One tool call reconstructed from {@code tool.call} audit events.
 *
 * <p>A single call can produce several events (a warning for an unclassified tool, then the
 * verdict; a pending approval, then the success once approved). They are folded into one record per
 * {@code callId}, keeping the most severe outcome as the verdict the live gate applied; among
 * outcomes of equal severity the later, terminal one wins over a preliminary {@code WARNING}.
 *
 * <p>Two things are not gate decisions and are left out so a replay compares like with like:
 *
 * <ul>
 *   <li>events whose {@code stage} attribute (written by {@code GovernedTool}) is not the gate or
 *       the approval stage, such as hook, guardrail and budget denials; for older trails without
 *       the attribute, denials whose detail starts with a hook or guardrail prefix are skipped
 *   <li>{@code REPLAYED} events, where the journal answered before the gate ran
 * </ul>
 *
 * <p>The post-approval execution is audited under a fresh call id but carries the same {@code
 * approvalId} as the pending event, so it is folded into that call instead of counting as a second,
 * autonomous call.
 *
 * @param callId the tool call id (from the {@code callId} attribute, or the event id)
 * @param toolName the tool (the event's {@code targetId})
 * @param actual the verdict the live gate effectively applied
 * @param actualOutcome the most severe audit outcome seen for the call
 * @param risk the recorded risk label, or {@code null}
 * @param recordedAt when the first event for the call was recorded
 * @param context the run the call belonged to
 */
@Experimental
public record RecordedCall(
    String callId,
    String toolName,
    Verdict.Outcome actual,
    AuditOutcome actualOutcome,
    @Nullable String risk,
    Instant recordedAt,
    RunContext context) {

  /** The audit action type recorded for every tool call by the governed pipeline. */
  public static final String ACTION_TYPE = "tool.call";

  static final String STAGE_ATTRIBUTE = "stage";
  static final String APPROVAL_ID_ATTRIBUTE = "approvalId";
  private static final Set<String> GATE_STAGES = Set.of("gate", "approval");
  private static final List<String> NON_GATE_DENIAL_PREFIXES =
      List.of("hook:", "guardrail: ", "output guardrail: ", "hook after call: ");

  public RecordedCall {
    Objects.requireNonNull(callId, "callId");
    Objects.requireNonNull(toolName, "toolName");
    Objects.requireNonNull(actual, "actual");
    Objects.requireNonNull(actualOutcome, "actualOutcome");
    Objects.requireNonNull(recordedAt, "recordedAt");
    Objects.requireNonNull(context, "context");
  }

  /**
   * Folds {@code tool.call} events into calls, in order of first appearance. Events with another
   * action type, and events that are not gate decisions (see class docs), are ignored.
   */
  public static List<RecordedCall> from(List<AuditEvent> events) {
    Map<String, RecordedCall> byCall = new LinkedHashMap<>();
    Map<String, String> callIdByApproval = new HashMap<>();
    for (AuditEvent event : events) {
      if (!ACTION_TYPE.equals(event.actionType()) || !isGateDecision(event)) {
        continue;
      }
      String callId = event.attributes().getOrDefault("callId", event.id().toString());
      String approvalId = event.attributes().get(APPROVAL_ID_ATTRIBUTE);
      if (approvalId != null) {
        // The post-approval execution is audited under a new call id; fold it into the call that
        // asked for the approval.
        callId = callIdByApproval.getOrDefault(approvalId, callId);
        callIdByApproval.putIfAbsent(approvalId, callId);
      }
      Verdict.Outcome verdict = verdictOf(event.outcome());
      RecordedCall previous = byCall.get(callId);
      if (previous == null) {
        byCall.put(
            callId,
            new RecordedCall(
                callId,
                event.targetId(),
                verdict,
                event.outcome(),
                event.attributes().get("risk"),
                event.createdAt(),
                event.context()));
        continue;
      }
      int severity = ChangeKind.severity(verdict);
      int previousSeverity = ChangeKind.severity(previous.actual());
      boolean moreSevere = severity > previousSeverity;
      boolean terminalAfterWarning =
          severity == previousSeverity
              && previous.actualOutcome() == AuditOutcome.WARNING
              && event.outcome() != AuditOutcome.WARNING;
      if (moreSevere || terminalAfterWarning) {
        byCall.put(
            callId,
            new RecordedCall(
                callId,
                previous.toolName(),
                moreSevere ? verdict : previous.actual(),
                event.outcome(),
                previous.risk() != null ? previous.risk() : event.attributes().get("risk"),
                previous.recordedAt().isBefore(event.createdAt())
                    ? previous.recordedAt()
                    : event.createdAt(),
                previous.context()));
      }
    }
    return new ArrayList<>(byCall.values());
  }

  /**
   * Whether an event reflects the gate's decision (or the tool running after the gate let it
   * through) rather than a hook, guardrail, budget or journal outcome.
   */
  static boolean isGateDecision(AuditEvent event) {
    if (event.outcome() == AuditOutcome.REPLAYED) {
      return false;
    }
    String stage = event.attributes().get(STAGE_ATTRIBUTE);
    if (stage != null) {
      if (GATE_STAGES.contains(stage) || "call".equals(stage) || "classification".equals(stage)) {
        return true;
      }
      // Guardrail, hook and budget stages decided after (or instead of) the gate; budget and
      // output stages only run on calls the gate allowed, which the SUCCESS/FAILURE/BUDGET
      // outcome would also say, but their DENIED is not a gate denial.
      return event.outcome() != AuditOutcome.DENIED && !"journal".equals(stage);
    }
    if (event.outcome() == AuditOutcome.DENIED && event.detail() != null) {
      for (String prefix : NON_GATE_DENIAL_PREFIXES) {
        if (event.detail().startsWith(prefix)) {
          return false;
        }
      }
    }
    return true;
  }

  /**
   * The gate verdict implied by an audit outcome. {@code DENIED} means the gate stopped the call;
   * the approval states mean a human was asked; everything else means the gate let the call
   * through.
   */
  public static Verdict.Outcome verdictOf(AuditOutcome outcome) {
    return switch (outcome) {
      case DENIED -> Verdict.Outcome.DENY;
      case PENDING_APPROVAL, APPROVED, REJECTED, EXPIRED, AUTO_APPROVED ->
          Verdict.Outcome.REQUIRE_APPROVAL;
      case SUCCESS, FAILURE, REPLAYED, BUDGET_EXCEEDED, WARNING -> Verdict.Outcome.ALLOW;
    };
  }
}
