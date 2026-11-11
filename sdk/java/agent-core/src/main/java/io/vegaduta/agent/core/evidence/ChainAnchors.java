package io.vegaduta.agent.core.evidence;

import io.vegaduta.agent.api.Experimental;
import org.jspecify.annotations.Nullable;

/**
 * Values recorded <em>outside</em> the audit log that pin where the chain starts and ends. A log
 * cannot show by itself that its first or last events were deleted: without anchors {@link
 * HashChainedAuditSink#verify} trusts the first event to start the chain and accepts whatever the
 * last event is. Store {@link EvidenceBundle#headHash()} and {@link EvidenceBundle#lastSeq()} (and
 * {@link EvidenceBundle#firstSeq()} / {@link EvidenceBundle#firstPrev()} for the start) somewhere
 * the log writer cannot rewrite, and pass them back here.
 *
 * <p>Every anchor is optional. A supplied anchor that does not match, including when the log has no
 * chained events at all, makes verification fail with a message naming it. The names match the
 * Python SDK's {@code expected_first_seq}, {@code expected_first_prev}, {@code expected_last_seq}
 * and {@code expected_head_hash} keyword arguments.
 *
 * @param firstSeq the {@code chain.seq} the first verified event must carry
 * @param firstPrev the {@code chain.prev} the first verified event must carry ({@link
 *     HashChainedAuditSink#GENESIS} for a chain verified from its start)
 * @param lastSeq the {@code chain.seq} the last verified event must carry
 * @param headHash the {@code chain.hash} the last verified event must carry
 */
@Experimental
public record ChainAnchors(
    @Nullable Long firstSeq,
    @Nullable String firstPrev,
    @Nullable Long lastSeq,
    @Nullable String headHash) {

  private static final ChainAnchors NONE = new ChainAnchors(null, null, null, null);

  /** No anchors: the first event is trusted to start the chain. */
  public static ChainAnchors none() {
    return NONE;
  }

  public ChainAnchors withFirstSeq(long value) {
    return new ChainAnchors(value, firstPrev, lastSeq, headHash);
  }

  public ChainAnchors withFirstPrev(String value) {
    return new ChainAnchors(firstSeq, value, lastSeq, headHash);
  }

  public ChainAnchors withLastSeq(long value) {
    return new ChainAnchors(firstSeq, firstPrev, value, headHash);
  }

  public ChainAnchors withHeadHash(String value) {
    return new ChainAnchors(firstSeq, firstPrev, lastSeq, value);
  }

  /** True when at least one anchor is set. */
  public boolean any() {
    return firstSeq != null || firstPrev != null || lastSeq != null || headHash != null;
  }
}
