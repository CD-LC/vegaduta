package io.vegaduta.agent.core.memory;

import io.vegaduta.agent.api.Experimental;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** A {@link MemoryStore} backed by a concurrent map. For tests, demos and single-process agents. */
@Experimental
public final class InMemoryMemoryStore implements MemoryStore {

  private final Map<String, MemoryEntry> entries = new ConcurrentHashMap<>();

  @Override
  public Optional<MemoryEntry> get(String key) {
    return Optional.ofNullable(entries.get(key));
  }

  @Override
  public void put(MemoryEntry entry) {
    entries.put(entry.key(), entry);
  }

  @Override
  public boolean delete(String key) {
    return entries.remove(key) != null;
  }

  @Override
  public boolean replace(MemoryEntry expected, MemoryEntry updated) {
    if (!expected.key().equals(updated.key())) {
      throw new IllegalArgumentException("replace must keep the key");
    }
    return entries.replace(expected.key(), expected, updated);
  }

  @Override
  public List<MemoryEntry> list() {
    return new ArrayList<>(entries.values());
  }

  public int size() {
    return entries.size();
  }
}
