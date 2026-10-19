package io.vegaduta.agent.scanner;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.risk.AsiRisk;
import io.vegaduta.agent.api.risk.ToolClassification;
import io.vegaduta.agent.core.classification.LethalTrifectaDetector;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The result of a scan: every tool, the unclassified ones, approval coverage, the lethal-trifecta
 * report, OWASP ASI exposure and a 0-100 readiness score with the deductions that explain it.
 *
 * @param tools every tool found, with its effective classification
 * @param unclassified names of tools without {@code @Governed}
 * @param sideEffectingCount how many tools are side-effecting under their effective classification
 * @param approvalGatedCount how many side-effecting tools the gate would stop for approval
 * @param approvalCoverage {@code approvalGatedCount / sideEffectingCount} in percent; 100 when
 *     nothing is side-effecting
 * @param trifecta the lethal-trifecta report over the whole set
 * @param asiExposure for each OWASP ASI item, the tools that declare exposure to it (declared
 *     through {@code @Governed(risks=...)} or implied by the classification flags)
 * @param score readiness 0-100
 * @param deductions why the score is below 100, most expensive first
 */
@Experimental
public record RiskMap(
    List<ScannedTool> tools,
    List<String> unclassified,
    int sideEffectingCount,
    int approvalGatedCount,
    int approvalCoverage,
    LethalTrifectaDetector.Report trifecta,
    Map<AsiRisk, List<String>> asiExposure,
    int score,
    List<Deduction> deductions) {

  /**
   * One reason the score is below 100.
   *
   * @param code a stable identifier such as {@code UNCLASSIFIED_TOOLS}
   * @param points points taken off
   * @param reason a sentence a reviewer can act on
   */
  public record Deduction(String code, int points, String reason) {
    public Deduction {
      Objects.requireNonNull(code, "code");
      Objects.requireNonNull(reason, "reason");
      if (points < 0) {
        throw new IllegalArgumentException("points must not be negative");
      }
    }
  }

  public RiskMap {
    tools = List.copyOf(tools);
    unclassified = List.copyOf(unclassified);
    Objects.requireNonNull(trifecta, "trifecta");
    Map<AsiRisk, List<String>> exposure = new LinkedHashMap<>();
    asiExposure.forEach((k, v) -> exposure.put(k, List.copyOf(v)));
    asiExposure = java.util.Collections.unmodifiableMap(exposure);
    deductions = List.copyOf(deductions);
    if (score < 0 || score > 100) {
      throw new IllegalArgumentException("score must be within 0..100");
    }
  }

  /** The number of ASI items (out of ten) at least one tool is exposed to. */
  public int asiItemsExposed() {
    return (int) asiExposure.values().stream().filter(l -> !l.isEmpty()).count();
  }

  /** A Markdown report: summary, deductions, the tool table, the trifecta and the ASI table. */
  public String toMarkdown() {
    StringBuilder sb = new StringBuilder();
    sb.append("# VegaDuta risk map\n\n");
    sb.append("**Readiness score: ").append(score).append("/100**\n\n");
    sb.append("| Metric | Value |\n|---|---|\n");
    row(sb, "Tools found", String.valueOf(tools.size()));
    row(sb, "Unclassified", String.valueOf(unclassified.size()));
    row(sb, "Side-effecting", String.valueOf(sideEffectingCount));
    row(
        sb,
        "Approval coverage",
        approvalCoverage + "% (" + approvalGatedCount + "/" + sideEffectingCount + ")");
    row(sb, "Lethal trifecta", trifecta.holds() ? "PRESENT" : "absent");
    row(sb, "OWASP ASI items exposed", asiItemsExposed() + "/" + AsiRisk.values().length);
    sb.append('\n');

    if (!deductions.isEmpty()) {
      sb.append("## Deductions\n\n| Points | Code | Why |\n|---|---|---|\n");
      for (Deduction d : deductions) {
        sb.append("| -")
            .append(d.points())
            .append(" | `")
            .append(d.code())
            .append("` | ")
            .append(cell(d.reason()))
            .append(" |\n");
      }
      sb.append('\n');
    }

    sb.append("## Tools\n\n");
    sb.append("| Tool | Location | Risk | Side effects | Private data | Untrusted input | ");
    sb.append("Exfiltration | Approval | ASI |\n|---|---|---|---|---|---|---|---|---|\n");
    for (ScannedTool t : tools) {
      ToolClassification c = t.classification();
      sb.append("| `")
          .append(cell(t.toolName()))
          .append("` | `")
          .append(cell(t.tool().location()))
          .append("` | ")
          .append(t.classified() ? c.risk().name() : "UNCLASSIFIED (assumed " + c.risk() + ")")
          .append(" | ")
          .append(yesNo(c.sideEffecting()))
          .append(" | ")
          .append(yesNo(c.readsPrivateData()))
          .append(" | ")
          .append(yesNo(c.consumesUntrustedInput()))
          .append(" | ")
          .append(yesNo(c.exfiltrationSink()))
          .append(" | ")
          .append(t.approvalGated() ? "gated" : c.sideEffecting() ? "none" : "n/a")
          .append(" | ")
          .append(c.risks().isEmpty() ? "-" : joinRisks(c))
          .append(" |\n");
    }
    sb.append('\n');

    sb.append("## Lethal trifecta\n\n");
    if (trifecta.holds()) {
      sb.append(
          "All three capabilities are present. A prompt injection in the untrusted input can"
              + " exfiltrate the private data through a sink.\n\n");
    } else {
      sb.append("Not present.\n\n");
    }
    sb.append("- Private readers: ").append(listOrNone(trifecta.privateReaders())).append('\n');
    sb.append("- Untrusted consumers: ")
        .append(listOrNone(trifecta.untrustedConsumers()))
        .append('\n');
    sb.append("- Exfiltration sinks: ").append(listOrNone(trifecta.sinks())).append("\n\n");

    sb.append("## OWASP Top 10 for Agentic Applications\n\n");
    sb.append("| Item | Title | Exposed through | SDK control |\n|---|---|---|---|\n");
    for (AsiRisk risk : AsiRisk.values()) {
      List<String> exposed = asiExposure.getOrDefault(risk, List.of());
      sb.append("| ")
          .append(risk.id())
          .append(" | ")
          .append(risk.title())
          .append(" | ")
          .append(listOrNone(exposed))
          .append(" | ")
          .append(AsiControls.describe(risk))
          .append(" |\n");
    }
    return sb.toString();
  }

  /** The same content as JSON, for dashboards and CI annotations. */
  public String toJson() {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("score", score);
    root.put("toolCount", tools.size());
    root.put("unclassified", unclassified);
    root.put("sideEffectingCount", sideEffectingCount);
    root.put("approvalGatedCount", approvalGatedCount);
    root.put("approvalCoverage", approvalCoverage);

    List<Object> deductionRows = new ArrayList<>();
    for (Deduction d : deductions) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("code", d.code());
      row.put("points", d.points());
      row.put("reason", d.reason());
      deductionRows.add(row);
    }
    root.put("deductions", deductionRows);

    List<Object> toolRows = new ArrayList<>();
    for (ScannedTool t : tools) {
      ToolClassification c = t.classification();
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("name", t.toolName());
      row.put("location", t.tool().location());
      row.put("description", t.tool().description());
      row.put("toolAnnotation", t.tool().toolAnnotation());
      row.put("classified", t.classified());
      row.put("risk", c.risk());
      row.put("sideEffecting", c.sideEffecting());
      row.put("readsPrivateData", c.readsPrivateData());
      row.put("consumesUntrustedInput", c.consumesUntrustedInput());
      row.put("exfiltrationSink", c.exfiltrationSink());
      row.put("idempotent", c.idempotent());
      row.put("requiresApproval", c.requiresApproval());
      row.put("approvalGated", t.approvalGated());
      row.put("risks", new ArrayList<>(c.risks()));
      toolRows.add(row);
    }
    root.put("tools", toolRows);

    Map<String, Object> trifectaNode = new LinkedHashMap<>();
    trifectaNode.put("holds", trifecta.holds());
    trifectaNode.put("privateReaders", trifecta.privateReaders());
    trifectaNode.put("untrustedConsumers", trifecta.untrustedConsumers());
    trifectaNode.put("sinks", trifecta.sinks());
    root.put("lethalTrifecta", trifectaNode);

    Map<String, Object> asi = new LinkedHashMap<>();
    for (AsiRisk risk : AsiRisk.values()) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("title", risk.title());
      item.put("exposedThrough", asiExposure.getOrDefault(risk, List.of()));
      item.put("sdkControl", AsiControls.describe(risk));
      asi.put(risk.id(), item);
    }
    root.put("owaspAsi", asi);
    root.put("asiItemsExposed", asiItemsExposed());
    return Json.write(root);
  }

  private static void row(StringBuilder sb, String metric, String value) {
    sb.append("| ").append(metric).append(" | ").append(value).append(" |\n");
  }

  private static String yesNo(boolean value) {
    return value ? "yes" : "no";
  }

  private static String cell(String value) {
    return value.replace("|", "\\|").replace("\r", " ").replace("\n", " ");
  }

  private static String joinRisks(ToolClassification c) {
    List<String> ids = new ArrayList<>();
    for (AsiRisk r : c.risks()) {
      ids.add(r.id());
    }
    return String.join(", ", ids);
  }

  private static String listOrNone(List<String> names) {
    if (names.isEmpty()) {
      return "none";
    }
    List<String> cells = new ArrayList<>(names.size());
    for (String n : names) {
      cells.add(cell(n));
    }
    return String.join(", ", cells);
  }
}
