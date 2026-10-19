package io.vegaduta.agent.core.evidence;

import io.vegaduta.agent.api.Experimental;

/** Checks a signature produced by a {@link Signer}. Implementations must be thread-safe. */
@Experimental
public interface Verifier {

  /** Short algorithm name, matching {@link Signer#algorithm()}. */
  String algorithm();

  /** True when {@code signature} is a valid signature over {@code data}. Never throws. */
  boolean verify(byte[] data, byte[] signature);
}
