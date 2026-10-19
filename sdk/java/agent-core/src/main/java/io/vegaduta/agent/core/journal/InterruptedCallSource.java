package io.vegaduta.agent.core.journal;

import io.vegaduta.agent.api.Experimental;
import java.time.Instant;
import java.util.Optional;

/**
 * Answers whether an effect key was begun and never recorded according to a durable journal, so a
 * {@link ReconcilingEffectJournal} can reconcile calls interrupted by a crash of an earlier
 * process, whose in-memory {@link InterruptedCallTracker} is empty. Durable {@code EffectJournal}
 * implementations (such as the JDBC journal) implement it alongside the journal interface; the
 * reconciling journal consults it whenever its tracker has no entry for a key.
 */
@Experimental
public interface InterruptedCallSource {

  /** When the key was begun, if it is currently begun-and-unrecorded in durable storage. */
  Optional<Instant> interruptedSince(String key);
}
