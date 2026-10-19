package io.vegaduta.agent.core.journal;

import io.vegaduta.agent.api.Experimental;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers which effect keys have begun and not yet been recorded. A key that stays in that state
 * is an <em>interrupted call</em>, the same notion {@code JdbcEffectJournal.findInterrupted}
 * reports from its {@code STARTED} rows.
 *
 * <p>Thread-safe. This tracker lives in memory; a durable journal that survives a crash can seed it
 * with {@link #seed(String, Instant)} from its own interrupted-call query at startup, or implement
 * {@link InterruptedCallSource} so the reconciling journal asks it on demand.
 *
 * <p>Bounded: at most {@code maxSize} keys (default {@value #DEFAULT_MAX_SIZE}) are kept; when the
 * bound is reached the oldest begun key is dropped. A key dropped here can still be found through
 * an {@link InterruptedCallSource}.
 */
@Experimental
public final class InterruptedCallTracker {

  /** Default bound on tracked keys. */
  public static final int DEFAULT_MAX_SIZE = 100_000;

  /** An effect that began at {@code since} and was never recorded. */
  public record InterruptedCall(String key, Instant since) {
    public InterruptedCall {
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(since, "since");
    }
  }

  private final Map<String, Instant> begun = new ConcurrentHashMap<>();
  private final int maxSize;

  public InterruptedCallTracker() {
    this(DEFAULT_MAX_SIZE);
  }

  public InterruptedCallTracker(int maxSize) {
    if (maxSize <= 0) {
      throw new IllegalArgumentException("maxSize must be positive");
    }
    this.maxSize = maxSize;
  }

  /** Marks that a call with this key began at {@code at}. An earlier begin for the key is kept. */
  public void begin(String key, Instant at) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(at, "at");
    if (begun.putIfAbsent(key, at) == null && begun.size() > maxSize) {
      evictOldest();
    }
  }

  private void evictOldest() {
    Map.Entry<String, Instant> oldest = null;
    for (Map.Entry<String, Instant> e : begun.entrySet()) {
      if (oldest == null || e.getValue().isBefore(oldest.getValue())) {
        oldest = e;
      }
    }
    if (oldest != null) {
      begun.remove(oldest.getKey(), oldest.getValue());
    }
  }

  /** Seeds an interrupted call known from elsewhere, for example a durable journal's rows. */
  public void seed(String key, Instant since) {
    begin(key, since);
  }

  /** Clears the key: the call completed, was reconciled, or was resolved by hand. */
  public void record(String key) {
    begun.remove(key);
  }

  /** When the key began, if it is currently interrupted. */
  public Optional<Instant> since(String key) {
    return Optional.ofNullable(begun.get(key));
  }

  /** Whether the key has begun and not been recorded. */
  public boolean isInterrupted(String key) {
    return begun.containsKey(key);
  }

  /** Every key that began at least {@code olderThan} before {@code now}, oldest first. */
  public List<InterruptedCall> interrupted(Duration olderThan, Instant now) {
    Instant cutoff = now.minus(olderThan);
    List<InterruptedCall> calls = new ArrayList<>();
    for (Map.Entry<String, Instant> e : begun.entrySet()) {
      if (!e.getValue().isAfter(cutoff)) {
        calls.add(new InterruptedCall(e.getKey(), e.getValue()));
      }
    }
    calls.sort((a, b) -> a.since().compareTo(b.since()));
    return calls;
  }

  /** Number of keys currently begun and not recorded. */
  public int size() {
    return begun.size();
  }
}
