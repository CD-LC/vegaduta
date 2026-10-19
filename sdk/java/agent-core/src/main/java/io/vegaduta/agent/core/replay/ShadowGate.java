package io.vegaduta.agent.core.replay;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.audit.AuditEvent;
import io.vegaduta.agent.api.audit.AuditOutcome;
import io.vegaduta.agent.api.audit.AuditSink;
import io.vegaduta.agent.api.gate.ToolDispatchGate;
import io.vegaduta.agent.api.gate.Verdict;
import io.vegaduta.agent.api.risk.AsiRisk;
import io.vegaduta.agent.api.tool.ToolCall;
import io.vegaduta.agent.core.text.Text;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs a candidate gate alongside the live one without enforcing it.
 *
 * <p>The live verdict is returned unchanged, always. The shadow gate is evaluated for the same
 * call; when the two verdicts differ an audit event with action type {@value #ACTION_TYPE} is
 * emitted and counted. A shadow gate that throws is logged and counted as an error, never
 * propagated: shadow mode must not be able to break the live path. The candidate is evaluated
 * synchronously on the calling thread, so its latency adds to every live call; keep candidate gates
 * cheap (a {@code PolicyToolDispatchGate} over an in-memory classification source) and do not put a
 * remote lookup behind a shadow gate. Reasons are bounded before they are audited.
 *
 * <pre>{@code
 * ShadowGate shadow = new ShadowGate(liveGate, candidateGate, auditSink);
 * Governance.builder().gate(shadow)...
 * // later
 * ShadowReport report = ShadowReport.from(auditSink.events());
 * }</pre>
 */
@Experimental
public final class ShadowGate implements ToolDispatchGate {

  /** Action type of the divergence event. */
  public static final String ACTION_TYPE = "shadow.decision";

  public static final String ATTR_LIVE = "live";
  public static final String ATTR_LIVE_REASON = "liveReason";
  public static final String ATTR_SHADOW = "shadow";
  public static final String ATTR_SHADOW_REASON = "shadowReason";
  public static final String ATTR_CALL_ID = "callId";
  public static final String ATTR_CHANGE = "change";

  private static final Logger log = LoggerFactory.getLogger(ShadowGate.class);

  private final ToolDispatchGate live;
  private final ToolDispatchGate shadow;
  private final AuditSink audit;
  private final AtomicLong evaluated = new AtomicLong();
  private final AtomicLong divergences = new AtomicLong();
  private final AtomicLong shadowErrors = new AtomicLong();

  public ShadowGate(ToolDispatchGate live, ToolDispatchGate shadow, AuditSink audit) {
    this.live = Objects.requireNonNull(live, "live");
    this.shadow = Objects.requireNonNull(shadow, "shadow");
    this.audit = Objects.requireNonNull(audit, "audit");
  }

  @Override
  public Verdict check(ToolCall call, RunContext context) {
    Verdict liveVerdict = live.check(call, context);
    evaluated.incrementAndGet();
    Verdict shadowVerdict;
    try {
      shadowVerdict = shadow.check(call, context);
    } catch (RuntimeException e) {
      shadowErrors.incrementAndGet();
      log.warn("Shadow gate threw for tool '{}'; live verdict stands", call.toolName(), e);
      return liveVerdict;
    }
    if (shadowVerdict.outcome() != liveVerdict.outcome()) {
      divergences.incrementAndGet();
      emit(call, context, liveVerdict, shadowVerdict);
    }
    return liveVerdict;
  }

  private void emit(ToolCall call, RunContext context, Verdict liveVerdict, Verdict shadowVerdict) {
    ChangeKind change = ChangeKind.between(liveVerdict.outcome(), shadowVerdict.outcome());
    AuditEvent event =
        AuditEvent.builder(ACTION_TYPE, "tool", call.toolName())
            .context(context)
            .outcome(AuditOutcome.WARNING)
            .detail(
                "shadow policy would "
                    + shadowVerdict.outcome().name()
                    + " where live did "
                    + liveVerdict.outcome().name())
            .risk(AsiRisk.ASI02)
            .attribute(ATTR_CALL_ID, call.callId())
            .attribute(ATTR_LIVE, liveVerdict.outcome().name())
            .attribute(ATTR_LIVE_REASON, Text.shortText(liveVerdict.reason()))
            .attribute(ATTR_SHADOW, shadowVerdict.outcome().name())
            .attribute(ATTR_SHADOW_REASON, Text.shortText(shadowVerdict.reason()))
            .attribute(ATTR_CHANGE, change.name())
            .build();
    try {
      audit.emit(event);
    } catch (RuntimeException e) {
      log.warn("Audit sink threw for shadow decision on '{}'", call.toolName(), e);
    }
  }

  /** Calls checked so far. */
  public long evaluated() {
    return evaluated.get();
  }

  /** Calls where the shadow verdict differed from the live one. */
  public long divergences() {
    return divergences.get();
  }

  /** Calls where the shadow gate threw. */
  public long shadowErrors() {
    return shadowErrors.get();
  }
}
