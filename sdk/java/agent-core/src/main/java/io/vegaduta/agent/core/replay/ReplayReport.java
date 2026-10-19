package io.vegaduta.agent.core.replay;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.audit.AuditOutcome;
import io.vegaduta.agent.api.gate.Verdict;
import io.vegaduta.agent.core.hook.JsonLite;
import io.vegaduta.agent.core.text.Text;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * What a {@link PolicyReplay} found.
 *
 * @param policyDescription the candidate policy's knobs, one line
 * @param totals verdict counts under the candidate policy
 * @param deltas how many calls would change, by kind
 * @param reviewLoad the estimated human-review cost of the candidate
 * @param perTool one row per tool, in order of first appearance
 * @param examples up to N example call ids per change kind
 * @param rows every replayed call
 * @param from the earliest recorded call, or {@code null} when there were none
 * @param to the latest recorded call, or {@code null} when there were none
 */
@Experimental
public record ReplayReport(
    String policyDescription,
    Totals totals,
    Deltas deltas,
    ReviewLoad reviewLoad,
    List<ToolRow> perTool,
    Map<ChangeKind, List<String>> examples,
    List<Row> rows,
    @Nullable Instant from,
    @Nullable Instant to) {

  public ReplayReport {
    Objects.requireNonNull(policyDescription, "policyDescription");
    Objects.requireNonNull(totals, "totals");
    Objects.requireNonNull(deltas, "deltas");
    Objects.requireNonNull(reviewLoad, "reviewLoad");
    perTool = List.copyOf(perTool);
    Map<ChangeKind, List<String>> copy = new EnumMap<>(ChangeKind.class);
    examples.forEach((k, v) -> copy.put(k, List.copyOf(v)));
    examples = Collections.unmodifiableMap(copy);
    rows = List.copyOf(rows);
  }

  /** Verdict counts under the candidate policy. */
  public record Totals(long calls, long wouldAllow, long wouldDeny, long wouldRequireApproval) {}

  /** Calls whose verdict would change, by kind. */
  public record Deltas(long newlyBlocked, long newlyEscalated, long relaxed, long unchanged) {

    public long changed() {
      return newlyBlocked + newlyEscalated + relaxed;
    }
  }

  /**
   * Estimated human-review cost: every newly escalated call is one more approval request.
   *
   * @param newReviews calls that would newly wait for a human
   * @param spanDays the recorded period in days, at least one
   * @param perDay {@code newReviews / spanDays}
   */
  public record ReviewLoad(long newReviews, double spanDays, double perDay) {}

  /** One replayed call. */
  public record Row(
      String callId,
      String toolName,
      Instant recordedAt,
      Verdict.Outcome actual,
      AuditOutcome actualOutcome,
      Verdict.Outcome replayed,
      String replayedReason,
      ChangeKind change) {}

  /** Aggregate for one tool. */
  public record ToolRow(
      String toolName,
      long calls,
      long wouldAllow,
      long wouldDeny,
      long wouldRequireApproval,
      long newlyBlocked,
      long newlyEscalated,
      long relaxed) {}

  static ReplayReport build(String policyDescription, List<Row> rows, int maxExamples) {
    long allow = 0;
    long deny = 0;
    long approval = 0;
    long blocked = 0;
    long escalated = 0;
    long relaxed = 0;
    long unchanged = 0;
    Instant from = null;
    Instant to = null;
    Map<String, long[]> perTool = new LinkedHashMap<>();
    Map<ChangeKind, List<String>> examples = new EnumMap<>(ChangeKind.class);
    for (Row row : rows) {
      long[] t = perTool.computeIfAbsent(row.toolName(), k -> new long[7]);
      t[0]++;
      switch (row.replayed()) {
        case ALLOW -> {
          allow++;
          t[1]++;
        }
        case DENY -> {
          deny++;
          t[2]++;
        }
        case REQUIRE_APPROVAL -> {
          approval++;
          t[3]++;
        }
      }
      switch (row.change()) {
        case NEWLY_BLOCKED -> {
          blocked++;
          t[4]++;
        }
        case NEWLY_ESCALATED -> {
          escalated++;
          t[5]++;
        }
        case RELAXED -> {
          relaxed++;
          t[6]++;
        }
        case UNCHANGED -> unchanged++;
      }
      if (row.change() != ChangeKind.UNCHANGED) {
        List<String> ids = examples.computeIfAbsent(row.change(), k -> new ArrayList<>());
        if (ids.size() < maxExamples) {
          ids.add(row.callId());
        }
      }
      if (from == null || row.recordedAt().isBefore(from)) {
        from = row.recordedAt();
      }
      if (to == null || row.recordedAt().isAfter(to)) {
        to = row.recordedAt();
      }
    }
    List<ToolRow> toolRows = new ArrayList<>(perTool.size());
    perTool.forEach(
        (name, t) -> toolRows.add(new ToolRow(name, t[0], t[1], t[2], t[3], t[4], t[5], t[6])));
    double spanDays = spanDays(from, to);
    return new ReplayReport(
        policyDescription,
        new Totals(rows.size(), allow, deny, approval),
        new Deltas(blocked, escalated, relaxed, unchanged),
        new ReviewLoad(escalated, spanDays, escalated / spanDays),
        toolRows,
        examples,
        rows,
        from,
        to);
  }

  /** The recorded span in days, never less than one so a short sample does not inflate the rate. */
  static double spanDays(@Nullable Instant from, @Nullable Instant to) {
    if (from == null || to == null) {
      return 1.0;
    }
    double days = Duration.between(from, to).toMillis() / 86_400_000.0;
    return Math.max(1.0, days);
  }

  /** A Markdown summary with totals, deltas, review load, a per-tool table and examples. */
  public String toMarkdown() {
    StringBuilder sb = new StringBuilder();
    sb.append("# Policy replay\n\n");
    sb.append("Candidate: ").append(policyDescription).append("\n\n");
    if (from != null && to != null) {
      sb.append("Period: ").append(from).append(" to ").append(to).append('\n');
    }
    sb.append("Calls replayed: ").append(totals.calls()).append("\n\n");
    sb.append("## Verdicts under the candidate\n\n");
    sb.append("| Allow | Require approval | Deny |\n|---:|---:|---:|\n");
    sb.append("| ")
        .append(totals.wouldAllow())
        .append(" | ")
        .append(totals.wouldRequireApproval())
        .append(" | ")
        .append(totals.wouldDeny())
        .append(" |\n\n");
    sb.append("## Changes versus what actually happened\n\n");
    sb.append("| Newly blocked | Newly escalated | Relaxed | Unchanged |\n|---:|---:|---:|---:|\n");
    sb.append("| ")
        .append(deltas.newlyBlocked())
        .append(" | ")
        .append(deltas.newlyEscalated())
        .append(" | ")
        .append(deltas.relaxed())
        .append(" | ")
        .append(deltas.unchanged())
        .append(" |\n\n");
    sb.append("## Estimated review load\n\n");
    sb.append(
        String.format(
            Locale.ROOT,
            "%d new human reviews over %.1f day(s): %.2f per day.%n%n",
            reviewLoad.newReviews(),
            reviewLoad.spanDays(),
            reviewLoad.perDay()));
    sb.append("## Per tool\n\n");
    sb.append("| Tool | Calls | Allow | Approval | Deny | Newly blocked | Newly escalated |");
    sb.append(" Relaxed |\n|---|---:|---:|---:|---:|---:|---:|---:|\n");
    for (ToolRow t : perTool) {
      sb.append("| ")
          .append(Text.cell(t.toolName()))
          .append(" | ")
          .append(t.calls())
          .append(" | ")
          .append(t.wouldAllow())
          .append(" | ")
          .append(t.wouldRequireApproval())
          .append(" | ")
          .append(t.wouldDeny())
          .append(" | ")
          .append(t.newlyBlocked())
          .append(" | ")
          .append(t.newlyEscalated())
          .append(" | ")
          .append(t.relaxed())
          .append(" |\n");
    }
    if (!examples.isEmpty()) {
      sb.append("\n## Examples\n\n");
      examples.forEach(
          (kind, ids) ->
              sb.append("- ")
                  .append(kind.name().toLowerCase(Locale.ROOT).replace('_', ' '))
                  .append(": ")
                  .append(ids.stream().map(Text::cell).collect(Collectors.joining(", ")))
                  .append('\n'));
    }
    return sb.toString();
  }

  /**
   * One JSON object per line: a {@code summary} line, one {@code tool} line per tool and one {@code
   * call} line per replayed call. All values are strings.
   */
  public String toJsonLines() {
    StringBuilder sb = new StringBuilder();
    Map<String, String> summary = new LinkedHashMap<>();
    summary.put("type", "summary");
    summary.put("policy", policyDescription);
    summary.put("calls", Long.toString(totals.calls()));
    summary.put("wouldAllow", Long.toString(totals.wouldAllow()));
    summary.put("wouldDeny", Long.toString(totals.wouldDeny()));
    summary.put("wouldRequireApproval", Long.toString(totals.wouldRequireApproval()));
    summary.put("newlyBlocked", Long.toString(deltas.newlyBlocked()));
    summary.put("newlyEscalated", Long.toString(deltas.newlyEscalated()));
    summary.put("relaxed", Long.toString(deltas.relaxed()));
    summary.put("unchanged", Long.toString(deltas.unchanged()));
    summary.put("reviewsPerDay", String.format(Locale.ROOT, "%.4f", reviewLoad.perDay()));
    summary.put("spanDays", String.format(Locale.ROOT, "%.4f", reviewLoad.spanDays()));
    if (from != null && to != null) {
      summary.put("from", from.toString());
      summary.put("to", to.toString());
    }
    sb.append(JsonLite.writeObject(summary)).append('\n');
    for (ToolRow t : perTool) {
      Map<String, String> line = new LinkedHashMap<>();
      line.put("type", "tool");
      line.put("tool", t.toolName());
      line.put("calls", Long.toString(t.calls()));
      line.put("wouldAllow", Long.toString(t.wouldAllow()));
      line.put("wouldDeny", Long.toString(t.wouldDeny()));
      line.put("wouldRequireApproval", Long.toString(t.wouldRequireApproval()));
      line.put("newlyBlocked", Long.toString(t.newlyBlocked()));
      line.put("newlyEscalated", Long.toString(t.newlyEscalated()));
      line.put("relaxed", Long.toString(t.relaxed()));
      sb.append(JsonLite.writeObject(line)).append('\n');
    }
    for (Row r : rows) {
      Map<String, String> line = new LinkedHashMap<>();
      line.put("type", "call");
      line.put("callId", r.callId());
      line.put("tool", r.toolName());
      line.put("recordedAt", r.recordedAt().toString());
      line.put("actual", r.actual().name());
      line.put("actualOutcome", r.actualOutcome().name());
      line.put("replayed", r.replayed().name());
      line.put("replayedReason", r.replayedReason());
      line.put("change", r.change().name());
      sb.append(JsonLite.writeObject(line)).append('\n');
    }
    return sb.toString();
  }
}
