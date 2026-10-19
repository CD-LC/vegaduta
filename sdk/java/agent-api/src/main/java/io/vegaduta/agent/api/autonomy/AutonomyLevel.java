package io.vegaduta.agent.api.autonomy;

import io.vegaduta.agent.api.Experimental;

/**
 * How much an agent may do without a human in the loop, ordered from least to most autonomous.
 *
 * <p>Uniform governance across every agent fails in practice: a research assistant and a payments
 * agent need different leashes, and an agent that misbehaves must be reined in now, not at the next
 * release. The level is therefore a run-time property of the agent (see the registry in {@code
 * agent-core}) rather than a compile-time annotation, and every tool call is checked against it.
 *
 * <p>The ordinal order is meaningful: {@link #atLeast(AutonomyLevel)} compares it.
 */
@Experimental
public enum AutonomyLevel {

  /**
   * Observe only. The agent may call read-only tools and return its findings to the requesting
   * user. It may not change anything outside the process.
   */
  L1_OBSERVE("Observe", "Read-only tools; outputs go to the requesting user."),

  /**
   * Recommend. The agent may draft or suggest side effects (a reply, a change, a payment) but never
   * executes them itself: every side-effecting call is turned into an approval request a human
   * decides.
   */
  L2_RECOMMEND("Recommend", "May draft or suggest side effects; a human executes or approves."),

  /**
   * Act within bounds. The agent executes side effects of low and medium risk inside an explicit
   * tool allow-list. High-risk effects still need an approval before they run; critical effects are
   * out of reach unless the tool is itself approval-gated.
   */
  L3_ACT_BOUNDED(
      "Act (bounded)",
      "Executes low/medium-risk side effects within the allow-list; high risk needs approval."),

  /**
   * Act autonomously. The agent executes within the guardrails, budgets and classification policy;
   * humans review the audit trail afterwards instead of approving beforehand. Tools marked {@code
   * requiresApproval} are still approval-gated.
   */
  L4_ACT_AUTONOMOUS(
      "Act (autonomous)", "Executes within guardrails; humans review after the fact.");

  private final String title;
  private final String summary;

  AutonomyLevel(String title, String summary) {
    this.title = title;
    this.summary = summary;
  }

  /** A short human-readable name, for reports and consoles. */
  public String title() {
    return title;
  }

  /** One sentence describing what the level permits. */
  public String summary() {
    return summary;
  }

  /** True when this level is the same as or more autonomous than {@code other}. */
  public boolean atLeast(AutonomyLevel other) {
    return compareTo(other) >= 0;
  }

  /** True when this level is strictly less autonomous than {@code other}. */
  public boolean below(AutonomyLevel other) {
    return compareTo(other) < 0;
  }

  /** The level whose name is {@code name} (case-insensitive), e.g. {@code "l3_act_bounded"}. */
  public static AutonomyLevel parse(String name) {
    return valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
  }
}
