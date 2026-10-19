package io.vegaduta.agent.core.evidence;

import io.vegaduta.agent.api.Experimental;
import java.util.Objects;

/**
 * Result of {@link HashChainedAuditSink#verify}.
 *
 * @param ok true when every link, sequence number and (if asked) signature checked out
 * @param firstBrokenSeq the {@code chain.seq} of the first event that failed, or {@code -1} when
 *     the chain is intact
 * @param eventsChecked how many events were examined
 * @param message a one-line human-readable summary
 * @param signed true when a {@link Verifier} checked every signature; an unsigned (hash-only) chain
 *     can be re-chained by anyone holding the log, so {@code ok && !signed} is weaker evidence
 */
@Experimental
public record ChainVerification(
    boolean ok, long firstBrokenSeq, int eventsChecked, String message, boolean signed) {

  public ChainVerification {
    Objects.requireNonNull(message, "message");
  }

  /** An unsigned verification result. */
  public ChainVerification(boolean ok, long firstBrokenSeq, int eventsChecked, String message) {
    this(ok, firstBrokenSeq, eventsChecked, message, false);
  }

  static ChainVerification intact(int eventsChecked, boolean signed) {
    return new ChainVerification(
        true,
        -1,
        eventsChecked,
        eventsChecked
            + " events verified, chain intact"
            + (signed ? ", signatures valid" : ", no signature check"),
        signed);
  }

  static ChainVerification broken(long seq, int eventsChecked, String reason) {
    return new ChainVerification(
        false, seq, eventsChecked, "chain broken at seq " + seq + ": " + reason, false);
  }
}
