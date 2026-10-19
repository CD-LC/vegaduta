package io.vegaduta.agent.core.autonomy;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.audit.AuditEvent;
import io.vegaduta.agent.api.audit.AuditOutcome;
import io.vegaduta.agent.api.audit.AuditSink;
import io.vegaduta.agent.api.autonomy.AutonomyLevel;
import io.vegaduta.agent.api.risk.AsiRisk;
import io.vegaduta.agent.core.text.Text;
import java.time.Clock;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Which {@link AutonomyLevel} each agent currently holds. Thread-safe; a change takes effect on the
 * next tool call of every run that uses the registry, so demoting an agent after an incident is one
 * line at run time, not a redeploy:
 *
 * <pre>{@code
 * registry.demote("refund-agent", AutonomyLevel.L2_RECOMMEND, "ops@example", "INC-4711");
 * }</pre>
 *
 * <p>Every change emits an {@link AuditEvent} with action type {@value #ACTION_TYPE} to the sink
 * passed in, so the compliance trail shows who lowered or raised an agent, when, and why.
 *
 * <p><b>Audit policy is asymmetric.</b> A demotion is an incident response and is never blocked by
 * the audit path: when the sink throws, the lower level stays and the failure is logged. A
 * promotion widens what the agent may do, so it is only committed when the audit event was
 * accepted: when the sink throws, the level is rolled back and an {@link IllegalStateException} is
 * raised. {@link #demote} and {@link #promote} compare and write atomically, so a concurrent {@link
 * #set} cannot turn a demotion into a raise.
 */
@Experimental
public final class AutonomyRegistry {

  /** Audit action type of every level change. */
  public static final String ACTION_TYPE = "agent.autonomy.changed";

  /** Audit action type emitted when a run carries an unparseable autonomy attribute. */
  public static final String INVALID_ATTRIBUTE_ACTION_TYPE = "agent.autonomy.invalid_attribute";

  /** Audit target type of every level change. */
  public static final String TARGET_TYPE = "agent";

  private static final Logger log = LoggerFactory.getLogger(AutonomyRegistry.class);

  private final Map<String, AutonomyLevel> levels = new ConcurrentHashMap<>();
  private final AutonomyLevel defaultLevel;
  private final AuditSink audit;
  private final Clock clock;

  /** A registry whose unknown agents run at {@code defaultLevel}. */
  public AutonomyRegistry(AutonomyLevel defaultLevel, AuditSink audit) {
    this(defaultLevel, audit, Clock.systemUTC());
  }

  public AutonomyRegistry(AutonomyLevel defaultLevel, AuditSink audit, Clock clock) {
    this.defaultLevel = Objects.requireNonNull(defaultLevel, "defaultLevel");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** The level agents not explicitly registered run at. */
  public AutonomyLevel defaultLevel() {
    return defaultLevel;
  }

  /** The explicitly registered level of {@code agentId}, if any. */
  public Optional<AutonomyLevel> get(String agentId) {
    return Optional.ofNullable(levels.get(agentId));
  }

  /** The level {@code agentId} runs at: its registered level, else the default. */
  public AutonomyLevel levelOf(String agentId) {
    return levels.getOrDefault(agentId, defaultLevel);
  }

  /**
   * Sets the level of an agent, in either direction, and records who did it and why.
   *
   * @return the level the agent had before, or {@code null} when it was not registered
   * @throws IllegalStateException when the change raises the level and the audit sink rejects the
   *     event; the level is left unchanged
   */
  public @Nullable AutonomyLevel set(
      String agentId, AutonomyLevel level, String by, String reason) {
    Objects.requireNonNull(agentId, "agentId");
    Objects.requireNonNull(level, "level");
    Objects.requireNonNull(by, "by");
    Objects.requireNonNull(reason, "reason");
    @Nullable AutonomyLevel[] previous = new AutonomyLevel[1];
    levels.compute(
        agentId,
        (k, cur) -> {
          previous[0] = cur;
          return level;
        });
    commit(agentId, previous[0], level, by, reason);
    return previous[0];
  }

  /**
   * Lowers an agent's autonomy. A no-op when the agent already runs at or below {@code level};
   * never raises, even when another thread changes the level concurrently.
   *
   * @return true when the level changed
   */
  public boolean demote(String agentId, AutonomyLevel level, String by, String reason) {
    return change(agentId, level, by, reason, true);
  }

  /**
   * Raises an agent's autonomy. A no-op when the agent already runs at or above {@code level};
   * never lowers, even when another thread changes the level concurrently.
   *
   * @return true when the level changed
   * @throws IllegalStateException when the audit sink rejects the event; the level is rolled back
   */
  public boolean promote(String agentId, AutonomyLevel level, String by, String reason) {
    return change(agentId, level, by, reason, false);
  }

  private boolean change(
      String agentId, AutonomyLevel level, String by, String reason, boolean lowering) {
    Objects.requireNonNull(agentId, "agentId");
    Objects.requireNonNull(level, "level");
    Objects.requireNonNull(by, "by");
    Objects.requireNonNull(reason, "reason");
    @Nullable AutonomyLevel[] previous = new AutonomyLevel[1];
    boolean[] changed = new boolean[1];
    levels.compute(
        agentId,
        (k, cur) -> {
          previous[0] = cur;
          AutonomyLevel current = cur == null ? defaultLevel : cur;
          boolean applies = lowering ? level.below(current) : current.below(level);
          changed[0] = applies;
          return applies ? level : cur;
        });
    if (!changed[0]) {
      log.debug(
          "autonomy: {} already at {} (asked to {} to {})",
          agentId,
          previous[0] == null ? defaultLevel : previous[0],
          lowering ? "demote" : "promote",
          level);
      return false;
    }
    commit(agentId, previous[0], level, by, reason);
    return true;
  }

  /**
   * Forgets the explicit level of an agent so it falls back to the default. Audited as a change
   * when the effective level moves.
   */
  public void clear(String agentId, String by, String reason) {
    AutonomyLevel previous = levels.remove(agentId);
    if (previous != null && previous != defaultLevel) {
      if (!emit(agentId, previous, defaultLevel, by, reason) && defaultLevel.atLeast(previous)) {
        // Clearing raised the level and the raise was not recorded: undo it.
        levels.putIfAbsent(agentId, previous);
        throw new IllegalStateException(
            "autonomy: audit sink rejected the promotion of " + agentId + "; level unchanged");
      }
    }
  }

  /** The explicitly registered agents and their levels, sorted by agent id. */
  public Map<String, AutonomyLevel> snapshot() {
    return Collections.unmodifiableMap(new TreeMap<>(levels));
  }

  /** Records a run that carried an unparseable {@link AutonomyResolver#ATTRIBUTE}. */
  void auditInvalidAttribute(RunContext context, String attribute) {
    AuditEvent event =
        AuditEvent.builder(
                INVALID_ATTRIBUTE_ACTION_TYPE,
                TARGET_TYPE,
                context.agentId() == null ? "-" : context.agentId())
            .at(clock.instant())
            .outcome(AuditOutcome.WARNING)
            .detail("invalid autonomy attribute; run resolved to " + AutonomyLevel.L1_OBSERVE)
            .context(context)
            .risk(AsiRisk.ASI03)
            .attribute("autonomy.attribute", Text.bounded(attribute, 64))
            .build();
    try {
      audit.emit(event);
    } catch (RuntimeException e) {
      log.warn("autonomy: audit sink failed for run {}", context.runId(), e);
    }
  }

  /**
   * Audits a committed change. A raise that the sink rejects is rolled back and rethrown; a
   * lowering stays in force whatever the sink does.
   */
  private void commit(
      String agentId,
      @Nullable AutonomyLevel previous,
      AutonomyLevel to,
      String by,
      String reason) {
    AutonomyLevel from = previous == null ? defaultLevel : previous;
    if (from == to) {
      return;
    }
    if (emit(agentId, from, to, by, reason) || to.below(from)) {
      return;
    }
    // Promotion without an audit record: roll back to what was there before.
    if (previous == null) {
      levels.remove(agentId, to);
    } else {
      levels.replace(agentId, to, previous);
    }
    throw new IllegalStateException(
        "autonomy: audit sink rejected the promotion of " + agentId + "; level unchanged");
  }

  /**
   * @return true when the sink accepted the event
   */
  private boolean emit(
      String agentId, AutonomyLevel from, AutonomyLevel to, String by, String reason) {
    boolean demotion = to.below(from);
    String safeBy = Text.shortText(by);
    String safeReason = Text.shortText(reason);
    AuditEvent event =
        AuditEvent.builder(ACTION_TYPE, TARGET_TYPE, agentId)
            .actor(safeBy)
            .at(clock.instant())
            .outcome(AuditOutcome.SUCCESS)
            .detail(safeReason)
            .context(RunContext.builder().agentId(agentId).principal(safeBy).build())
            .risk(AsiRisk.ASI03)
            .risk(AsiRisk.ASI10)
            .attribute("autonomy.from", from.name())
            .attribute("autonomy.to", to.name())
            .attribute("autonomy.change", demotion ? "demote" : "promote")
            .build();
    log.info(
        "autonomy: {} {} {} -> {} by {} ({})",
        demotion ? "demoted" : "promoted",
        agentId,
        from,
        to,
        safeBy,
        safeReason);
    try {
      audit.emit(event);
      return true;
    } catch (RuntimeException e) {
      log.warn("autonomy: audit sink failed for {}", agentId, e);
      return false;
    }
  }
}
