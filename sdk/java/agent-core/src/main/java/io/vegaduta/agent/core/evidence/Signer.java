package io.vegaduta.agent.core.evidence;

import io.vegaduta.agent.api.Experimental;

/**
 * Signs chain hashes so that a verifier who holds the matching key can prove the log was written by
 * the holder of the signing key, not only that it is internally consistent.
 *
 * <p>Implementations must be thread-safe; {@link HashChainedAuditSink} calls them under its own
 * lock but other callers may not.
 */
@Experimental
public interface Signer {

  /** Short algorithm name recorded next to the signature, for example {@code HmacSHA256}. */
  String algorithm();

  /** The raw signature over {@code data}. */
  byte[] sign(byte[] data);

  /** A verifier for signatures this signer produces. */
  Verifier verifier();
}
