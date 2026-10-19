package io.vegaduta.agent.api.journal;

import java.util.Optional;

/**
 * Remembers the result of side-effecting calls so a retried turn, a resumed job or a replayed
 * workflow step does not perform the effect twice.
 *
 * <p>Keys are built from the run, the step, the tool and its arguments (see {@code EffectKeys} in
 * core). The journal is consulted only for tools whose classification says a replay is safe.
 */
public interface EffectJournal {

  /** A previously recorded result for the key, if any. */
  Optional<String> lookup(String key);

  /**
   * Marks that a call with this key has started. Durable implementations use it to detect calls
   * that were interrupted mid-flight.
   */
  void begin(String key);

  /** Records the result of a completed call. */
  void record(String key, String result);

  /** A journal that remembers nothing; disables replay. */
  static EffectJournal disabled() {
    return new EffectJournal() {
      @Override
      public Optional<String> lookup(String key) {
        return Optional.empty();
      }

      @Override
      public void begin(String key) {}

      @Override
      public void record(String key, String result) {}
    };
  }
}
