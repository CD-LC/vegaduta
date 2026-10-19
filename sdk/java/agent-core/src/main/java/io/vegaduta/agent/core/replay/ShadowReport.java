package io.vegaduta.agent.core.replay;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.audit.AuditEvent;
import io.vegaduta.agent.api.gate.Verdict;
import io.vegaduta.agent.core.hook.JsonLite;
import io.vegaduta.agent.core.text.Text;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Summary of {@link ShadowGate} divergence events: how often, and for which tools, the candidate
 * policy would have decided differently from the live one.
 *
 * @param divergences every {@code shadow.decision} event seen
 * @param perTool one row per tool, in order of first appearance
 * @param from the earliest divergence, or {@code null} when there were none
 * @param to the latest divergence, or {@code null} when there were none
 */
@Experimental
public record ShadowReport(
    long divergences, List<ToolRow> perTool, @Nullable Instant from, @Nullable Instant to) {

  public ShadowReport {
    perTool = List.copyOf(perTool);
  }

  /** Divergences for one tool. */
  public record ToolRow(
      String toolName,
      long divergences,
      long wouldBlock,
      long wouldEscalate,
      long wouldRelax,
      List<String> exampleCallIds) {
    public ToolRow {
      Objects.requireNonNull(toolName, "toolName");
      exampleCallIds = List.copyOf(exampleCallIds);
    }
  }

  private static final int MAX_EXAMPLES = 10;

  /** Reads every {@code shadow.decision} event in {@code events}; other events are ignored. */
  public static ShadowReport from(List<AuditEvent> events) {
    long total = 0;
    Instant from = null;
    Instant to = null;
    Map<String, long[]> counts = new LinkedHashMap<>();
    Map<String, List<String>> examples = new LinkedHashMap<>();
    for (AuditEvent event : events) {
      if (!ShadowGate.ACTION_TYPE.equals(event.actionType())) {
        continue;
      }
      total++;
      long[] c = counts.computeIfAbsent(event.targetId(), k -> new long[4]);
      c[0]++;
      String change = event.attributes().get(ShadowGate.ATTR_CHANGE);
      if (change == null) {
        change = changeFrom(event.attributes());
      }
      switch (change) {
        case "NEWLY_BLOCKED" -> c[1]++;
        case "NEWLY_ESCALATED" -> c[2]++;
        case "RELAXED" -> c[3]++;
        default -> {}
      }
      List<String> ids = examples.computeIfAbsent(event.targetId(), k -> new ArrayList<>());
      String callId = event.attributes().get(ShadowGate.ATTR_CALL_ID);
      if (callId != null && ids.size() < MAX_EXAMPLES) {
        ids.add(callId);
      }
      if (from == null || event.createdAt().isBefore(from)) {
        from = event.createdAt();
      }
      if (to == null || event.createdAt().isAfter(to)) {
        to = event.createdAt();
      }
    }
    List<ToolRow> rows = new ArrayList<>(counts.size());
    counts.forEach(
        (tool, c) -> rows.add(new ToolRow(tool, c[0], c[1], c[2], c[3], examples.get(tool))));
    return new ShadowReport(total, rows, from, to);
  }

  private static String changeFrom(Map<String, String> attributes) {
    try {
      Verdict.Outcome live = Verdict.Outcome.valueOf(attributes.get(ShadowGate.ATTR_LIVE));
      Verdict.Outcome shadow = Verdict.Outcome.valueOf(attributes.get(ShadowGate.ATTR_SHADOW));
      return ChangeKind.between(live, shadow).name();
    } catch (RuntimeException e) {
      return ChangeKind.UNCHANGED.name();
    }
  }

  /** A Markdown table of divergences per tool. */
  public String toMarkdown() {
    StringBuilder sb = new StringBuilder("# Shadow policy divergences\n\n");
    sb.append("Divergences: ").append(divergences).append('\n');
    if (from != null && to != null) {
      sb.append("Period: ").append(from).append(" to ").append(to).append('\n');
    }
    sb.append("\n| Tool | Divergences | Would block | Would escalate | Would relax | Examples |\n");
    sb.append("|---|---:|---:|---:|---:|---|\n");
    for (ToolRow t : perTool) {
      sb.append("| ")
          .append(Text.cell(t.toolName()))
          .append(" | ")
          .append(t.divergences())
          .append(" | ")
          .append(t.wouldBlock())
          .append(" | ")
          .append(t.wouldEscalate())
          .append(" | ")
          .append(t.wouldRelax())
          .append(" | ")
          .append(
              t.exampleCallIds().stream()
                  .map(Text::cell)
                  .collect(java.util.stream.Collectors.joining(", ")))
          .append(" |\n");
    }
    return sb.toString();
  }

  /** One JSON object per tool row, preceded by a summary line. All values are strings. */
  public String toJsonLines() {
    StringBuilder sb = new StringBuilder();
    Map<String, String> summary = new LinkedHashMap<>();
    summary.put("type", "summary");
    summary.put("divergences", Long.toString(divergences));
    if (from != null && to != null) {
      summary.put("from", from.toString());
      summary.put("to", to.toString());
    }
    sb.append(JsonLite.writeObject(summary)).append('\n');
    for (ToolRow t : perTool) {
      Map<String, String> line = new LinkedHashMap<>();
      line.put("type", "tool");
      line.put("tool", t.toolName());
      line.put("divergences", Long.toString(t.divergences()));
      line.put("wouldBlock", Long.toString(t.wouldBlock()));
      line.put("wouldEscalate", Long.toString(t.wouldEscalate()));
      line.put("wouldRelax", Long.toString(t.wouldRelax()));
      line.put("examples", String.join(",", t.exampleCallIds()));
      sb.append(JsonLite.writeObject(line)).append('\n');
    }
    return sb.toString();
  }
}
