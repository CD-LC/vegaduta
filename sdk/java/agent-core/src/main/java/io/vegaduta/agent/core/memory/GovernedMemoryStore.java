package io.vegaduta.agent.core.memory;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.audit.AuditEvent;
import io.vegaduta.agent.api.audit.AuditOutcome;
import io.vegaduta.agent.api.audit.AuditSink;
import io.vegaduta.agent.api.guardrail.ContentGuardrail;
import io.vegaduta.agent.api.guardrail.GuardrailAction;
import io.vegaduta.agent.api.guardrail.GuardrailResult;
import io.vegaduta.agent.api.guardrail.GuardrailStage;
import io.vegaduta.agent.api.guardrail.GuardrailViolation;
import io.vegaduta.agent.api.risk.AsiRisk;
import io.vegaduta.agent.core.guardrail.GuardrailChain;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Treats every memory write as a privileged event (OWASP ASI06).
 *
 * <ul>
 *   <li><b>Screening.</b> The value runs through a {@link ContentGuardrail} at {@link
 *       GuardrailStage#TOOL_OUTPUT}. Secrets are redacted before storage. Any {@code BLOCK}
 *       violation, and any violation whose code starts with {@code injection.}, refuses the write
 *       with a {@link MemoryWriteBlockedException}: an instruction-override phrase has no business
 *       in long-term memory, even redacted.
 *   <li><b>Quarantine.</b> Entries whose {@link Provenance#trusted()} is false ({@link
 *       Provenance#TOOL_OUTPUT_UNTRUSTED}, {@link Provenance#AGENT_PEER}) are stored but hidden
 *       from {@link #get(String, RunContext)} and {@link #list(RunContext)} until {@link
 *       #promote(String, RunContext)} is called.
 *   <li><b>Write cap.</b> Each run may write (or delete) at most {@code writeCapPerRun} entries
 *       (default {@value #DEFAULT_WRITE_CAP}); the next write throws {@link
 *       MemoryWriteCapExceededException}. The slot is reserved atomically, so parallel writes in
 *       one run cannot overshoot the cap. A runaway or hijacked agent cannot flood shared memory.
 *       Per-run counters expire after {@code writeCapTtl} (default 24 hours).
 *   <li><b>Deletes are governed too.</b> A delete counts against the cap, and a trusted entry
 *       written by another agent cannot be deleted by a run that names a different agent.
 *   <li><b>Promotion is identity-bound.</b> {@link #promote(MemoryEntry, RunContext)} releases
 *       exactly the entry the reviewer inspected; {@link #promote(String, RunContext)} releases the
 *       value present at the time of the call with an atomic replace, and both record the value's
 *       digest in the audit event.
 *   <li><b>Audit.</b> Every write, promote and refusal emits an {@link AuditEvent} with target type
 *       {@code memory} and risk {@link AsiRisk#ASI06}. Reading quarantined content written by a
 *       different agent, or by or for an agent whose identity is unknown, is audited as a {@link
 *       AuditOutcome#WARNING}.
 * </ul>
 *
 * <p>The underlying {@link MemoryStore} is never exposed; callers that bypass this class bypass
 * governance, so keep the delegate private to the host.
 */
@Experimental
public final class GovernedMemoryStore {

  public static final int DEFAULT_WRITE_CAP = 100;

  public static final String ACTION_WRITE = "memory.write";
  public static final String ACTION_PROMOTE = "memory.promote";
  public static final String ACTION_BLOCKED = "memory.blocked";
  public static final String ACTION_READ = "memory.read";
  public static final String ACTION_DELETE = "memory.delete";
  public static final String TARGET_TYPE = "memory";

  /** Default lifetime of a run's write counter. */
  public static final Duration DEFAULT_WRITE_CAP_TTL = Duration.ofHours(24);

  private static final Logger log = LoggerFactory.getLogger(GovernedMemoryStore.class);
  private static final String INJECTION_PREFIX = "injection.";

  private final MemoryStore delegate;
  private final ContentGuardrail guardrail;
  private final AuditSink audit;
  private final Clock clock;
  private final int writeCapPerRun;
  private final Duration writeCapTtl;
  private final Map<String, RunWrites> writesPerRun = new ConcurrentHashMap<>();
  private final AtomicLong lastPurgeMillis = new AtomicLong();

  /** A run's write counter and when it was first used, for expiry. */
  private record RunWrites(AtomicInteger count, Instant firstWriteAt) {}

  private GovernedMemoryStore(Builder b) {
    this.delegate = b.delegate;
    this.guardrail = b.guardrail;
    this.audit = b.audit;
    this.clock = b.clock;
    this.writeCapPerRun = b.writeCapPerRun;
    this.writeCapTtl = b.writeCapTtl;
  }

  /** Wraps {@code delegate} with the built-in guardrails, the given audit sink and defaults. */
  public static GovernedMemoryStore of(MemoryStore delegate, AuditSink audit) {
    return builder(delegate).auditSink(audit).build();
  }

  public static Builder builder(MemoryStore delegate) {
    return new Builder(delegate);
  }

  /**
   * Screens and stores {@code value} under {@code key}.
   *
   * @param key the memory key
   * @param value the value to store; secrets in it are redacted
   * @param provenance where the value came from
   * @param context the writing run; {@link RunContext#agentId()} becomes the writer
   * @return the entry as stored (possibly redacted, possibly quarantined)
   * @throws MemoryWriteBlockedException when the value matches an injection or blocking rule
   * @throws MemoryWriteCapExceededException when the run has used up its write cap
   */
  public MemoryEntry put(String key, String value, Provenance provenance, RunContext context) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(value, "value");
    Objects.requireNonNull(provenance, "provenance");
    Objects.requireNonNull(context, "context");

    AtomicInteger counter = reserveSlot(key, context, provenance);
    GuardrailResult screened;
    List<GuardrailViolation> blocking;
    try {
      screened = guardrail.evaluate(value, GuardrailStage.TOOL_OUTPUT, context);
      blocking = blockingViolations(screened);
    } catch (RuntimeException e) {
      counter.decrementAndGet();
      throw e;
    }
    if (!blocking.isEmpty()) {
      // A blocked write does not consume the cap.
      counter.decrementAndGet();
      emit(
          ACTION_BLOCKED,
          key,
          AuditOutcome.DENIED,
          "guardrail: " + MemoryWriteBlockedException.codes(blocking),
          context,
          provenance,
          Map.of("reason", "guardrail", "violations", MemoryWriteBlockedException.codes(blocking)));
      throw new MemoryWriteBlockedException(key, blocking);
    }

    boolean quarantined = !provenance.trusted();
    MemoryEntry entry =
        new MemoryEntry(
            key,
            screened.content(),
            provenance,
            context.agentId(),
            context.runId(),
            clock.instant(),
            quarantined);
    delegate.put(entry);

    Map<String, String> attrs = new LinkedHashMap<>();
    attrs.put("quarantined", Boolean.toString(quarantined));
    attrs.put("valueDigest", digest(entry.value()));
    if (!screened.clean()) {
      attrs.put("redacted", MemoryWriteBlockedException.codes(screened.violations()));
    }
    emit(
        ACTION_WRITE,
        key,
        quarantined ? AuditOutcome.PENDING_APPROVAL : AuditOutcome.SUCCESS,
        quarantined ? "stored in quarantine" : null,
        context,
        provenance,
        attrs);
    return entry;
  }

  /** The visible (non-quarantined) entry under {@code key}. */
  public Optional<MemoryEntry> get(String key, RunContext reader) {
    return get(key, reader, false);
  }

  /**
   * The entry under {@code key}. With {@code includeQuarantined} a quarantined entry is returned
   * too; when its writer is a different agent than the reader, the read is audited as a warning.
   */
  public Optional<MemoryEntry> get(String key, RunContext reader, boolean includeQuarantined) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(reader, "reader");
    Optional<MemoryEntry> found = delegate.get(key);
    if (found.isEmpty()) {
      return found;
    }
    MemoryEntry entry = found.get();
    if (!entry.quarantined()) {
      return found;
    }
    if (!includeQuarantined) {
      return Optional.empty();
    }
    auditQuarantinedRead(entry, reader);
    return found;
  }

  /** Every visible (non-quarantined) entry. */
  public List<MemoryEntry> list(RunContext reader) {
    return list(reader, false);
  }

  /** Every entry; quarantined ones only with {@code includeQuarantined} (audited per entry). */
  public List<MemoryEntry> list(RunContext reader, boolean includeQuarantined) {
    Objects.requireNonNull(reader, "reader");
    List<MemoryEntry> out = new ArrayList<>();
    for (MemoryEntry entry : delegate.list()) {
      if (!entry.quarantined()) {
        out.add(entry);
      } else if (includeQuarantined) {
        auditQuarantinedRead(entry, reader);
        out.add(entry);
      }
    }
    return out;
  }

  /**
   * Releases the entry under {@code key} from quarantine: whatever value is stored at the time of
   * the call, replaced atomically. Prefer {@link #promote(MemoryEntry, RunContext)} after a human
   * review, so a value written between the review and the promotion is not released unseen.
   *
   * @param key the memory key
   * @param by who promotes; {@link RunContext#principalOrAnonymous()} is the audited actor
   * @return the promoted entry, or empty when there is no entry under {@code key} or it changed
   *     concurrently
   */
  public Optional<MemoryEntry> promote(String key, RunContext by) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(by, "by");
    Optional<MemoryEntry> found = delegate.get(key);
    if (found.isEmpty()) {
      return found;
    }
    MemoryEntry entry = found.get();
    if (!entry.quarantined()) {
      return found;
    }
    return promote(entry, by);
  }

  /**
   * Releases exactly {@code expected} from quarantine. Refuses (empty result, audited as {@link
   * AuditOutcome#DENIED}) when the stored entry is no longer equal to {@code expected}: a different
   * value, provenance, writer or run means the reviewer did not inspect what is there now.
   *
   * @param expected the entry the reviewer inspected, as returned by {@link #get(String,
   *     RunContext, boolean)}
   * @param by who promotes; {@link RunContext#principalOrAnonymous()} is the audited actor
   * @return the promoted entry, or empty when it was not promoted
   */
  public Optional<MemoryEntry> promote(MemoryEntry expected, RunContext by) {
    Objects.requireNonNull(expected, "expected");
    Objects.requireNonNull(by, "by");
    if (!expected.quarantined()) {
      return Optional.of(expected);
    }
    MemoryEntry promoted = expected.promoted();
    Map<String, String> attrs = new LinkedHashMap<>();
    attrs.put("writerAgentId", String.valueOf(expected.writerAgentId()));
    attrs.put("valueDigest", digest(expected.value()));
    if (!delegate.replace(expected, promoted)) {
      attrs.put("reason", "stale");
      emit(
          ACTION_PROMOTE,
          expected.key(),
          AuditOutcome.DENIED,
          "promotion refused: entry changed since it was reviewed",
          by,
          expected.provenance(),
          attrs);
      return Optional.empty();
    }
    emit(
        ACTION_PROMOTE,
        expected.key(),
        AuditOutcome.APPROVED,
        "promoted by " + by.principalOrAnonymous(),
        by,
        expected.provenance(),
        attrs);
    return Optional.of(promoted);
  }

  /** Convenience for {@link #promote(String, RunContext)} with a bare principal. */
  public Optional<MemoryEntry> promote(String key, String by) {
    return promote(key, RunContext.builder().principal(by).build());
  }

  /**
   * Removes the entry under {@code key}. A delete is a write: it counts against the run's cap and
   * is audited as {@value #ACTION_DELETE} with the deleted entry's provenance and writer. A trusted
   * (non-quarantined) entry written by a named agent can only be deleted by a run of that agent or
   * by a run that names no agent at all (an operator); a different agent's attempt is refused and
   * audited as {@link AuditOutcome#DENIED}.
   *
   * @return true when an entry was removed
   * @throws MemoryWriteCapExceededException when the run has used up its write cap
   */
  public boolean delete(String key, RunContext context) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(context, "context");
    Optional<MemoryEntry> found = delegate.get(key);
    if (found.isEmpty()) {
      return false;
    }
    MemoryEntry entry = found.get();
    Map<String, String> attrs = new LinkedHashMap<>();
    attrs.put("writerAgentId", String.valueOf(entry.writerAgentId()));
    attrs.put("quarantined", Boolean.toString(entry.quarantined()));
    attrs.put("valueDigest", digest(entry.value()));
    String deleter = context.agentId();
    if (!entry.quarantined()
        && entry.writerAgentId() != null
        && deleter != null
        && !deleter.equals(entry.writerAgentId())) {
      attrs.put("reason", "foreign_trusted_entry");
      emit(
          ACTION_DELETE,
          key,
          AuditOutcome.DENIED,
          "delete refused: trusted entry written by " + entry.writerAgentId(),
          context,
          entry.provenance(),
          attrs);
      return false;
    }
    AtomicInteger counter = reserveSlot(key, context, entry.provenance());
    boolean removed = delegate.delete(key);
    if (!removed) {
      counter.decrementAndGet();
      return false;
    }
    emit(ACTION_DELETE, key, AuditOutcome.SUCCESS, "deleted", context, entry.provenance(), attrs);
    return true;
  }

  /** Writes performed so far by {@code runId}. */
  public int writesBy(String runId) {
    RunWrites n = writesPerRun.get(runId);
    return n == null ? 0 : n.count().get();
  }

  /**
   * Reserves one write slot for the run atomically, or audits and throws when the cap is reached.
   * The caller releases the slot (decrement) when the write does not happen.
   */
  private AtomicInteger reserveSlot(String key, RunContext context, Provenance provenance) {
    purgeExpiredCounters();
    Instant now = clock.instant();
    AtomicInteger counter =
        writesPerRun
            .computeIfAbsent(context.runId(), r -> new RunWrites(new AtomicInteger(), now))
            .count();
    if (counter.incrementAndGet() > writeCapPerRun) {
      counter.decrementAndGet();
      emit(
          ACTION_BLOCKED,
          key,
          AuditOutcome.DENIED,
          "write cap of " + writeCapPerRun + " reached",
          context,
          provenance,
          Map.of("reason", "write_cap"));
      throw new MemoryWriteCapExceededException(context.runId(), writeCapPerRun);
    }
    return counter;
  }

  /** Drops counters older than the TTL, at most once a minute. */
  private void purgeExpiredCounters() {
    long nowMillis = clock.millis();
    long last = lastPurgeMillis.get();
    if (nowMillis - last < 60_000 || !lastPurgeMillis.compareAndSet(last, nowMillis)) {
      return;
    }
    Instant cutoff = clock.instant().minus(writeCapTtl);
    writesPerRun.entrySet().removeIf(e -> e.getValue().firstWriteAt().isBefore(cutoff));
  }

  static String digest(String value) {
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder("sha256:");
      for (byte b : hash) {
        sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  public int writeCapPerRun() {
    return writeCapPerRun;
  }

  private static List<GuardrailViolation> blockingViolations(GuardrailResult result) {
    List<GuardrailViolation> blocking = new ArrayList<>();
    for (GuardrailViolation v : result.violations()) {
      if (v.action() == GuardrailAction.BLOCK || v.code().startsWith(INJECTION_PREFIX)) {
        blocking.add(v);
      }
    }
    return blocking;
  }

  private void auditQuarantinedRead(MemoryEntry entry, RunContext reader) {
    // An unknown identity on either side cannot be proven to be the same agent: audit it.
    if (entry.writerAgentId() != null
        && reader.agentId() != null
        && entry.writerAgentId().equals(reader.agentId())) {
      return;
    }
    emit(
        ACTION_READ,
        entry.key(),
        AuditOutcome.WARNING,
        "quarantined entry written by " + entry.writerAgentId() + " read by " + reader.agentId(),
        reader,
        entry.provenance(),
        Map.of("writerAgentId", String.valueOf(entry.writerAgentId())));
  }

  private void emit(
      String actionType,
      String key,
      AuditOutcome outcome,
      @Nullable String detail,
      RunContext context,
      @Nullable Provenance provenance,
      Map<String, String> attributes) {
    AuditEvent.Builder b =
        AuditEvent.builder(actionType, TARGET_TYPE, key)
            .context(context)
            .outcome(outcome)
            .detail(detail)
            .at(clock.instant())
            .risk(AsiRisk.ASI06);
    if (provenance != null) {
      b.attribute("provenance", provenance.name());
    }
    attributes.forEach(b::attribute);
    try {
      audit.emit(b.build());
    } catch (RuntimeException e) {
      log.warn("Audit sink failed for {} on memory key '{}': {}", actionType, key, e.toString());
    }
  }

  /** Builds a {@link GovernedMemoryStore}. */
  public static final class Builder {
    private final MemoryStore delegate;
    private ContentGuardrail guardrail = GuardrailChain.builtIn();
    private AuditSink audit = AuditSink.discard();
    private Clock clock = Clock.systemUTC();
    private int writeCapPerRun = DEFAULT_WRITE_CAP;
    private Duration writeCapTtl = DEFAULT_WRITE_CAP_TTL;

    private Builder(MemoryStore delegate) {
      this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /** The guardrail run over every value at {@link GuardrailStage#TOOL_OUTPUT}. */
    public Builder guardrail(ContentGuardrail value) {
      this.guardrail = Objects.requireNonNull(value, "guardrail");
      return this;
    }

    public Builder auditSink(AuditSink value) {
      this.audit = Objects.requireNonNull(value, "auditSink");
      return this;
    }

    public Builder clock(Clock value) {
      this.clock = Objects.requireNonNull(value, "clock");
      return this;
    }

    /** Maximum writes per run id; must be positive. */
    public Builder writeCapPerRun(int value) {
      if (value <= 0) {
        throw new IllegalArgumentException("writeCapPerRun must be positive: " + value);
      }
      this.writeCapPerRun = value;
      return this;
    }

    /** How long a run's write counter is kept after its first write; must be positive. */
    public Builder writeCapTtl(Duration value) {
      Objects.requireNonNull(value, "writeCapTtl");
      if (value.isZero() || value.isNegative()) {
        throw new IllegalArgumentException("writeCapTtl must be positive");
      }
      this.writeCapTtl = value;
      return this;
    }

    public GovernedMemoryStore build() {
      return new GovernedMemoryStore(this);
    }
  }
}
