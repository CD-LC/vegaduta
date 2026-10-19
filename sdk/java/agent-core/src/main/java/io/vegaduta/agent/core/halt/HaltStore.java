package io.vegaduta.agent.core.halt;

import io.vegaduta.agent.api.Experimental;
import java.util.Collection;
import java.util.Optional;

/**
 * Where active halts live. The default, {@link InMemoryHaltStore}, is process-local: in a
 * deployment with more than one replica (or an API node plus workers) a halt engaged on one process
 * stops only that process. Plug a shared backend (a database table, a replicated cache) through
 * this interface so {@link HaltSwitch#isHalted} sees the same halts everywhere.
 *
 * <p>Implementations must be thread-safe. {@link HaltSwitch} reads {@link #active()} on every gate
 * check, so a remote store should cache with a short TTL.
 */
@Experimental
public interface HaltStore {

  /** Adds or replaces the halt with {@code record.id()}. */
  void put(HaltRecord record);

  /** Removes the halt, returning it when it was present. */
  Optional<HaltRecord> remove(String id);

  /** The halt with this id, if active. */
  Optional<HaltRecord> find(String id);

  /** Every active halt, in no particular order. */
  Collection<HaltRecord> active();
}
