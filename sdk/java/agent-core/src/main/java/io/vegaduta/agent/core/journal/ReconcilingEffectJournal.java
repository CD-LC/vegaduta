package io.vegaduta.agent.core.journal;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.audit.AuditEvent;
import io.vegaduta.agent.api.audit.AuditOutcome;
import io.vegaduta.agent.api.audit.AuditSink;
import io.vegaduta.agent.api.journal.EffectJournal;
import io.vegaduta.agent.api.risk.AsiRisk;
import io.vegaduta.agent.core.journal.InterruptedCallTracker.InterruptedCall;
import io.vegaduta.agent.core.text.Text;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An {@link EffectJournal} that reconciles interrupted calls before answering a lookup.
 *
 * <p>Wraps any journal. {@code begin} and {@code record} pass through and update the {@link
 * InterruptedCallTracker}. {@code lookup} first asks the delegate; when the delegate has nothing
 * and the key is interrupted (begun, never recorded) it asks the {@link EffectReconciler}:
 *
 * <ul>
 *   <li>{@link ReconcileOutcome.Completed}: the result is recorded in the delegate and returned, so
 *       the pipeline replays it instead of re-running the effect. Audit {@code effect.reconciled} /
 *       {@code SUCCESS}.
 *   <li>{@link ReconcileOutcome.NotPerformed}: the interrupted mark is cleared and the lookup is
 *       empty, so the pipeline runs the tool. Audit {@code effect.reconciled} / {@code SUCCESS}
 *       with detail {@code not-performed}.
 *   <li>{@link ReconcileOutcome.Unknown}: audit {@code effect.unresolved} / {@code WARNING}. Under
 *       {@link UnknownEffectPolicy#RETRY} the lookup is empty and the tool runs again; under {@link
 *       UnknownEffectPolicy#HOLD} the lookup throws {@link EffectUnresolvedException} so the
 *       pipeline surfaces an error rather than a possible duplicate.
 * </ul>
 *
 * <p>{@link #reconcileAll(Duration)} sweeps every interrupted key older than a threshold, for a
 * startup task or a scheduled job. Thread-safe; a probe for one key never blocks another key.
 *
 * <p>Interrupted-call state is tracked in memory. For calls interrupted by a crash of an earlier
 * process, the delegate journal is consulted when it implements {@link InterruptedCallSource} (the
 * JDBC journal does); otherwise seed the tracker from the durable journal (for example {@code
 * JdbcEffectJournal.findInterrupted}) at startup, through {@code Governance.reconcilingJournal()}.
 *
 * <p>{@link #resolve(String, String, RunContext)} is the operator override for a held key; it is
 * audited as {@value #ACTION_RESOLVED} with the actor and whether a result was supplied.
 */
@Experimental
public final class ReconcilingEffectJournal implements EffectJournal {

  private static final Logger log = LoggerFactory.getLogger(ReconcilingEffectJournal.class);

  /** Audit action type for a reconciled interrupted call. */
  public static final String ACTION_RECONCILED = "effect.reconciled";

  /** Audit action type for an interrupted call whose state could not be determined. */
  public static final String ACTION_UNRESOLVED = "effect.unresolved";

  /** Audit action type for an operator resolving an interrupted call by hand. */
  public static final String ACTION_RESOLVED = "effect.resolved";

  /** Audit target type of reconciliation events. */
  public static final String TARGET_TYPE = "effect";

  private record Description(String runId, String toolName, String argumentsJson) {}

  private final EffectJournal delegate;
  private final InterruptedCallTracker tracker;
  private final EffectReconciler reconciler;
  private final AuditSink audit;
  private final Clock clock;
  private final UnknownEffectPolicy unknownPolicy;
  private final Map<String, Description> descriptions = new ConcurrentHashMap<>();
  private final Map<String, KeyLock> locks = new ConcurrentHashMap<>();

  /** A per-key lock with a holder count, so an entry is only removed when nobody is waiting. */
  private static final class KeyLock {
    final ReentrantLock lock = new ReentrantLock();
    int holders;
  }

  public ReconcilingEffectJournal(
      EffectJournal delegate,
      InterruptedCallTracker tracker,
      EffectReconciler reconciler,
      AuditSink audit,
      Clock clock,
      UnknownEffectPolicy unknownPolicy) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.tracker = Objects.requireNonNull(tracker, "tracker");
    this.reconciler = Objects.requireNonNull(reconciler, "reconciler");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.unknownPolicy = Objects.requireNonNull(unknownPolicy, "unknownPolicy");
  }

  /** Builder with a fresh tracker, the system clock and {@link UnknownEffectPolicy#RETRY}. */
  public static Builder wrap(EffectJournal delegate) {
    return new Builder(delegate);
  }

  /** The tracker, for seeding at startup or for operator views. */
  public InterruptedCallTracker tracker() {
    return tracker;
  }

  /** The configured policy for {@link ReconcileOutcome.Unknown}. */
  public UnknownEffectPolicy unknownPolicy() {
    return unknownPolicy;
  }

  /**
   * Describes the tool call behind a key so the reconciler receives the tool name and arguments in
   * its {@link ReconcileContext}. Optional; hosts typically call this from a {@code
   * BEFORE_TOOL_CALL} hook, computing the key with {@link EffectKeys}.
   */
  public void describe(String key, RunContext context, String toolName, String argumentsJson) {
    Objects.requireNonNull(key, "key");
    descriptions.put(
        key,
        new Description(
            Objects.requireNonNull(context, "context").runId(),
            Objects.requireNonNull(toolName, "toolName"),
            Objects.requireNonNull(argumentsJson, "argumentsJson")));
  }

  @Override
  public Optional<String> lookup(String key) {
    Optional<String> recorded = delegate.lookup(key);
    if (recorded.isPresent()) {
      tracker.record(key);
      return recorded;
    }
    Optional<Instant> since = interruptedSince(key);
    if (since.isEmpty()) {
      return Optional.empty();
    }
    return reconcile(key, since.get(), true);
  }

  /** The tracker's answer, else the durable delegate's when it can tell. */
  private Optional<Instant> interruptedSince(String key) {
    Optional<Instant> since = tracker.since(key);
    if (since.isPresent()) {
      return since;
    }
    if (delegate instanceof InterruptedCallSource source) {
      since = source.interruptedSince(key);
      since.ifPresent(at -> tracker.seed(key, at));
    }
    return since;
  }

  @Override
  public void begin(String key) {
    tracker.begin(key, clock.instant());
    delegate.begin(key);
  }

  @Override
  public void record(String key, String result) {
    delegate.record(key, result);
    tracker.record(key);
    descriptions.remove(key);
  }

  /**
   * Marks an interrupted key as resolved by an operator: with a result it is recorded and later
   * lookups replay it; without one the key is cleared and the call may run again. Audited as
   * {@value #ACTION_RESOLVED} with {@code resolvedWith=result|none}; the actor is the context's
   * principal. Prefer this over the two-argument form so the override is attributable.
   */
  public void resolve(String key, @Nullable String result, RunContext by) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(by, "by");
    ReconcileContext ctx = contextFor(key, tracker.since(key).orElse(clock.instant()));
    if (result != null) {
      record(key, result);
    } else {
      tracker.record(key);
      descriptions.remove(key);
    }
    emit(
        ACTION_RESOLVED,
        ctx,
        AuditOutcome.SUCCESS,
        "resolved by " + by.principalOrAnonymous() + (result != null ? " with a result" : ""),
        by,
        Map.of("resolvedWith", result != null ? "result" : "none"));
  }

  /** {@link #resolve(String, String, RunContext)} with an anonymous actor. */
  public void resolve(String key, @Nullable String result) {
    resolve(key, result, RunContext.anonymous());
  }

  /**
   * Probes every interrupted call that began at least {@code olderThan} ago. Returns the outcome
   * per key; {@code UNKNOWN} keys stay interrupted and are never thrown from a sweep, whatever the
   * policy, because a sweep has no caller to protect.
   */
  public List<SweepResult> reconcileAll(Duration olderThan) {
    Objects.requireNonNull(olderThan, "olderThan");
    List<SweepResult> results = new ArrayList<>();
    for (InterruptedCall call : tracker.interrupted(olderThan, clock.instant())) {
      ReconcileOutcome outcome = reconcileOutcome(call.key(), call.since());
      results.add(new SweepResult(call.key(), call.since(), outcome));
    }
    return results;
  }

  /** One key's outcome from {@link #reconcileAll(Duration)}. */
  public record SweepResult(String key, Instant since, ReconcileOutcome outcome) {}

  private Optional<String> reconcile(String key, Instant since, boolean applyPolicy) {
    ReconcileOutcome outcome = reconcileOutcome(key, since);
    if (outcome instanceof ReconcileOutcome.Completed completed) {
      return Optional.of(completed.result());
    }
    if (outcome instanceof ReconcileOutcome.Unknown unknown
        && applyPolicy
        && unknownPolicy == UnknownEffectPolicy.HOLD) {
      throw new EffectUnresolvedException(key, since, unknown.reason());
    }
    return Optional.empty();
  }

  private ReconcileOutcome reconcileOutcome(String key, Instant since) {
    KeyLock keyLock =
        locks.compute(
            key,
            (k, existing) -> {
              KeyLock l = existing == null ? new KeyLock() : existing;
              l.holders++;
              return l;
            });
    keyLock.lock.lock();
    try {
      // Another thread may have finished the same reconciliation while we waited.
      Optional<String> recorded = delegate.lookup(key);
      if (recorded.isPresent()) {
        tracker.record(key);
        return ReconcileOutcome.completed(recorded.get());
      }
      if (!tracker.isInterrupted(key)) {
        return ReconcileOutcome.notPerformed();
      }
      ReconcileContext ctx = contextFor(key, since);
      ReconcileOutcome outcome = probe(key, ctx);
      apply(key, ctx, outcome);
      return outcome;
    } finally {
      keyLock.lock.unlock();
      // Only the last holder removes the entry, so a waiter never ends up on an orphaned lock.
      locks.computeIfPresent(key, (k, l) -> --l.holders == 0 ? null : l);
    }
  }

  private ReconcileOutcome probe(String key, ReconcileContext ctx) {
    try {
      ReconcileOutcome outcome = reconciler.probe(key, ctx);
      return outcome == null ? ReconcileOutcome.unknown("reconciler returned null") : outcome;
    } catch (RuntimeException e) {
      log.warn("Reconciler threw for effect {}", key, e);
      // Bounded: an HTTP client's message can embed a whole response body.
      return ReconcileOutcome.unknown(
          "reconciler threw "
              + e.getClass().getSimpleName()
              + (e.getMessage() == null ? "" : ": " + Text.bounded(e.getMessage(), Text.SHORT)));
    }
  }

  private void apply(String key, ReconcileContext ctx, ReconcileOutcome outcome) {
    if (outcome instanceof ReconcileOutcome.Completed completed) {
      delegate.record(key, completed.result());
      tracker.record(key);
      descriptions.remove(key);
      emit(ACTION_RECONCILED, ctx, AuditOutcome.SUCCESS, "completed");
    } else if (outcome instanceof ReconcileOutcome.NotPerformed) {
      tracker.record(key);
      descriptions.remove(key);
      emit(ACTION_RECONCILED, ctx, AuditOutcome.SUCCESS, "not-performed");
    } else if (outcome instanceof ReconcileOutcome.Unknown unknown) {
      emit(ACTION_UNRESOLVED, ctx, AuditOutcome.WARNING, unknown.reason());
    }
  }

  private ReconcileContext contextFor(String key, Instant since) {
    Description d = descriptions.get(key);
    if (d == null) {
      return ReconcileContext.of(key, since);
    }
    return new ReconcileContext(key, since, d.runId(), d.toolName(), d.argumentsJson());
  }

  private void emit(String action, ReconcileContext ctx, AuditOutcome outcome, String detail) {
    RunContext runContext =
        ctx.runId() == null ? RunContext.anonymous() : RunContext.ofRun(ctx.runId());
    emit(action, ctx, outcome, detail, runContext, Map.of());
  }

  private void emit(
      String action,
      ReconcileContext ctx,
      AuditOutcome outcome,
      String detail,
      RunContext runContext,
      Map<String, String> extra) {
    try {
      AuditEvent.Builder builder =
          AuditEvent.builder(action, TARGET_TYPE, ctx.key())
              .context(runContext)
              .at(clock.instant())
              .outcome(outcome)
              .detail(Text.bounded(detail, Text.CELL))
              .risk(AsiRisk.ASI08)
              .attribute("interruptedSince", ctx.since().toString())
              .attribute("unknownPolicy", unknownPolicy.name());
      if (ctx.toolName() != null) {
        builder.attribute("toolName", ctx.toolName());
      }
      extra.forEach(builder::attribute);
      audit.emit(builder.build());
    } catch (RuntimeException e) {
      log.warn("Audit sink threw for {} on effect {}", action, ctx.key(), e);
    }
  }

  /** Builds a {@link ReconcilingEffectJournal}. */
  public static final class Builder {
    private final EffectJournal delegate;
    private @Nullable InterruptedCallTracker tracker;
    private EffectReconciler reconciler = EffectReconciler.unknown();
    private AuditSink audit = AuditSink.discard();
    private Clock clock = Clock.systemUTC();
    private UnknownEffectPolicy unknownPolicy = UnknownEffectPolicy.RETRY;

    private Builder(EffectJournal delegate) {
      this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    public Builder tracker(InterruptedCallTracker value) {
      this.tracker = value;
      return this;
    }

    public Builder reconciler(EffectReconciler value) {
      this.reconciler = value;
      return this;
    }

    public Builder auditSink(AuditSink value) {
      this.audit = value;
      return this;
    }

    public Builder clock(Clock value) {
      this.clock = value;
      return this;
    }

    public Builder unknownPolicy(UnknownEffectPolicy value) {
      this.unknownPolicy = value;
      return this;
    }

    /** Shorthand for {@code unknownPolicy(HOLD)} when {@code true}, {@code RETRY} otherwise. */
    public Builder holdOnUnknown(boolean value) {
      return unknownPolicy(value ? UnknownEffectPolicy.HOLD : UnknownEffectPolicy.RETRY);
    }

    public ReconcilingEffectJournal build() {
      return new ReconcilingEffectJournal(
          delegate,
          tracker == null ? new InterruptedCallTracker() : tracker,
          reconciler,
          audit,
          clock,
          unknownPolicy);
    }
  }
}
