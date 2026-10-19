package io.vegaduta.agent.core.replay;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.gate.Verdict;

/** How a replayed verdict relates to the verdict that was actually applied. */
@Experimental
public enum ChangeKind {
  /** Same outcome under both policies. */
  UNCHANGED,
  /** Would now be denied; it was allowed or escalated before. */
  NEWLY_BLOCKED,
  /** Would now wait for a human; it was allowed before. */
  NEWLY_ESCALATED,
  /** Would now be treated more leniently than it actually was. */
  RELAXED;

  /** Severity order used to compare verdicts: allow, require approval, deny. */
  static int severity(Verdict.Outcome outcome) {
    return switch (outcome) {
      case ALLOW -> 0;
      case REQUIRE_APPROVAL -> 1;
      case DENY -> 2;
    };
  }

  static ChangeKind between(Verdict.Outcome actual, Verdict.Outcome replayed) {
    if (actual == replayed) {
      return UNCHANGED;
    }
    if (severity(replayed) < severity(actual)) {
      return RELAXED;
    }
    return replayed == Verdict.Outcome.DENY ? NEWLY_BLOCKED : NEWLY_ESCALATED;
  }
}
