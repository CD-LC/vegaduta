package io.vegaduta.agent.core.evidence;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.audit.AuditEvent;
import io.vegaduta.agent.api.audit.AuditOutcome;
import io.vegaduta.agent.api.risk.AsiRisk;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Evidence for the EU AI Act record-keeping and oversight duties, organised by article:
 *
 * <ul>
 *   <li><b>Article 12</b> (record-keeping): the event log, the tamper-evidence check and a
 *       retention note.
 *   <li><b>Article 14</b> (human oversight): approvals requested and decided (by whom), denials,
 *       operator halts and budget stops; the proof that a human could and did intervene.
 *   <li><b>Article 9</b> (risk management): the per-tool risk classification in force and how it
 *       was exercised.
 * </ul>
 *
 * <p>The SDK does not decide whether a system is high-risk; the sections are the evidence an
 * organisation attaches to that assessment.
 */
@Experimental
public final class EuAiActEvidenceExporter implements EvidenceExporter {

  private static final String RETENTION_NOTE =
      "Retain these records for the period required by Art. 12(1) and Art. 19 (at least six months"
          + " unless Union or national law requires longer). The head hash and last sequence number"
          + " are the integrity anchors for this bundle; store them separately and pass them back as"
          + " expected anchors when verifying.";

  private final EvidenceBundle bundle;

  public EuAiActEvidenceExporter(EvidenceBundle bundle) {
    this.bundle = Objects.requireNonNull(bundle, "bundle");
  }

  @Override
  public String toMarkdown() {
    StringBuilder sb = new StringBuilder();
    sb.append("# EU AI Act evidence bundle\n\n");
    sb.append("Period: ").append(bundle.periodText()).append("  \n");
    sb.append("Generated: ").append(bundle.generatedAt()).append("  \n");
    sb.append("Events: ").append(bundle.events().size()).append("\n\n");
    article12(sb);
    article14(sb);
    article9(sb);
    return sb.toString();
  }

  @Override
  public String toJsonLines() {
    StringBuilder sb = new StringBuilder();
    ChainVerification cv = bundle.chainVerification();
    sb.append(
            EvidenceJson.object()
                .put("section", "bundle")
                .put("standard", "EU AI Act")
                .put("period", bundle.periodText())
                .put("generatedAt", bundle.generatedAt().toString())
                .put("events", bundle.events().size()))
        .append('\n');
    sb.append(
            EvidenceJson.object()
                .put("section", "art12.integrity")
                .put("chained", bundle.chained())
                .put("ok", cv.ok())
                .put("signed", cv.signed())
                .put("unchainedEvents", bundle.unchainedEvents())
                .put("algorithm", chainAlgorithm())
                .put("firstBrokenSeq", cv.firstBrokenSeq())
                .put("eventsChecked", cv.eventsChecked())
                .put("message", cv.message())
                .put("firstSeq", bundle.firstSeq())
                .put("firstPrev", bundle.firstPrev().orElse(null))
                .put("lastSeq", bundle.lastSeq())
                .put("headHash", bundle.headHash().orElse(null))
                .put("retention", RETENTION_NOTE))
        .append('\n');
    for (AuditEvent e : bundle.events()) {
      sb.append(logLine(e)).append('\n');
    }
    sb.append(
            EvidenceJson.object()
                .put("section", "art14.oversight")
                .put("approvalsRequested", bundle.approvalsRequested().size())
                .put("approved", decided(AuditOutcome.APPROVED).size())
                .put("rejected", decided(AuditOutcome.REJECTED).size())
                .put("expired", decided(AuditOutcome.EXPIRED).size())
                .put("denied", denials().size())
                .put("halts", bundle.halts().size())
                .put("budgetStops", budgetStops().size())
                .put("humanApprovedExecutions", bundle.humanApprovedExecutions().size())
                .put("autonomousExecutions", bundle.autonomousExecutions().size())
                .putStrings("approvers", approvers()))
        .append('\n');
    for (String tool : bundle.toolNames()) {
      List<AuditEvent> calls = callsOf(tool);
      sb.append(
              EvidenceJson.object()
                  .put("section", "art9.tool")
                  .put("tool", tool)
                  .put("risk", bundle.riskLabelOf(tool))
                  .putStrings(
                      "asiRisks", bundle.asiRisksOf(tool).stream().map(AsiRisk::id).toList())
                  .put("calls", calls.size())
                  .put("outcomes", outcomes(calls)))
          .append('\n');
    }
    return sb.toString();
  }

  /**
   * One event as a JSON line: top-level fields under fixed names and the attributes as a nested
   * object, so an attribute named {@code actionType} or {@code section} can neither shadow the
   * field nor duplicate a key.
   */
  static EvidenceJson logLine(AuditEvent e) {
    EvidenceJson attrs = EvidenceJson.object();
    e.attributes().forEach(attrs::put);
    EvidenceJson line =
        EvidenceJson.object()
            .put("section", "art12.log")
            .put("id", e.id().toString())
            .put("createdAt", e.createdAt().toString())
            .put("actorId", e.actorId())
            .put("actionType", e.actionType())
            .put("targetType", e.targetType())
            .put("targetId", e.targetId())
            .put("outcome", e.outcome().name())
            .put("detail", e.detail())
            .put("runId", e.context().runId())
            .put("tenantId", e.context().tenantId())
            .put("agentId", e.context().agentId())
            .putStrings("risks", e.risks().stream().map(AsiRisk::id).toList())
            .put("attributes", attrs);
    return line;
  }

  private void article12(StringBuilder sb) {
    sb.append("## Article 12 - Record-keeping\n\n");
    ChainVerification cv = bundle.chainVerification();
    sb.append("### Tamper evidence\n\n");
    if (!bundle.chained()) {
      sb.append(
          "Events in this bundle are **not hash-chained**. Wrap the audit sink in"
              + " `HashChainedAuditSink` to obtain tamper-evident records.\n\n");
    } else {
      sb.append(cv.ok() ? "**Chain intact.** " : "**Chain BROKEN.** ").append(cv.message());
      if (bundle.unchainedEvents() > 0) {
        sb.append("  \n")
            .append(Markdown.plural(bundle.unchainedEvents(), "event"))
            .append(" in the log carry no chain attributes and are not covered.");
      }
      sb.append("  \nSignature algorithm: `").append(chainAlgorithm()).append("`");
      sb.append("  \nHead: seq ")
          .append(bundle.lastSeq().isPresent() ? Long.toString(bundle.lastSeq().getAsLong()) : "-")
          .append(", hash `")
          .append(bundle.headHash().orElse("-"))
          .append("`");
      sb.append("  \nFirst: seq ")
          .append(
              bundle.firstSeq().isPresent() ? Long.toString(bundle.firstSeq().getAsLong()) : "-")
          .append(", prev `")
          .append(bundle.firstPrev().orElse("-"))
          .append("`\n\n");
    }
    sb.append("### Retention\n\n").append(RETENTION_NOTE).append("\n\n");
    sb.append("### Event log\n\n");
    List<List<String>> rows = new ArrayList<>();
    for (AuditEvent e : bundle.events()) {
      rows.add(
          List.of(
              e.attributes().getOrDefault(HashChainedAuditSink.SEQ, "-"),
              e.createdAt().toString(),
              e.actorId(),
              e.actionType(),
              e.targetType() + ":" + e.targetId(),
              e.outcome().name(),
              Markdown.text(e.detail())));
    }
    Markdown.table(
        sb, List.of("Seq", "Time", "Actor", "Action", "Target", "Outcome", "Detail"), rows);
  }

  private void article14(StringBuilder sb) {
    sb.append("## Article 14 - Human oversight\n\n");
    List<AuditEvent> requested = bundle.approvalsRequested();
    sb.append("### Approvals requested\n\n");
    if (requested.isEmpty()) {
      sb.append("No action required human approval in this period.\n\n");
    } else {
      List<List<String>> rows = new ArrayList<>();
      for (AuditEvent e : requested) {
        rows.add(
            List.of(
                e.targetId(),
                e.createdAt().toString(),
                e.context().principalOrAnonymous(),
                e.attributes().getOrDefault("actionType", "-"),
                Markdown.text(e.detail())));
      }
      Markdown.table(
          sb, List.of("Approval", "Requested at", "On behalf of", "Action", "Summary"), rows);
    }
    sb.append("### Approval decisions\n\n");
    List<AuditEvent> decided = bundle.approvalsDecided();
    if (decided.isEmpty()) {
      sb.append("No approval decisions were recorded.\n\n");
    } else {
      List<List<String>> rows = new ArrayList<>();
      for (AuditEvent e : decided) {
        rows.add(
            List.of(
                e.targetId(),
                e.createdAt().toString(),
                e.outcome().name(),
                e.actorId(),
                Markdown.text(e.detail())));
      }
      Markdown.table(sb, List.of("Approval", "Decided at", "Decision", "Decided by", "Note"), rows);
      sb.append("Approved: ")
          .append(decided(AuditOutcome.APPROVED).size())
          .append(", rejected: ")
          .append(decided(AuditOutcome.REJECTED).size())
          .append(", expired without decision: ")
          .append(decided(AuditOutcome.EXPIRED).size())
          .append(". Human approvers: ")
          .append(approvers().isEmpty() ? "none" : String.join(", ", approvers()))
          .append(".\n\n");
    }
    sb.append("### Denials\n\n");
    List<AuditEvent> denials = denials();
    if (denials.isEmpty()) {
      sb.append("No tool call was denied by policy, gate, guardrail or hook.\n\n");
    } else {
      List<List<String>> rows = new ArrayList<>();
      for (AuditEvent e : denials) {
        rows.add(
            List.of(
                e.createdAt().toString(),
                e.targetId(),
                e.context().principalOrAnonymous(),
                Markdown.text(e.detail())));
      }
      Markdown.table(sb, List.of("Time", "Tool", "On behalf of", "Reason"), rows);
    }
    sb.append("### Halts\n\n");
    List<AuditEvent> halts = bundle.halts();
    if (halts.isEmpty()) {
      sb.append("No operator halt (`halt.*`) was recorded in this period.\n\n");
    } else {
      List<List<String>> rows = new ArrayList<>();
      for (AuditEvent e : halts) {
        rows.add(
            List.of(
                e.createdAt().toString(),
                e.actorId(),
                e.actionType(),
                e.targetType() + ":" + e.targetId(),
                e.outcome().name(),
                Markdown.text(e.detail())));
      }
      Markdown.table(sb, List.of("Time", "By", "Action", "Target", "Outcome", "Detail"), rows);
    }
    sb.append("### Budget stops\n\n");
    List<AuditEvent> stops = budgetStops();
    if (stops.isEmpty()) {
      sb.append("No run was stopped by a budget limit.\n\n");
    } else {
      List<List<String>> rows = new ArrayList<>();
      for (AuditEvent e : stops) {
        rows.add(
            List.of(
                e.createdAt().toString(),
                e.targetId(),
                e.context().runId(),
                Markdown.text(e.detail())));
      }
      Markdown.table(sb, List.of("Time", "Tool", "Run", "Detail"), rows);
    }
    sb.append("Executions after human approval: ")
        .append(bundle.humanApprovedExecutions().size())
        .append("; autonomous executions: ")
        .append(bundle.autonomousExecutions().size())
        .append(".\n\n");
  }

  private void article9(StringBuilder sb) {
    sb.append("## Article 9 - Risk management\n\n");
    List<String> tools = bundle.toolNames();
    if (tools.isEmpty()) {
      sb.append("No tools were classified or called in this period.\n\n");
      return;
    }
    List<List<String>> rows = new ArrayList<>();
    for (String tool : tools) {
      List<AuditEvent> calls = callsOf(tool);
      rows.add(
          List.of(
              tool,
              bundle.riskLabelOf(tool),
              bundle.asiRisksOf(tool).stream().map(AsiRisk::id).collect(Collectors.joining(", ")),
              Long.toString(calls.size()),
              EvidenceBundle.countByOutcome(calls).entrySet().stream()
                  .map(en -> en.getKey() + "=" + en.getValue())
                  .collect(Collectors.joining(", "))));
    }
    Markdown.table(sb, List.of("Tool", "Risk label", "OWASP ASI", "Calls", "Outcomes"), rows);
  }

  private List<AuditEvent> decided(AuditOutcome outcome) {
    return bundle.withOutcome(EvidenceBundle.APPROVAL_DECIDED, outcome);
  }

  private List<AuditEvent> denials() {
    return bundle.withOutcome(EvidenceBundle.TOOL_CALL, AuditOutcome.DENIED);
  }

  private List<AuditEvent> budgetStops() {
    return bundle.events().stream()
        .filter(e -> e.outcome() == AuditOutcome.BUDGET_EXCEEDED)
        .toList();
  }

  private List<AuditEvent> callsOf(String tool) {
    return bundle.toolCalls().stream().filter(e -> e.targetId().equals(tool)).toList();
  }

  private List<String> approvers() {
    return bundle.approvalsDecided().stream()
        .filter(e -> e.outcome() == AuditOutcome.APPROVED || e.outcome() == AuditOutcome.REJECTED)
        .map(AuditEvent::actorId)
        .distinct()
        .sorted()
        .toList();
  }

  /** The {@code chain.alg} seen on the chained events, {@code none} when the chain is unsigned. */
  private String chainAlgorithm() {
    return bundle.events().stream()
        .map(e -> e.attributes().get(HashChainedAuditSink.ALG))
        .filter(a -> a != null)
        .findFirst()
        .orElse("none");
  }

  private static EvidenceJson outcomes(List<AuditEvent> events) {
    EvidenceJson json = EvidenceJson.object();
    for (Map.Entry<String, Long> en : EvidenceBundle.countByOutcome(events).entrySet()) {
      json.put(en.getKey(), en.getValue());
    }
    return json;
  }
}
