package io.vegaduta.agent.core.autonomy;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.autonomy.AutonomyLevel;
import io.vegaduta.agent.api.autonomy.AutonomyRequirement;
import io.vegaduta.agent.api.gate.Verdict;
import io.vegaduta.agent.api.risk.ToolClassification;
import io.vegaduta.agent.core.text.Text;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Renders the autonomy posture as markdown for the compliance pack: the levels, which agents hold
 * which level, and which tools each level can reach (outright, with approval, or not at all) given
 * a set of classifications. Deterministic: same inputs, same text.
 */
@Experimental
public final class AutonomyPolicyReport {

  private final AutonomyRegistry registry;
  private final List<ToolClassification> classifications;

  public AutonomyPolicyReport(
      AutonomyRegistry registry, Collection<ToolClassification> classifications) {
    this.registry = Objects.requireNonNull(registry, "registry");
    List<ToolClassification> sorted = new ArrayList<>(classifications);
    sorted.sort(Comparator.comparing(ToolClassification::toolName));
    this.classifications = List.copyOf(sorted);
  }

  /** The report as markdown. */
  public String markdown() {
    StringBuilder sb = new StringBuilder();
    sb.append("# Agent autonomy policy\n\n");
    sb.append("Default level for unregistered agents: `")
        .append(registry.defaultLevel().name())
        .append("`\n\n");

    sb.append("## Levels\n\n");
    sb.append("| Level | Title | Permits |\n|---|---|---|\n");
    for (AutonomyLevel level : AutonomyLevel.values()) {
      row(sb, "`" + level.name() + "`", level.title(), level.summary());
    }
    sb.append('\n');

    sb.append("## Agents\n\n");
    Map<String, AutonomyLevel> agents = registry.snapshot();
    if (agents.isEmpty()) {
      sb.append("_No agents registered; all run at the default level._\n\n");
    } else {
      sb.append("| Agent | Level |\n|---|---|\n");
      for (Map.Entry<String, AutonomyLevel> e : agents.entrySet()) {
        row(sb, Text.code(e.getKey()), "`" + e.getValue().name() + "`");
      }
      sb.append('\n');
    }

    sb.append("## Tool requirements\n\n");
    if (classifications.isEmpty()) {
      sb.append("_No tool classifications supplied._\n\n");
    } else {
      sb.append("| Tool | Risk | Side-effecting | Minimum level | Approval |\n");
      sb.append("|---|---|---|---|---|\n");
      for (ToolClassification c : classifications) {
        AutonomyRequirement r = AutonomyRequirement.forClassification(c);
        row(
            sb,
            Text.code(c.toolName()),
            c.risk().name(),
            c.sideEffecting() ? "yes" : "no",
            "`" + r.minimumLevel().name() + "`",
            r.approvalRequired() ? "always" : "per level");
      }
      sb.append('\n');
    }

    sb.append("## Reach per level\n\n");
    for (AutonomyLevel level : AutonomyLevel.values()) {
      sb.append("### `").append(level.name()).append("`\n\n");
      if (classifications.isEmpty()) {
        sb.append("_No tool classifications supplied._\n\n");
        continue;
      }
      List<String> allowed = new ArrayList<>();
      List<String> approval = new ArrayList<>();
      List<String> denied = new ArrayList<>();
      for (ToolClassification c : classifications) {
        Verdict v = AutonomyGate.evaluate(level, c);
        String entry = Text.code(c.toolName());
        switch (v.outcome()) {
          case ALLOW -> allowed.add(entry);
          case REQUIRE_APPROVAL -> approval.add(entry + " (" + v.reason() + ")");
          case DENY -> denied.add(entry + " (" + v.reason() + ")");
        }
      }
      list(sb, "Executes", allowed);
      list(sb, "Needs approval", approval);
      list(sb, "Out of reach", denied);
      sb.append('\n');
    }
    return sb.toString();
  }

  /**
   * Cells are either SDK constants or already passed through {@link Text#code}; this escapes any
   * remaining pipe or line break so no value can add a column or a row.
   */
  private static void row(StringBuilder sb, String... cells) {
    sb.append('|');
    for (String cell : cells) {
      sb.append(' ').append(flat(cell).replace("|", "\\|")).append(" |");
    }
    sb.append('\n');
  }

  private static void list(StringBuilder sb, String label, List<String> items) {
    sb.append("- **").append(label).append(":** ");
    sb.append(items.isEmpty() ? "none" : flat(String.join(", ", items)));
    sb.append('\n');
  }

  private static String flat(String s) {
    return s.replace("\r", " ").replace("\n", " ");
  }
}
