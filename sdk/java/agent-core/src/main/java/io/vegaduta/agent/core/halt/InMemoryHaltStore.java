package io.vegaduta.agent.core.halt;

import io.vegaduta.agent.api.Experimental;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The default {@link HaltStore}: a concurrent map in this process. Halts engaged here are invisible
 * to other replicas; see {@link HaltStore} for when to replace it.
 */
@Experimental
public final class InMemoryHaltStore implements HaltStore {

  private final Map<String, HaltRecord> halts = new ConcurrentHashMap<>();

  @Override
  public void put(HaltRecord record) {
    halts.put(Objects.requireNonNull(record, "record").id(), record);
  }

  @Override
  public Optional<HaltRecord> remove(String id) {
    return Optional.ofNullable(halts.remove(Objects.requireNonNull(id, "id")));
  }

  @Override
  public Optional<HaltRecord> find(String id) {
    return Optional.ofNullable(halts.get(Objects.requireNonNull(id, "id")));
  }

  @Override
  public Collection<HaltRecord> active() {
    return List.copyOf(halts.values());
  }
}
