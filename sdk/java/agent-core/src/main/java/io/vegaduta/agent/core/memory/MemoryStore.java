package io.vegaduta.agent.core.memory;

import io.vegaduta.agent.api.Experimental;
import java.util.List;
import java.util.Optional;

/**
 * Raw storage for {@link MemoryEntry} records. Implementations store what they are given and apply
 * no policy; wrap one in a {@link GovernedMemoryStore} to get screening, quarantine, write caps and
 * audit. Implementations must be safe for concurrent use.
 */
@Experimental
public interface MemoryStore {

  /** The entry under {@code key}, quarantined or not. */
  Optional<MemoryEntry> get(String key);

  /** Stores or replaces the entry under its own key. */
  void put(MemoryEntry entry);

  /** Removes the entry; returns true when one was there. */
  boolean delete(String key);

  /** Every entry, quarantined or not, in no particular order. */
  List<MemoryEntry> list();

  /**
   * Replaces the entry under {@code expected.key()} with {@code updated} only when the stored entry
   * still equals {@code expected}. The default is a non-atomic get-then-put; stores that can
   * compare-and-set (a concurrent map, a row with a version column) must override it so that a
   * promotion cannot release a value the reviewer never saw.
   *
   * @return true when the replacement happened
   */
  default boolean replace(MemoryEntry expected, MemoryEntry updated) {
    Optional<MemoryEntry> current = get(expected.key());
    if (current.isEmpty() || !current.get().equals(expected)) {
      return false;
    }
    put(updated);
    return true;
  }
}
