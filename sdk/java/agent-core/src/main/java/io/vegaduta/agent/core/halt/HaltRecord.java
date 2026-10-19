package io.vegaduta.agent.core.halt;

import io.vegaduta.agent.api.Experimental;
import java.time.Instant;
import java.util.Objects;

/**
 * One engaged halt.
 *
 * @param id unique id, quoted in every verdict and audit event the halt produces
 * @param scope what the halt applies to
 * @param by who engaged it (an operator id, never the agent)
 * @param reason why, in the operator's words
 * @param at when it was engaged
 */
@Experimental
public record HaltRecord(String id, HaltScope scope, String by, String reason, Instant at) {

  public HaltRecord {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(scope, "scope");
    Objects.requireNonNull(by, "by");
    Objects.requireNonNull(reason, "reason");
    Objects.requireNonNull(at, "at");
  }
}
