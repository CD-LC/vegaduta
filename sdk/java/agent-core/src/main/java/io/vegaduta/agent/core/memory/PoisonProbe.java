package io.vegaduta.agent.core.memory;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.audit.AuditEvent;
import io.vegaduta.agent.api.audit.AuditOutcome;
import io.vegaduta.agent.api.audit.AuditSink;
import io.vegaduta.agent.api.risk.AsiRisk;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A canary against delayed-trigger memory poisoning.
 *
 * <p>Some poisoning does not act at once: an attacker plants an entry, or rewrites an existing one,
 * that only steers behaviour many runs later. Scanning all of memory for that is expensive and
 * heuristic. A probe is cheap and exact: {@link #seed(RunContext)} writes an entry under a reserved
 * key with a random marker that no legitimate process has a reason to touch; {@link
 * #check(RunContext)} later asserts that the entry is still there, still carries the marker, the
 * same provenance and the same quarantine state, and was not rewritten by another run. Any
 * deviation means something is writing to memory it should not, and the surrounding writes deserve
 * a look in the audit log.
 *
 * <p>Seed with a quarantined provenance ({@link Provenance#AGENT_PEER}) to also detect rogue
 * promotion: a promoted canary means an actor is releasing quarantined entries without review.
 *
 * <p>A clean probe is not proof that memory is clean; it is a tripwire, not a scanner.
 */
@Experimental
public final class PoisonProbe {

  private static final Logger log = LoggerFactory.getLogger(PoisonProbe.class);

  public static final String ACTION_PROBE = "memory.probe";
  public static final String DEFAULT_KEY = "__vegaduta_canary__";

  private final GovernedMemoryStore store;
  private final String key;
  private final Provenance provenance;
  private final String marker;
  private final AuditSink audit;
  private final Clock clock;
  private volatile @Nullable MemoryEntry seeded;

  /** A probe under {@link #DEFAULT_KEY} with {@link Provenance#SYSTEM} and a random marker. */
  public PoisonProbe(GovernedMemoryStore store) {
    this(
        store,
        DEFAULT_KEY,
        Provenance.SYSTEM,
        randomMarker(),
        AuditSink.discard(),
        Clock.systemUTC());
  }

  /**
   * @param store the governed store to seed and check
   * @param key the canary key; pick one no tool or agent would use
   * @param provenance the provenance to seed with; untrusted ones also detect rogue promotion
   * @param marker the canary value; random and unguessable in production
   * @param audit receives a {@code memory.probe} event per check
   * @param clock for report timestamps
   */
  public PoisonProbe(
      GovernedMemoryStore store,
      String key,
      Provenance provenance,
      String marker,
      AuditSink audit,
      Clock clock) {
    this.store = Objects.requireNonNull(store, "store");
    this.key = Objects.requireNonNull(key, "key");
    this.provenance = Objects.requireNonNull(provenance, "provenance");
    this.marker = Objects.requireNonNull(marker, "marker");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.clock = Objects.requireNonNull(clock, "clock");
    if (marker.isBlank()) {
      throw new IllegalArgumentException("marker must not be blank");
    }
  }

  /** Writes the canary and remembers exactly what was stored. */
  public MemoryEntry seed(RunContext context) {
    MemoryEntry entry = store.put(key, marker, provenance, context);
    this.seeded = entry;
    return entry;
  }

  /**
   * Compares the stored canary with what {@link #seed(RunContext)} wrote.
   *
   * @throws IllegalStateException when the probe was never seeded
   */
  public ProbeReport check(RunContext context) {
    MemoryEntry expected = seeded;
    if (expected == null) {
      throw new IllegalStateException("PoisonProbe.check called before seed");
    }
    List<String> findings = new ArrayList<>();
    Optional<MemoryEntry> found = store.get(key, context, true);
    MemoryEntry observed = found.orElse(null);
    if (observed == null) {
      findings.add("canary missing");
    } else {
      if (!observed.value().equals(expected.value())) {
        findings.add("value overwritten");
      }
      if (observed.provenance() != expected.provenance()) {
        findings.add(
            "provenance changed from " + expected.provenance() + " to " + observed.provenance());
      }
      if (observed.quarantined() != expected.quarantined()) {
        findings.add(expected.quarantined() ? "canary promoted" : "canary quarantined");
      }
      if (!observed.runId().equals(expected.runId())) {
        findings.add("rewritten by run " + observed.runId());
      }
      if (!Objects.equals(observed.writerAgentId(), expected.writerAgentId())) {
        findings.add("rewritten by agent " + observed.writerAgentId());
      }
    }
    ProbeReport report =
        new ProbeReport(key, clock.instant(), findings.isEmpty(), findings, observed);
    try {
      audit.emit(
          AuditEvent.builder(ACTION_PROBE, GovernedMemoryStore.TARGET_TYPE, key)
              .context(context)
              .at(report.checkedAt())
              .outcome(report.intact() ? AuditOutcome.SUCCESS : AuditOutcome.FAILURE)
              .detail(report.intact() ? "canary intact" : String.join("; ", findings))
              .risk(AsiRisk.ASI06)
              .build());
    } catch (RuntimeException e) {
      // The report is the point; a failing sink must not swallow a detected tamper.
      log.warn("Audit sink failed for poison probe on key '{}': {}", key, e.toString());
    }
    return report;
  }

  public String key() {
    return key;
  }

  private static String randomMarker() {
    return "canary:" + UUID.randomUUID();
  }
}
