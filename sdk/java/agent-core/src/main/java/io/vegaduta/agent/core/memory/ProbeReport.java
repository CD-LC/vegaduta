package io.vegaduta.agent.core.memory;

import io.vegaduta.agent.api.Experimental;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The result of one {@link PoisonProbe#check(io.vegaduta.agent.api.RunContext)}.
 *
 * @param key the canary key
 * @param checkedAt when the check ran
 * @param intact true when the canary is exactly as seeded
 * @param findings one line per deviation; empty when intact
 * @param observed the entry as found, or {@code null} when it is gone
 */
@Experimental
public record ProbeReport(
    String key,
    Instant checkedAt,
    boolean intact,
    List<String> findings,
    @Nullable MemoryEntry observed) {

  public ProbeReport {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(checkedAt, "checkedAt");
    findings = List.copyOf(findings);
  }

  /** The opposite of {@link #intact()}, for readable assertions. */
  public boolean tampered() {
    return !intact;
  }
}
