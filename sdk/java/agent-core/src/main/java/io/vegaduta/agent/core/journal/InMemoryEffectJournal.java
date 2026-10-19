package io.vegaduta.agent.core.journal;

import io.vegaduta.agent.api.journal.EffectJournal;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps results for a TTL in a bounded map. */
public final class InMemoryEffectJournal implements EffectJournal {

  private record Entry(String result, long expiresAtMillis) {}

  private final Map<String, Entry> entries = new ConcurrentHashMap<>();
  private final Duration ttl;
  private final int maxEntries;
  private final Clock clock;

  public InMemoryEffectJournal(Duration ttl) {
    this(ttl, 50_000, Clock.systemUTC());
  }

  public InMemoryEffectJournal(Duration ttl, int maxEntries, Clock clock) {
    this.ttl = ttl;
    this.maxEntries = maxEntries;
    this.clock = clock;
  }

  @Override
  public Optional<String> lookup(String key) {
    Entry entry = entries.get(key);
    if (entry == null) {
      return Optional.empty();
    }
    if (entry.expiresAtMillis() <= clock.millis()) {
      entries.remove(key, entry);
      return Optional.empty();
    }
    return Optional.of(entry.result());
  }

  @Override
  public void begin(String key) {
    // In-memory journals do not survive a crash, so there is no interrupted-call state to track.
  }

  @Override
  public void record(String key, String result) {
    if (entries.size() >= maxEntries) {
      purgeExpired();
      if (entries.size() >= maxEntries) {
        return;
      }
    }
    entries.put(key, new Entry(result, clock.millis() + ttl.toMillis()));
  }

  public int size() {
    return entries.size();
  }

  private void purgeExpired() {
    long now = clock.millis();
    entries.entrySet().removeIf(e -> e.getValue().expiresAtMillis() <= now);
  }
}
