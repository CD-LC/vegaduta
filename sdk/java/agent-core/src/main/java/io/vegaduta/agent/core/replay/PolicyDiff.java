package io.vegaduta.agent.core.replay;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.core.GovernancePolicy;
import io.vegaduta.agent.core.text.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Which knobs differ between two {@link GovernancePolicy} records.
 *
 * @param changes one entry per knob that differs, in declaration order of the policy record
 */
@Experimental
public record PolicyDiff(List<Change> changes) {

  public PolicyDiff {
    changes = List.copyOf(changes);
  }

  /**
   * One differing knob.
   *
   * @param knob the policy field name
   * @param from the value in the current policy
   * @param to the value in the candidate
   */
  public record Change(String knob, String from, String to) {
    public Change {
      Objects.requireNonNull(knob, "knob");
      Objects.requireNonNull(from, "from");
      Objects.requireNonNull(to, "to");
    }
  }

  /** Compares {@code current} with {@code candidate}. */
  public static PolicyDiff of(GovernancePolicy current, GovernancePolicy candidate) {
    Objects.requireNonNull(current, "current");
    Objects.requireNonNull(candidate, "candidate");
    List<Change> changes = new ArrayList<>();
    add(changes, "mode", current.mode(), candidate.mode());
    add(changes, "defaultRisk", current.defaultRisk(), candidate.defaultRisk());
    add(
        changes,
        "approvalRequiredFrom",
        current.approvalRequiredFrom(),
        candidate.approvalRequiredFrom());
    add(changes, "approvalTtl", current.approvalTtl(), candidate.approvalTtl());
    add(changes, "fourEyes", current.fourEyes(), candidate.fourEyes());
    add(
        changes,
        "maxToolCallsPerRun",
        current.maxToolCallsPerRun(),
        candidate.maxToolCallsPerRun());
    add(changes, "maxTokensPerRun", current.maxTokensPerRun(), candidate.maxTokensPerRun());
    if (current.maxUsdPerRun().compareTo(candidate.maxUsdPerRun()) != 0) {
      changes.add(
          new Change(
              "maxUsdPerRun",
              current.maxUsdPerRun().toPlainString(),
              candidate.maxUsdPerRun().toPlainString()));
    }
    if (current.estimatedUsdPerToolCall().compareTo(candidate.estimatedUsdPerToolCall()) != 0) {
      changes.add(
          new Change(
              "estimatedUsdPerToolCall",
              current.estimatedUsdPerToolCall().toPlainString(),
              candidate.estimatedUsdPerToolCall().toPlainString()));
    }
    add(changes, "effectJournalTtl", current.effectJournalTtl(), candidate.effectJournalTtl());
    add(
        changes,
        "argumentPreviewChars",
        current.argumentPreviewChars(),
        candidate.argumentPreviewChars());
    return new PolicyDiff(changes);
  }

  private static void add(List<Change> changes, String knob, Object from, Object to) {
    if (!Objects.equals(from, to)) {
      changes.add(new Change(knob, String.valueOf(from), String.valueOf(to)));
    }
  }

  public boolean isEmpty() {
    return changes.isEmpty();
  }

  /**
   * Which knobs matter to the dispatch gate: {@code mode}, {@code defaultRisk} and {@code
   * approvalRequiredFrom}. A diff that touches none of them cannot change a replay verdict.
   */
  public boolean affectsGate() {
    for (Change c : changes) {
      switch (c.knob()) {
        case "mode", "defaultRisk", "approvalRequiredFrom" -> {
          return true;
        }
        default -> {}
      }
    }
    return false;
  }

  /** One line per change: {@code knob: from -> to}; "no changes" when the policies are equal. */
  public String toText() {
    if (changes.isEmpty()) {
      return "no changes";
    }
    StringBuilder sb = new StringBuilder();
    for (Change c : changes) {
      if (sb.length() > 0) {
        sb.append('\n');
      }
      sb.append(c.knob()).append(": ").append(c.from()).append(" -> ").append(c.to());
    }
    return sb.toString();
  }

  /** A Markdown table of the changes. */
  public String toMarkdown() {
    if (changes.isEmpty()) {
      return "No policy changes.\n";
    }
    StringBuilder sb = new StringBuilder("| Knob | Current | Candidate |\n|---|---|---|\n");
    for (Change c : changes) {
      sb.append("| ")
          .append(Text.cell(c.knob()))
          .append(" | ")
          .append(Text.cell(c.from()))
          .append(" | ")
          .append(Text.cell(c.to()))
          .append(" |\n");
    }
    return sb.toString();
  }

  /** All knobs of one policy on a single line, for report headers. */
  public static String describe(GovernancePolicy p) {
    return "mode="
        + p.mode()
        + ", defaultRisk="
        + p.defaultRisk()
        + ", approvalRequiredFrom="
        + p.approvalRequiredFrom()
        + ", approvalTtl="
        + p.approvalTtl()
        + ", fourEyes="
        + p.fourEyes()
        + ", maxToolCallsPerRun="
        + p.maxToolCallsPerRun()
        + ", maxTokensPerRun="
        + p.maxTokensPerRun()
        + ", maxUsdPerRun="
        + p.maxUsdPerRun().toPlainString()
        + ", estimatedUsdPerToolCall="
        + p.estimatedUsdPerToolCall().toPlainString()
        + ", effectJournalTtl="
        + p.effectJournalTtl()
        + ", argumentPreviewChars="
        + p.argumentPreviewChars();
  }
}
