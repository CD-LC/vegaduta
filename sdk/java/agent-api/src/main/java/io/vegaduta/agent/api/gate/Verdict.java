package io.vegaduta.agent.api.gate;

import java.util.Objects;

/**
 * What the gate decided for one call.
 *
 * @param outcome allow, deny or escalate to a human
 * @param reason a short machine-readable reason (for example {@code "unclassified"}, {@code
 *     "risk_threshold"}, {@code "deny_list"}); empty for {@link Outcome#ALLOW}
 */
public record Verdict(Outcome outcome, String reason) {

  public enum Outcome {
    ALLOW,
    DENY,
    REQUIRE_APPROVAL
  }

  private static final Verdict ALLOW = new Verdict(Outcome.ALLOW, "");

  public Verdict {
    Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(reason, "reason");
  }

  public static Verdict allow() {
    return ALLOW;
  }

  public static Verdict deny(String reason) {
    return new Verdict(Outcome.DENY, reason);
  }

  public static Verdict requireApproval(String reason) {
    return new Verdict(Outcome.REQUIRE_APPROVAL, reason);
  }

  public boolean allowed() {
    return outcome == Outcome.ALLOW;
  }
}
