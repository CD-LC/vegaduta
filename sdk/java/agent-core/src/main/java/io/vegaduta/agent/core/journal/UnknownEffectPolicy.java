package io.vegaduta.agent.core.journal;

import io.vegaduta.agent.api.Experimental;

/**
 * What a {@link ReconcilingEffectJournal} does when the reconciler answers {@link
 * ReconcileOutcome.Unknown} for an interrupted call.
 */
@Experimental
public enum UnknownEffectPolicy {

  /**
   * Return an empty lookup so the pipeline runs the tool again, after emitting a {@code
   * effect.unresolved} WARNING audit event. Appropriate when a duplicate is cheaper than a missed
   * effect.
   */
  RETRY,

  /**
   * Throw {@link EffectUnresolvedException} from {@code lookup} so the pipeline surfaces an error
   * instead of re-running the effect. Appropriate for payments and postings where a duplicate is
   * the worse outcome; an operator resolves the key by hand or through {@link
   * ReconcilingEffectJournal#resolve}.
   */
  HOLD
}
