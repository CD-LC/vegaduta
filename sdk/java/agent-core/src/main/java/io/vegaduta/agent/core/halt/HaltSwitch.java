package io.vegaduta.agent.core.halt;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.audit.AuditEvent;
import io.vegaduta.agent.api.audit.AuditOutcome;
import io.vegaduta.agent.api.audit.AuditSink;
import io.vegaduta.agent.api.risk.AsiRisk;
import io.vegaduta.agent.core.text.Text;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The kill switch. Overseers {@link #engage(HaltScope, String, String) engage} a halt for a scope
 * and {@link #release(String, String) release} it by id; {@link HaltGate} asks {@link
 * #isHalted(RunContext)} before every tool call.
 *
 * <p>Thread-safe: halts live in a {@link HaltStore} (by default an in-memory concurrent map) and
 * every operation is lock-free. Engaging and releasing each emit one {@link AuditEvent} ({@code
 * halt.engaged} / {@code halt.released}, target type {@code halt}) tagged ASI08 (cascading
 * failures) and ASI09 (human-agent trust), so the audit trail shows who stopped what, when, and who
 * let it resume.
 *
 * <p><b>The default store is process-local.</b> With several replicas, a halt engaged on one
 * replica stops only that replica; give every replica a shared {@link HaltStore} so the fleet stops
 * together. The operator-supplied {@code reason} and {@code by} are bounded to {@value Text#SHORT}
 * characters with control characters removed, because they are copied into every denial the model
 * sees and into every audit event for the duration of the halt.
 *
 * <p>Build one per application and share it with the {@link HaltGate} and the {@link HaltDrill}.
 */
@Experimental
public final class HaltSwitch {

  public static final String ACTION_ENGAGED = "halt.engaged";
  public static final String ACTION_RELEASED = "halt.released";
  public static final String TARGET_TYPE = "halt";

  private static final Logger log = LoggerFactory.getLogger(HaltSwitch.class);

  /** Most specific first; among equals the earliest engaged, then by id for determinism. */
  private static final Comparator<HaltRecord> PRECEDENCE =
      Comparator.comparingInt((HaltRecord r) -> r.scope().specificity())
          .reversed()
          .thenComparing(HaltRecord::at)
          .thenComparing(HaltRecord::id);

  private final HaltStore halts;
  private final AuditSink audit;
  private final Clock clock;

  /** A switch that audits to {@code audit} and timestamps with the system UTC clock. */
  public HaltSwitch(AuditSink audit) {
    this(audit, Clock.systemUTC());
  }

  public HaltSwitch(AuditSink audit, Clock clock) {
    this(new InMemoryHaltStore(), audit, clock);
  }

  /** A switch over a (possibly shared) store. */
  public HaltSwitch(HaltStore store, AuditSink audit, Clock clock) {
    this.halts = Objects.requireNonNull(store, "store");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** The store halts are kept in. */
  public HaltStore store() {
    return halts;
  }

  /**
   * Engages a halt. Takes effect for the next gate check on every thread; calls already past the
   * gate finish.
   *
   * @param scope what to stop
   * @param by who is stopping it
   * @param reason why, for the audit trail and the denial the model sees
   * @return the record, whose {@link HaltRecord#id()} is needed to release it
   */
  public HaltRecord engage(HaltScope scope, String by, String reason) {
    Objects.requireNonNull(scope, "scope");
    Objects.requireNonNull(by, "by");
    Objects.requireNonNull(reason, "reason");
    by = Text.shortText(by);
    reason = Text.shortText(reason);
    HaltRecord record =
        new HaltRecord(UUID.randomUUID().toString(), scope, by, reason, clock.instant());
    halts.put(record);
    log.warn("Halt {} engaged by {} on {}: {}", record.id(), by, scope, reason);
    emit(
        AuditEvent.builder(ACTION_ENGAGED, TARGET_TYPE, record.id())
            .actor(by)
            .outcome(AuditOutcome.SUCCESS)
            .detail(reason)
            .context(contextFor(record))
            .at(record.at())
            .attribute("scope", scope.toString())
            .attribute("scopeKind", scope.kind().name()));
    return record;
  }

  /**
   * Releases a halt.
   *
   * @param id the halt to release
   * @param by who is releasing it
   * @return the released record, or empty when no such halt was active (nothing is audited then)
   */
  public Optional<HaltRecord> release(String id, String by) {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(by, "by");
    by = Text.shortText(by);
    HaltRecord record = halts.remove(id).orElse(null);
    if (record == null) {
      return Optional.empty();
    }
    log.warn("Halt {} on {} released by {}", id, record.scope(), by);
    emit(
        AuditEvent.builder(ACTION_RELEASED, TARGET_TYPE, id)
            .actor(by)
            .outcome(AuditOutcome.SUCCESS)
            .detail(record.reason())
            .context(contextFor(record))
            .at(clock.instant())
            .attribute("scope", record.scope().toString())
            .attribute("scopeKind", record.scope().kind().name())
            .attribute("engagedBy", record.by())
            .attribute("engagedAt", record.at().toString()));
    return Optional.of(record);
  }

  /**
   * The halt that applies to {@code context}, if any. When several match, the most specific scope
   * wins (RUN over AGENT over TENANT over GLOBAL); among equals, the one engaged first.
   */
  public Optional<HaltRecord> isHalted(RunContext context) {
    Objects.requireNonNull(context, "context");
    return halts.active().stream().filter(r -> r.scope().matches(context)).min(PRECEDENCE);
  }

  /** Every active halt, most specific first. */
  public List<HaltRecord> active() {
    return halts.active().stream().sorted(PRECEDENCE).collect(Collectors.toUnmodifiableList());
  }

  /** The halt with this id, if still active. */
  public Optional<HaltRecord> find(String id) {
    return halts.find(id);
  }

  Clock clock() {
    return clock;
  }

  AuditSink audit() {
    return audit;
  }

  private void emit(AuditEvent.Builder event) {
    try {
      audit.emit(event.risk(AsiRisk.ASI08).risk(AsiRisk.ASI09).build());
    } catch (RuntimeException e) {
      log.warn("Audit sink threw while recording a halt event", e);
    }
  }

  /** A context carrying the scoped ids so flat sinks show which tenant, agent or run was halted. */
  private static RunContext contextFor(HaltRecord record) {
    HaltScope scope = record.scope();
    RunContext.Builder context = RunContext.builder().runId("halt-" + record.id());
    switch (scope.kind()) {
      case TENANT:
        context.tenantId(scope.id());
        break;
      case AGENT:
        context.agentId(scope.id());
        break;
      case RUN:
        context.runId(Objects.requireNonNull(scope.id()));
        break;
      default:
        break;
    }
    return context.build();
  }
}
