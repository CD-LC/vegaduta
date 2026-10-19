package io.vegaduta.agent.core.evidence;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.audit.AuditEvent;
import io.vegaduta.agent.api.audit.AuditOutcome;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Answers the questions an insurer's AI questionnaire asks, per tenant and agent: what the agent
 * can reach and how dangerous it is, how much of the side-effecting activity a human approved, how
 * often it acted on its own, how often it was stopped (denied, over budget), how many duplicate
 * charges the effect journal prevented, and how often output had to be redacted. Plain language,
 * one block per scope, with the numbers behind each sentence.
 */
@Experimental
public final class UnderwritingReport implements EvidenceExporter {

  private final EvidenceBundle bundle;

  public UnderwritingReport(EvidenceBundle bundle) {
    this.bundle = Objects.requireNonNull(bundle, "bundle");
  }

  /** The figures for one {@code tenant/agent} scope. */
  @Experimental
  public record ScopeSummary(
      String scope,
      Map<String, String> toolsWithRisk,
      long toolCalls,
      long sideEffectingCalls,
      long sideEffectingHumanApproved,
      long autonomousExecutions,
      long denied,
      long budgetExceeded,
      long replayed,
      long redactions,
      long failures) {

    public double humanApprovedShare() {
      return Markdown.ratio(sideEffectingHumanApproved, sideEffectingCalls);
    }
  }

  /** Summaries by scope, in first-seen order. */
  public Map<String, ScopeSummary> summaries() {
    Map<String, ScopeSummary> out = new LinkedHashMap<>();
    for (Map.Entry<String, List<AuditEvent>> en : bundle.byScope().entrySet()) {
      out.put(en.getKey(), summarise(en.getKey(), en.getValue()));
    }
    return out;
  }

  private ScopeSummary summarise(String scope, List<AuditEvent> events) {
    Map<String, String> tools = new LinkedHashMap<>();
    long calls = 0;
    long sideEffecting = 0;
    long sideEffectingApproved = 0;
    long autonomous = 0;
    long denied = 0;
    long budget = 0;
    long replayed = 0;
    long redactions = 0;
    long failures = 0;
    TreeSet<String> names = new TreeSet<>();
    for (AuditEvent e : events) {
      if (e.outcome() == AuditOutcome.BUDGET_EXCEEDED) {
        budget++;
      }
      if (!e.actionType().equals(EvidenceBundle.TOOL_CALL)) {
        continue;
      }
      names.add(e.targetId());
      calls++;
      boolean approved = e.attributes().containsKey(EvidenceBundle.APPROVAL_ID_ATTRIBUTE);
      switch (e.outcome()) {
        case SUCCESS -> {
          if (bundle.sideEffecting(e)) {
            sideEffecting++;
            if (approved) {
              sideEffectingApproved++;
            }
          }
          if (!approved) {
            autonomous++;
          }
        }
        case DENIED -> denied++;
        case REPLAYED -> replayed++;
        case FAILURE -> failures++;
        case WARNING -> {
          String detail = e.detail();
          if (detail != null && detail.startsWith(EvidenceBundle.REDACTION_DETAIL_PREFIX)) {
            redactions++;
          }
        }
        default -> {}
      }
    }
    for (String name : names) {
      tools.put(name, bundle.riskLabelOf(name));
    }
    return new ScopeSummary(
        scope,
        tools,
        calls,
        sideEffecting,
        sideEffectingApproved,
        autonomous,
        denied,
        budget,
        replayed,
        redactions,
        failures);
  }

  @Override
  public String toMarkdown() {
    StringBuilder sb = new StringBuilder();
    sb.append("# Agent governance summary for underwriting\n\n");
    sb.append("Period: ").append(bundle.periodText()).append("  \n");
    sb.append("Generated: ").append(bundle.generatedAt()).append("  \n");
    sb.append("Records tamper-evident: ").append(tamperEvidence()).append("\n\n");
    Map<String, ScopeSummary> summaries = summaries();
    if (summaries.isEmpty()) {
      sb.append("No agent activity was recorded in this period.\n");
      return sb.toString();
    }
    for (ScopeSummary s : summaries.values()) {
      sb.append("## Tenant/agent ").append(s.scope()).append("\n\n");
      sb.append("**Permissions.** ");
      if (s.toolsWithRisk().isEmpty()) {
        sb.append("The agent called no tools in this period.\n\n");
      } else {
        sb.append("The agent can reach ")
            .append(Markdown.plural(s.toolsWithRisk().size(), "tool"))
            .append(":\n\n");
        List<List<String>> rows = new ArrayList<>();
        s.toolsWithRisk().forEach((tool, risk) -> rows.add(List.of(tool, risk)));
        Markdown.table(sb, List.of("Tool", "Risk label"), rows);
      }
      sb.append("**Human review of consequential actions.** ");
      if (s.sideEffectingCalls() == 0) {
        sb.append("No side-effecting action completed in this period.");
      } else {
        sb.append(Markdown.percent(s.sideEffectingHumanApproved(), s.sideEffectingCalls()))
            .append(" of completed side-effecting actions (")
            .append(s.sideEffectingHumanApproved())
            .append(" of ")
            .append(s.sideEffectingCalls())
            .append(") were approved by a named human before they ran.");
      }
      sb.append("\n\n**Autonomous execution.** The agent completed ")
          .append(Markdown.plural(s.autonomousExecutions(), "action"))
          .append(" without a human decision; ")
          .append(Markdown.plural(s.toolCalls(), "tool call"))
          .append(" were attempted in total.\n\n");
      sb.append("**Controls that stopped the agent.** ")
          .append(Markdown.plural(s.denied(), "call"))
          .append(" denied by policy, gate, guardrail or hook; ")
          .append(Markdown.plural(s.budgetExceeded(), "stop"))
          .append(" by budget limits; ")
          .append(Markdown.plural(s.failures(), "tool failure"))
          .append(".\n\n");
      sb.append("**Duplicate-charge prevention.** ")
          .append(Markdown.plural(s.replayed(), "repeated call"))
          .append(" served from the effect journal instead of re-executing.\n\n");
      sb.append("**Data protection.** ")
          .append(Markdown.plural(s.redactions(), "tool output"))
          .append(" redacted by guardrails before reaching the model.\n\n");
    }
    return sb.toString();
  }

  /**
   * States exactly what was checked. A hash-only chain can be re-chained by anyone holding the log,
   * so "verified" is only claimed when signatures were checked with a verifier.
   */
  String tamperEvidence() {
    if (!bundle.chained()) {
      return "no";
    }
    ChainVerification cv = bundle.chainVerification();
    if (!cv.ok()) {
      return "CHAIN BROKEN (" + cv.message() + ")";
    }
    String algorithm = chainAlgorithm();
    String coverage =
        bundle.unchainedEvents() > 0
            ? "; " + Markdown.plural(bundle.unchainedEvents(), "event") + " not covered"
            : "";
    if (cv.signed()) {
      return "yes, hash-linked and signatures verified (" + algorithm + ")" + coverage;
    }
    return "hash-linked only, signatures NOT verified ("
        + (algorithm.equals("none") ? "unsigned chain" : algorithm + " present, no verifier given")
        + ")"
        + coverage;
  }

  private String chainAlgorithm() {
    return bundle.events().stream()
        .map(e -> e.attributes().get(HashChainedAuditSink.ALG))
        .filter(a -> a != null)
        .findFirst()
        .orElse("none");
  }

  @Override
  public String toJsonLines() {
    StringBuilder sb = new StringBuilder();
    ChainVerification cv = bundle.chainVerification();
    sb.append(
            EvidenceJson.object()
                .put("section", "underwriting.bundle")
                .put("period", bundle.periodText())
                .put("generatedAt", bundle.generatedAt().toString())
                .put("tamperEvident", bundle.chained())
                .put("chainOk", cv.ok())
                .put("chainSigned", cv.signed())
                .put("chainAlgorithm", chainAlgorithm())
                .put("unchainedEvents", bundle.unchainedEvents())
                .put("tamperEvidence", tamperEvidence()))
        .append('\n');
    for (ScopeSummary s : summaries().values()) {
      sb.append(
              EvidenceJson.object()
                  .put("section", "underwriting.scope")
                  .put("scope", s.scope())
                  .put("tools", EvidenceJson.object().putAll(s.toolsWithRisk()))
                  .put("toolCalls", s.toolCalls())
                  .put("sideEffectingCalls", s.sideEffectingCalls())
                  .put("sideEffectingHumanApproved", s.sideEffectingHumanApproved())
                  .put("humanApprovedShare", s.humanApprovedShare())
                  .put("autonomousExecutions", s.autonomousExecutions())
                  .put("denied", s.denied())
                  .put("budgetExceeded", s.budgetExceeded())
                  .put("replayed", s.replayed())
                  .put("redactions", s.redactions())
                  .put("failures", s.failures()))
          .append('\n');
    }
    return sb.toString();
  }
}
