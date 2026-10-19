package io.vegaduta.agent.scanner;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.risk.AsiRisk;
import io.vegaduta.agent.api.risk.ToolClassification;
import io.vegaduta.agent.core.GovernanceMode;
import io.vegaduta.agent.core.GovernancePolicy;
import io.vegaduta.agent.core.classification.GovernedAnnotations;
import io.vegaduta.agent.core.classification.LethalTrifectaDetector;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builds a {@link RiskMap} from classes. Each tool found by {@link ToolMethodFinder} is classified
 * through {@link GovernedAnnotations} when it carries {@code @Governed}; otherwise it is marked
 * unclassified and given what {@code WARN} mode would assume at run time: the policy's default
 * risk, side-effecting, no further flags.
 *
 * <p>Scoring starts at 100 and deducts:
 *
 * <ul>
 *   <li>10 per unclassified tool, at most 40 ({@code UNCLASSIFIED_TOOLS});
 *   <li>up to 20 in proportion to side-effecting tools the gate would not stop ({@code
 *       APPROVAL_GAP});
 *   <li>25 when the lethal trifecta holds, or 10 when the policy is {@code STRICT} and therefore
 *       forces approval on the sinks ({@code LETHAL_TRIFECTA});
 *   <li>10 when an exfiltration sink in a trifecta is not approval-gated ({@code UNGATED_SINK});
 *   <li>2 per classified tool with no ASI tags, at most 10 ({@code NO_ASI_TAGS}).
 * </ul>
 */
@Experimental
public final class RiskMapScanner {

  static final int UNCLASSIFIED_POINTS = 10;
  static final int UNCLASSIFIED_CAP = 40;
  static final int APPROVAL_GAP_CAP = 20;
  static final int TRIFECTA_POINTS = 25;
  static final int TRIFECTA_STRICT_POINTS = 10;
  static final int UNGATED_SINK_POINTS = 10;

  /** Deducted when the policy mode is OFF: nothing the scanner scores is enforced. */
  static final int GOVERNANCE_OFF_POINTS = 60;

  static final int NO_ASI_POINTS = 2;
  static final int NO_ASI_CAP = 10;

  private final GovernancePolicy policy;
  private final ToolMethodFinder finder;
  private final LethalTrifectaDetector detector = new LethalTrifectaDetector();

  /** Scores against {@link GovernancePolicy#defaults()}. */
  public RiskMapScanner() {
    this(GovernancePolicy.defaults());
  }

  public RiskMapScanner(GovernancePolicy policy) {
    this(policy, new ToolMethodFinder());
  }

  public RiskMapScanner(GovernancePolicy policy, ToolMethodFinder finder) {
    this.policy = Objects.requireNonNull(policy, "policy");
    this.finder = Objects.requireNonNull(finder, "finder");
  }

  public GovernancePolicy policy() {
    return policy;
  }

  public RiskMap scan(Collection<Class<?>> classes) {
    return scanTools(finder.find(classes));
  }

  /** Scores tools that were already located, for callers with their own discovery. */
  public RiskMap scanTools(List<FoundTool> found) {
    List<ScannedTool> tools = new ArrayList<>(found.size());
    List<String> unclassified = new ArrayList<>();
    List<ToolClassification> classifications = new ArrayList<>(found.size());
    int sideEffecting = 0;
    int gated = 0;
    int classifiedWithoutTags = 0;
    Map<AsiRisk, List<String>> exposure = new EnumMap<>(AsiRisk.class);
    for (AsiRisk risk : AsiRisk.values()) {
      exposure.put(risk, new ArrayList<>());
    }

    boolean off = policy.mode() == GovernanceMode.OFF;
    boolean strict = policy.mode() == GovernanceMode.STRICT;
    for (FoundTool tool : found) {
      ToolClassification classification =
          GovernedAnnotations.classify(tool.method(), tool.toolName())
              .orElseGet(() -> assumed(tool.toolName()));
      boolean classified = tool.governed();
      if (!classified) {
        unclassified.add(tool.toolName());
      } else if (classification.risks().isEmpty()) {
        classifiedWithoutTags++;
      }
      // OFF bypasses the pipeline entirely: nothing is gated. STRICT denies unclassified tools
      // outright: they neither run nor need approval, so they are left out of the coverage ratio.
      boolean denied = strict && !classified;
      boolean approvalGated = !off && !denied && approvalGated(classification);
      if (classification.sideEffecting() && !denied) {
        sideEffecting++;
        if (approvalGated) {
          gated++;
        }
      }
      for (AsiRisk risk : classification.risks()) {
        exposure.get(risk).add(tool.toolName());
      }
      for (AsiRisk risk : AsiControls.implied(classification)) {
        List<String> names = exposure.get(risk);
        if (!names.contains(tool.toolName())) {
          names.add(tool.toolName());
        }
      }
      classifications.add(classification);
      tools.add(new ScannedTool(tool, classification, classified, approvalGated));
    }

    LethalTrifectaDetector.Report trifecta = detector.analyse(classifications);
    int coverage = sideEffecting == 0 ? 100 : (int) Math.floor(100.0 * gated / sideEffecting);

    List<RiskMap.Deduction> deductions = new ArrayList<>();
    if (off) {
      deductions.add(
          new RiskMap.Deduction(
              "GOVERNANCE_OFF",
              GOVERNANCE_OFF_POINTS,
              "Policy mode OFF: GovernedTool bypasses the gate, approvals, guardrails, budgets"
                  + " and audit entirely; no tool is gated"));
    }
    if (!unclassified.isEmpty()) {
      int points = Math.min(UNCLASSIFIED_CAP, UNCLASSIFIED_POINTS * unclassified.size());
      String reason =
          strict
              ? unclassified.size()
                  + " tool(s) carry no @Governed and are DENIED by the STRICT policy gate (they"
                  + " never run): "
                  + String.join(", ", unclassified)
              : unclassified.size()
                  + " tool(s) carry no @Governed and run with the policy default ("
                  + policy.defaultRisk()
                  + ", side-effecting): "
                  + String.join(", ", unclassified);
      deductions.add(new RiskMap.Deduction("UNCLASSIFIED_TOOLS", points, reason));
    }
    if (coverage < 100) {
      int points = (int) Math.round(APPROVAL_GAP_CAP * (100 - coverage) / 100.0);
      if (points > 0) {
        deductions.add(
            new RiskMap.Deduction(
                "APPROVAL_GAP",
                points,
                (sideEffecting - gated)
                    + " of "
                    + sideEffecting
                    + " side-effecting tool(s) run without human approval (threshold "
                    + policy.approvalRequiredFrom()
                    + ")"));
      }
    }
    if (trifecta.holds()) {
      deductions.add(
          new RiskMap.Deduction(
              "LETHAL_TRIFECTA",
              strict ? TRIFECTA_STRICT_POINTS : TRIFECTA_POINTS,
              "Private readers, untrusted consumers and exfiltration sinks are all offered"
                  + (strict
                      ? "; STRICT mode forces approval on the sinks"
                      : "; in " + policy.mode() + " mode this is only audited")));
      List<String> ungatedSinks = new ArrayList<>();
      for (ScannedTool t : tools) {
        if (t.classification().exfiltrationSink() && !t.approvalGated()) {
          ungatedSinks.add(t.toolName());
        }
      }
      if (!ungatedSinks.isEmpty() && !strict) {
        deductions.add(
            new RiskMap.Deduction(
                "UNGATED_SINK",
                UNGATED_SINK_POINTS,
                "Exfiltration sink(s) without approval while the trifecta holds: "
                    + String.join(", ", ungatedSinks)));
      }
    }
    if (classifiedWithoutTags > 0) {
      deductions.add(
          new RiskMap.Deduction(
              "NO_ASI_TAGS",
              Math.min(NO_ASI_CAP, NO_ASI_POINTS * classifiedWithoutTags),
              classifiedWithoutTags
                  + " classified tool(s) declare no OWASP ASI risks; audit events for them carry"
                  + " no coverage tags"));
    }
    deductions.sort(Comparator.comparingInt(RiskMap.Deduction::points).reversed());

    int total = deductions.stream().mapToInt(RiskMap.Deduction::points).sum();
    int score = Math.max(0, 100 - total);

    Map<AsiRisk, List<String>> ordered = new LinkedHashMap<>(exposure);
    return new RiskMap(
        tools, unclassified, sideEffecting, gated, coverage, trifecta, ordered, score, deductions);
  }

  /** What the pipeline assumes for a tool it knows nothing about, in {@code WARN} mode. */
  ToolClassification assumed(String toolName) {
    return ToolClassification.builder(toolName)
        .risk(policy.defaultRisk())
        .sideEffecting(true)
        .build();
  }

  boolean approvalGated(ToolClassification c) {
    if (c.requiresApproval()) {
      return true;
    }
    return c.sideEffecting() && c.risk().atLeast(policy.approvalRequiredFrom());
  }
}
