package io.vegaduta.agent.core.evidence;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.audit.AuditEvent;
import io.vegaduta.agent.api.risk.AsiRisk;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Maps the audit log onto the OWASP Top 10 for Agentic Applications (ASI01 to ASI10): for each
 * item, which controls fired (the events tagged with that risk, by action type and target), the
 * counts by outcome, and <i>not exercised</i> when nothing in the period touched it. Auditors read
 * it as a coverage matrix; an empty row is a finding, not a pass.
 */
@Experimental
public final class OwaspAsiCoverageReport implements EvidenceExporter {

  private final EvidenceBundle bundle;

  public OwaspAsiCoverageReport(EvidenceBundle bundle) {
    this.bundle = Objects.requireNonNull(bundle, "bundle");
  }

  /** The events tagged with {@code risk}. */
  public List<AuditEvent> eventsFor(AsiRisk risk) {
    return bundle.withRisk(risk);
  }

  /** True when at least one event in the period is tagged with {@code risk}. */
  public boolean exercised(AsiRisk risk) {
    return !eventsFor(risk).isEmpty();
  }

  @Override
  public String toMarkdown() {
    StringBuilder sb = new StringBuilder();
    sb.append("# OWASP Top 10 for Agentic Applications - control coverage\n\n");
    sb.append("Period: ").append(bundle.periodText()).append("  \n");
    sb.append("Generated: ").append(bundle.generatedAt()).append("\n\n");
    List<List<String>> rows = new ArrayList<>();
    for (AsiRisk risk : AsiRisk.values()) {
      List<AuditEvent> events = eventsFor(risk);
      if (events.isEmpty()) {
        rows.add(List.of(risk.id(), risk.title(), "not exercised", "-", "0"));
        continue;
      }
      rows.add(
          List.of(
              risk.id(),
              risk.title(),
              String.join(", ", controls(events)),
              EvidenceBundle.countByOutcome(events).entrySet().stream()
                  .map(en -> en.getKey() + "=" + en.getValue())
                  .collect(Collectors.joining(", ")),
              Long.toString(events.size())));
    }
    Markdown.table(sb, List.of("Item", "Title", "Controls fired", "Outcomes", "Events"), rows);
    long covered = java.util.Arrays.stream(AsiRisk.values()).filter(this::exercised).count();
    sb.append(covered)
        .append(" of ")
        .append(AsiRisk.values().length)
        .append(" items exercised in this period.\n");
    return sb.toString();
  }

  @Override
  public String toJsonLines() {
    StringBuilder sb = new StringBuilder();
    for (AsiRisk risk : AsiRisk.values()) {
      List<AuditEvent> events = eventsFor(risk);
      EvidenceJson outcomes = EvidenceJson.object();
      for (Map.Entry<String, Long> en : EvidenceBundle.countByOutcome(events).entrySet()) {
        outcomes.put(en.getKey(), en.getValue());
      }
      sb.append(
              EvidenceJson.object()
                  .put("section", "owasp.asi")
                  .put("item", risk.id())
                  .put("title", risk.title())
                  .put("exercised", !events.isEmpty())
                  .put("events", events.size())
                  .putStrings("controls", controls(events))
                  .put("outcomes", outcomes))
          .append('\n');
    }
    return sb.toString();
  }

  /** Distinct {@code actionType target} pairs, sorted. */
  private static List<String> controls(List<AuditEvent> events) {
    TreeSet<String> controls = new TreeSet<>();
    for (AuditEvent e : events) {
      controls.add(e.actionType() + " " + e.targetType() + ":" + e.targetId());
    }
    return List.copyOf(controls);
  }
}
