package io.vegaduta.agent.core.journal;

import io.vegaduta.agent.api.Experimental;

/**
 * Asks the target system whether an interrupted side effect actually happened.
 *
 * <p>Legacy systems (ERP, core banking, mainframe gateways) rarely accept idempotency keys, so a
 * crash between {@code begin(key)} and {@code record(key, result)} leaves the SDK unable to tell
 * whether a retry would double-post. The host implements this interface with whatever the system
 * offers: a query by client reference, a statement lookup, a status endpoint.
 *
 * <p>Implementations are called from the tool pipeline and must be thread-safe. They should be
 * quick; a probe that blocks slows the retried call. Throwing is treated as {@link
 * ReconcileOutcome.Unknown}.
 */
@Experimental
@FunctionalInterface
public interface EffectReconciler {

  /** Determines the state of the effect behind {@code key}. */
  ReconcileOutcome probe(String key, ReconcileContext ctx);

  /** A reconciler that never knows; every interrupted call resolves to {@code UNKNOWN}. */
  static EffectReconciler unknown() {
    return (key, ctx) -> ReconcileOutcome.unknown("no reconciler configured");
  }
}
