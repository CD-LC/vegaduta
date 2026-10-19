package io.vegaduta.agent.core.provenance;

import io.vegaduta.agent.api.Experimental;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** A {@link ManifestPinStore} in a map; for tests and single-process hosts. */
@Experimental
public final class InMemoryManifestPinStore implements ManifestPinStore {

  private final Map<String, ToolManifest> pins = new ConcurrentHashMap<>();
  private final java.util.concurrent.atomic.AtomicLong version =
      new java.util.concurrent.atomic.AtomicLong();

  @Override
  public Optional<ToolManifest> load(String key) {
    return Optional.ofNullable(pins.get(Objects.requireNonNull(key, "key")));
  }

  @Override
  public void save(String key, ToolManifest manifest) {
    pins.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(manifest, "manifest"));
    version.incrementAndGet();
  }

  @Override
  public boolean remove(String key) {
    boolean removed = pins.remove(Objects.requireNonNull(key, "key")) != null;
    if (removed) {
      version.incrementAndGet();
    }
    return removed;
  }

  @Override
  public Optional<String> version(String key) {
    ToolManifest pin = pins.get(key);
    return Optional.of(version.get() + ":" + (pin == null ? "-" : pin.digest()));
  }

  public int size() {
    return pins.size();
  }
}
