package io.vegaduta.agent.core.cost;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.tool.ToolStatus;
import io.vegaduta.agent.core.text.Text;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Who spent what. Groups a {@link CostLedger} by tenant, agent, team and feature; for each group
 * reports runs, tool calls, tokens, USD by category (tools, model, human review), denied and
 * budget-exceeded counts and the unit economics "USD per run"; and lists the most expensive tools
 * and runs. Immutable snapshot: build a new one to see new spend.
 *
 * <pre>{@code
 * ShowbackReport report = ShowbackReport.from(ledger);
 * report.byTeam().forEach(row -> System.out.println(row.key() + " " + row.totalUsd()));
 * Files.writeString(path, report.toCsv());
 * }</pre>
 */
@Experimental
public final class ShowbackReport {

  /** The grouping axes a report offers. */
  public enum Dimension {
    TENANT(CostAttribution::tenantOrUnattributed),
    AGENT(CostAttribution::agentOrUnattributed),
    TEAM(CostAttribution::teamOrUnattributed),
    FEATURE(CostAttribution::featureOrUnattributed);

    private final Function<RunContext, String> key;

    Dimension(Function<RunContext, String> key) {
      this.key = key;
    }

    public String keyOf(RunContext context) {
      return key.apply(context);
    }
  }

  /**
   * One group's totals.
   *
   * @param key the tenant, agent, team or feature
   * @param runs distinct run ids seen in the group
   * @param toolCalls every observed tool call, whatever its outcome
   * @param inputTokens prompt tokens
   * @param outputTokens completion tokens
   * @param toolUsd USD charged for tool calls
   * @param modelUsd USD charged for model calls
   * @param reviewUsd USD charged for human review
   * @param denied tool calls refused by a gate, guardrail or hook
   * @param budgetExceeded tool calls refused by the budget
   */
  public record Row(
      String key,
      long runs,
      long toolCalls,
      long inputTokens,
      long outputTokens,
      BigDecimal toolUsd,
      BigDecimal modelUsd,
      BigDecimal reviewUsd,
      long denied,
      long budgetExceeded) {

    public long totalTokens() {
      return inputTokens + outputTokens;
    }

    public BigDecimal totalUsd() {
      return toolUsd.add(modelUsd).add(reviewUsd);
    }

    /** Unit economics: total USD divided by runs, six decimal places; zero with no runs. */
    public BigDecimal usdPerRun() {
      return Usd.divide(totalUsd(), runs);
    }
  }

  /** One tool's totals across every run. */
  public record ToolTotal(String toolName, long calls, long denied, BigDecimal usd) {}

  /** One run's totals. */
  public record RunTotal(
      String runId, String tenant, String agent, String team, long toolCalls, BigDecimal usd) {}

  private static final String[] CSV_HEADER = {
    "dimension",
    "key",
    "runs",
    "toolCalls",
    "inputTokens",
    "outputTokens",
    "toolUsd",
    "modelUsd",
    "reviewUsd",
    "totalUsd",
    "denied",
    "budgetExceeded",
    "usdPerRun"
  };

  private final Map<Dimension, List<Row>> groups;
  private final Row total;
  private final List<ToolTotal> tools;
  private final List<RunTotal> runs;

  private ShowbackReport(
      Map<Dimension, List<Row>> groups, Row total, List<ToolTotal> tools, List<RunTotal> runs) {
    this.groups = groups;
    this.total = total;
    this.tools = tools;
    this.runs = runs;
  }

  /** Snapshots the ledger. */
  public static ShowbackReport from(CostLedger ledger) {
    Objects.requireNonNull(ledger, "ledger");
    CostLedger.Snapshot snapshot = ledger.snapshot();
    List<CostLedger.ToolCallCost> toolCalls = snapshot.toolCalls();
    List<CostLedger.ModelUsage> usages = snapshot.modelUsages();
    List<CostLedger.ReviewCost> reviews = snapshot.reviews();

    Map<Dimension, List<Row>> groups = new EnumMap<>(Dimension.class);
    for (Dimension d : Dimension.values()) {
      groups.put(d, group(toolCalls, usages, reviews, d::keyOf));
    }
    List<Row> all = group(toolCalls, usages, reviews, c -> "total");
    Row total = all.isEmpty() ? emptyRow("total") : all.get(0);
    return new ShowbackReport(
        groups, total, toolTotals(toolCalls), runTotals(toolCalls, usages, reviews));
  }

  // --- aggregation -----------------------------------------------------------------------------

  private static List<Row> group(
      List<CostLedger.ToolCallCost> toolCalls,
      List<CostLedger.ModelUsage> usages,
      List<CostLedger.ReviewCost> reviews,
      Function<RunContext, String> keyOf) {
    Map<String, Accumulator> acc = new LinkedHashMap<>();
    for (CostLedger.ToolCallCost t : toolCalls) {
      Accumulator a = acc.computeIfAbsent(keyOf.apply(t.context()), Accumulator::new);
      a.runs.add(t.context().runId());
      a.toolCalls++;
      a.toolUsd = a.toolUsd.add(t.usd());
      if (t.status() == ToolStatus.DENIED) {
        a.denied++;
      } else if (t.status() == ToolStatus.BUDGET_EXCEEDED) {
        a.budgetExceeded++;
      }
    }
    for (CostLedger.ModelUsage m : usages) {
      Accumulator a = acc.computeIfAbsent(keyOf.apply(m.context()), Accumulator::new);
      a.runs.add(m.context().runId());
      a.inputTokens += m.inputTokens();
      a.outputTokens += m.outputTokens();
      a.modelUsd = a.modelUsd.add(m.usd());
    }
    for (CostLedger.ReviewCost r : reviews) {
      Accumulator a = acc.computeIfAbsent(keyOf.apply(r.context()), Accumulator::new);
      a.runs.add(r.context().runId());
      a.reviewUsd = a.reviewUsd.add(r.usd());
    }
    List<Row> rows = new ArrayList<>(acc.size());
    for (Accumulator a : acc.values()) {
      rows.add(a.toRow());
    }
    rows.sort(Comparator.comparing(Row::totalUsd).reversed().thenComparing(Row::key));
    return List.copyOf(rows);
  }

  private static List<ToolTotal> toolTotals(List<CostLedger.ToolCallCost> toolCalls) {
    Map<String, long[]> counts = new LinkedHashMap<>();
    Map<String, BigDecimal> usd = new LinkedHashMap<>();
    for (CostLedger.ToolCallCost t : toolCalls) {
      long[] c = counts.computeIfAbsent(t.toolName(), k -> new long[2]);
      c[0]++;
      if (t.status() == ToolStatus.DENIED) {
        c[1]++;
      }
      usd.merge(t.toolName(), t.usd(), BigDecimal::add);
    }
    List<ToolTotal> totals = new ArrayList<>(counts.size());
    for (Map.Entry<String, long[]> e : counts.entrySet()) {
      totals.add(new ToolTotal(e.getKey(), e.getValue()[0], e.getValue()[1], usd.get(e.getKey())));
    }
    totals.sort(Comparator.comparing(ToolTotal::usd).reversed().thenComparing(ToolTotal::toolName));
    return List.copyOf(totals);
  }

  private static List<RunTotal> runTotals(
      List<CostLedger.ToolCallCost> toolCalls,
      List<CostLedger.ModelUsage> usages,
      List<CostLedger.ReviewCost> reviews) {
    Map<String, RunContext> contexts = new LinkedHashMap<>();
    Map<String, Long> calls = new LinkedHashMap<>();
    Map<String, BigDecimal> usd = new LinkedHashMap<>();
    for (CostLedger.ToolCallCost t : toolCalls) {
      String id = t.context().runId();
      contexts.putIfAbsent(id, t.context());
      calls.merge(id, 1L, Long::sum);
      usd.merge(id, t.usd(), BigDecimal::add);
    }
    for (CostLedger.ModelUsage m : usages) {
      String id = m.context().runId();
      contexts.putIfAbsent(id, m.context());
      usd.merge(id, m.usd(), BigDecimal::add);
    }
    for (CostLedger.ReviewCost r : reviews) {
      String id = r.context().runId();
      contexts.putIfAbsent(id, r.context());
      usd.merge(id, r.usd(), BigDecimal::add);
    }
    List<RunTotal> totals = new ArrayList<>(contexts.size());
    for (Map.Entry<String, RunContext> e : contexts.entrySet()) {
      RunContext c = e.getValue();
      totals.add(
          new RunTotal(
              e.getKey(),
              CostAttribution.tenantOrUnattributed(c),
              CostAttribution.agentOrUnattributed(c),
              CostAttribution.teamOrUnattributed(c),
              calls.getOrDefault(e.getKey(), 0L),
              usd.getOrDefault(e.getKey(), Usd.ZERO)));
    }
    totals.sort(Comparator.comparing(RunTotal::usd).reversed().thenComparing(RunTotal::runId));
    return List.copyOf(totals);
  }

  private static Row emptyRow(String key) {
    return new Row(key, 0, 0, 0, 0, Usd.ZERO, Usd.ZERO, Usd.ZERO, 0, 0);
  }

  private static final class Accumulator {
    final String key;
    final Set<String> runs = new LinkedHashSet<>();
    long toolCalls;
    long inputTokens;
    long outputTokens;
    BigDecimal toolUsd = Usd.ZERO;
    BigDecimal modelUsd = Usd.ZERO;
    BigDecimal reviewUsd = Usd.ZERO;
    long denied;
    long budgetExceeded;

    Accumulator(String key) {
      this.key = key;
    }

    Row toRow() {
      return new Row(
          key,
          runs.size(),
          toolCalls,
          inputTokens,
          outputTokens,
          toolUsd,
          modelUsd,
          reviewUsd,
          denied,
          budgetExceeded);
    }
  }

  // --- reading ---------------------------------------------------------------------------------

  /** Rows for one dimension, most expensive first. */
  public List<Row> by(Dimension dimension) {
    return groups.get(dimension);
  }

  public List<Row> byTenant() {
    return by(Dimension.TENANT);
  }

  public List<Row> byAgent() {
    return by(Dimension.AGENT);
  }

  public List<Row> byTeam() {
    return by(Dimension.TEAM);
  }

  public List<Row> byFeature() {
    return by(Dimension.FEATURE);
  }

  /** Everything in one row. */
  public Row total() {
    return total;
  }

  /** The {@code n} most expensive tools (by USD, then by name). */
  public List<ToolTotal> topTools(int n) {
    return tools.subList(0, Math.min(Math.max(n, 0), tools.size()));
  }

  /** The {@code n} most expensive runs (by USD, then by run id). */
  public List<RunTotal> topRuns(int n) {
    return runs.subList(0, Math.min(Math.max(n, 0), runs.size()));
  }

  // --- rendering -------------------------------------------------------------------------------

  /** A Markdown document with one table per dimension plus top-10 tools and runs. */
  public String toMarkdown() {
    return toMarkdown(10);
  }

  public String toMarkdown(int topN) {
    StringBuilder sb = new StringBuilder();
    sb.append("# Agent cost showback\n\n");
    sb.append("Total: ")
        .append(total.runs())
        .append(" runs, ")
        .append(total.toolCalls())
        .append(" tool calls, ")
        .append(total.totalTokens())
        .append(" tokens, USD ")
        .append(total.totalUsd().toPlainString())
        .append(" (tools ")
        .append(total.toolUsd().toPlainString())
        .append(", model ")
        .append(total.modelUsd().toPlainString())
        .append(", human review ")
        .append(total.reviewUsd().toPlainString())
        .append("), USD per run ")
        .append(total.usdPerRun().toPlainString())
        .append(".\n\n");
    for (Dimension d : Dimension.values()) {
      String name = d.name().toLowerCase(Locale.ROOT);
      sb.append("## By ").append(name).append("\n\n");
      sb.append("| ")
          .append(name)
          .append(
              " | runs | tool calls | tokens | tools USD | model USD | review USD | total USD"
                  + " | USD/run | denied | budget exceeded |\n");
      sb.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
      for (Row r : by(d)) {
        sb.append("| ")
            .append(mdCell(r.key()))
            .append(" | ")
            .append(r.runs())
            .append(" | ")
            .append(r.toolCalls())
            .append(" | ")
            .append(r.totalTokens())
            .append(" | ")
            .append(r.toolUsd().toPlainString())
            .append(" | ")
            .append(r.modelUsd().toPlainString())
            .append(" | ")
            .append(r.reviewUsd().toPlainString())
            .append(" | ")
            .append(r.totalUsd().toPlainString())
            .append(" | ")
            .append(r.usdPerRun().toPlainString())
            .append(" | ")
            .append(r.denied())
            .append(" | ")
            .append(r.budgetExceeded())
            .append(" |\n");
      }
      sb.append('\n');
    }
    sb.append("## Top tools\n\n| tool | calls | denied | USD |\n|---|---:|---:|---:|\n");
    for (ToolTotal t : topTools(topN)) {
      sb.append("| ")
          .append(mdCell(t.toolName()))
          .append(" | ")
          .append(t.calls())
          .append(" | ")
          .append(t.denied())
          .append(" | ")
          .append(t.usd().toPlainString())
          .append(" |\n");
    }
    sb.append("\n## Top runs\n\n| run | tenant | agent | team | tool calls | USD |\n");
    sb.append("|---|---|---|---|---:|---:|\n");
    for (RunTotal r : topRuns(topN)) {
      sb.append("| ")
          .append(mdCell(r.runId()))
          .append(" | ")
          .append(mdCell(r.tenant()))
          .append(" | ")
          .append(mdCell(r.agent()))
          .append(" | ")
          .append(mdCell(r.team()))
          .append(" | ")
          .append(r.toolCalls())
          .append(" | ")
          .append(r.usd().toPlainString())
          .append(" |\n");
    }
    return sb.toString();
  }

  /**
   * RFC 4180 CSV with one line per (dimension, key) row and a final {@code total} line. Columns:
   * {@code dimension,key,runs,toolCalls,inputTokens,outputTokens,toolUsd,modelUsd,reviewUsd,
   * totalUsd,denied,budgetExceeded,usdPerRun}.
   */
  public String toCsv() {
    StringBuilder sb = new StringBuilder();
    sb.append(String.join(",", CSV_HEADER)).append("\r\n");
    for (Dimension d : Dimension.values()) {
      for (Row r : by(d)) {
        csvRow(sb, d.name(), r);
      }
    }
    csvRow(sb, "TOTAL", total);
    return sb.toString();
  }

  private static void csvRow(StringBuilder sb, String dimension, Row r) {
    sb.append(csv(dimension))
        .append(',')
        .append(csv(r.key()))
        .append(',')
        .append(r.runs())
        .append(',')
        .append(r.toolCalls())
        .append(',')
        .append(r.inputTokens())
        .append(',')
        .append(r.outputTokens())
        .append(',')
        .append(r.toolUsd().toPlainString())
        .append(',')
        .append(r.modelUsd().toPlainString())
        .append(',')
        .append(r.reviewUsd().toPlainString())
        .append(',')
        .append(r.totalUsd().toPlainString())
        .append(',')
        .append(r.denied())
        .append(',')
        .append(r.budgetExceeded())
        .append(',')
        .append(r.usdPerRun().toPlainString())
        .append("\r\n");
  }

  /**
   * A CSV cell. Quoted when it contains a separator, and prefixed with an apostrophe when it starts
   * with a character spreadsheets interpret as a formula ({@code = + - @}, tab, CR), so a tenant or
   * agent id chosen by a user cannot execute when the file is opened.
   */
  static String csv(String value) {
    return Text.csv(value);
  }

  private static String mdCell(String value) {
    return Text.cell(value);
  }
}
