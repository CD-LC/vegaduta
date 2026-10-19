package io.vegaduta.agent.core.halt;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.audit.AuditEvent;
import io.vegaduta.agent.api.audit.AuditOutcome;
import io.vegaduta.agent.api.audit.AuditSink;
import io.vegaduta.agent.api.risk.AsiRisk;
import io.vegaduta.agent.api.tool.ToolCall;
import io.vegaduta.agent.api.tool.ToolResult;
import io.vegaduta.agent.api.tool.ToolStatus;
import io.vegaduta.agent.core.GovernedTool;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Proves the kill switch works. The drill engages a halt for the agent of the given context (or for
 * the run, when the context names no agent), invokes a governed tool and expects the halt gate to
 * deny it, releases the halt, invokes the tool again and expects it to no longer be denied by the
 * halt. It records how long that took and emits one {@code halt.drill} audit event with outcome
 * {@code SUCCESS} or {@code FAILURE} and the attributes {@code deniedWhileHalted}, {@code
 * resumedAfterRelease} and {@code millis}.
 *
 * <p>While the halt is engaged, the tool is refused by the gate before any tool code runs. After
 * release the tool runs normally (or, if the governance already journaled an identical call in this
 * run, is replayed from the effect journal), so drive the drill with a harmless probe tool governed
 * by the production {@link io.vegaduta.agent.core.Governance} rather than with a side-effecting
 * one. If the governance has no {@link HaltGate} wired, the halted invocation is not denied, the
 * probe runs, and the report says {@code FAILED}: that is the finding the drill exists to surface.
 *
 * <p>The halt is always released when the drill ends, even if the tool throws.
 */
@Experimental
public final class HaltDrill {

  public static final String ACTION_DRILL = "halt.drill";
  public static final String DEFAULT_REASON = "halt drill";

  private static final Logger log = LoggerFactory.getLogger(HaltDrill.class);

  private final HaltSwitch haltSwitch;
  private final AuditSink audit;
  private final Clock clock;

  /** A drill that audits through the switch's sink and clock. */
  public HaltDrill(HaltSwitch haltSwitch) {
    this(haltSwitch, haltSwitch.audit(), haltSwitch.clock());
  }

  public HaltDrill(HaltSwitch haltSwitch, AuditSink audit, Clock clock) {
    this.haltSwitch = Objects.requireNonNull(haltSwitch, "haltSwitch");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Runs the drill with an empty argument object. */
  public DrillReport run(GovernedTool tool, RunContext context, String by) {
    return run(tool, ToolCall.of(tool.name(), "{}"), context, by);
  }

  /**
   * Runs the drill.
   *
   * @param tool the governed tool to drive; a harmless probe (see class docs)
   * @param call the call to make, twice: once halted, once released
   * @param context the run; the halt targets its agent id, or its run id when there is none
   * @param by who is running the drill, for the halt and audit records
   * @return the evidence
   */
  public DrillReport run(GovernedTool tool, ToolCall call, RunContext context, String by) {
    Objects.requireNonNull(tool, "tool");
    Objects.requireNonNull(call, "call");
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(by, "by");

    String drillId = UUID.randomUUID().toString();
    // Each drill runs under its own run id so its probe call has a fresh effect-journal key: a
    // replay-safe probe journaled by an earlier drill in the same run would otherwise be served
    // from the journal before the gate runs and look like a missing HaltGate.
    context = context.toBuilder().runId(context.runId() + "-drill-" + drillId).build();
    HaltScope scope =
        context.agentId() != null
            ? HaltScope.agent(context.agentId())
            : HaltScope.run(context.runId());
    Instant startedAt = clock.instant();
    long startMillis = clock.millis();
    log.info("Halt drill {} starting on {} with tool '{}'", drillId, scope, tool.name());

    HaltRecord halt = haltSwitch.engage(scope, by, DEFAULT_REASON + " " + drillId);
    ToolResult haltedResult = null;
    ToolResult resumedResult = null;
    String error = null;
    try {
      haltedResult = tool.invoke(call, context);
    } catch (RuntimeException e) {
      error = "halted invocation threw: " + e;
      log.warn("Halt drill {}: halted invocation threw", drillId, e);
    } finally {
      haltSwitch.release(halt.id(), by);
    }
    if (error == null) {
      try {
        resumedResult = tool.invoke(call, context);
      } catch (RuntimeException e) {
        error = "post-release invocation threw: " + e;
        log.warn("Halt drill {}: post-release invocation threw", drillId, e);
      }
    }

    boolean deniedWhileHalted = deniedByHalt(haltedResult);
    boolean resumedAfterRelease = resumedResult != null && !deniedByHalt(resumedResult);
    long millis = Math.max(0, clock.millis() - startMillis);
    DrillReport report =
        new DrillReport(
            drillId,
            tool.name(),
            scope,
            halt.id(),
            by,
            startedAt,
            millis,
            deniedWhileHalted,
            resumedAfterRelease,
            haltedResult,
            resumedResult,
            error);
    emit(report, context);
    if (report.passed()) {
      log.info("Halt drill {} passed in {} ms", drillId, millis);
    } else {
      log.error(
          "Halt drill {} FAILED: deniedWhileHalted={} resumedAfterRelease={} error={}",
          drillId,
          deniedWhileHalted,
          resumedAfterRelease,
          error);
    }
    return report;
  }

  private static boolean deniedByHalt(@Nullable ToolResult result) {
    return result != null
        && result.status() == ToolStatus.DENIED
        && HaltGate.isHaltReason(result.reason());
  }

  private void emit(DrillReport report, RunContext context) {
    AuditEvent.Builder event =
        AuditEvent.builder(ACTION_DRILL, HaltSwitch.TARGET_TYPE, report.id())
            .actor(report.by())
            .context(context)
            .outcome(report.passed() ? AuditOutcome.SUCCESS : AuditOutcome.FAILURE)
            .detail(
                report.passed()
                    ? "kill switch stopped '" + report.toolName() + "' and released cleanly"
                    : "kill switch drill failed for '" + report.toolName() + "'")
            .at(clock.instant())
            .risk(AsiRisk.ASI08)
            .risk(AsiRisk.ASI09)
            .attribute("deniedWhileHalted", Boolean.toString(report.deniedWhileHalted()))
            .attribute("resumedAfterRelease", Boolean.toString(report.resumedAfterRelease()))
            .attribute("millis", Long.toString(report.millis()))
            .attribute("toolName", report.toolName())
            .attribute("haltId", report.haltId())
            .attribute("scope", report.scope().toString());
    if (report.error() != null) {
      event.attribute("error", report.error());
    }
    try {
      audit.emit(event.build());
    } catch (RuntimeException e) {
      log.warn("Audit sink threw while recording halt drill {}", report.id(), e);
    }
  }
}
