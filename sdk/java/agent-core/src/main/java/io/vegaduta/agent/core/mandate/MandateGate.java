package io.vegaduta.agent.core.mandate;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.gate.ToolDispatchGate;
import io.vegaduta.agent.api.gate.Verdict;
import io.vegaduta.agent.api.mandate.Mandate;
import io.vegaduta.agent.api.mandate.Mandates;
import io.vegaduta.agent.api.risk.RiskLabel;
import io.vegaduta.agent.api.tool.ToolCall;
import io.vegaduta.agent.core.classification.ClassificationResolver;
import io.vegaduta.agent.core.text.Text;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Denies a tool call that exceeds the {@link Mandate} carried by the {@link RunContext}, then hands
 * the call to an optional delegate gate (typically the {@link
 * io.vegaduta.agent.core.gate.PolicyToolDispatchGate}).
 *
 * <p>Order of evaluation:
 *
 * <ol>
 *   <li>no mandate and {@code requireMandate}: {@code DENY} ({@value #REASON_MISSING})
 *   <li>attribute present but not decodable: {@code DENY} ({@value #REASON_MALFORMED})
 *   <li>root mandate whose digest does not match its fields: {@code DENY} ({@value
 *       #REASON_DIGEST_MISMATCH}); a derived mandate's digest needs the chain and is checked with
 *       {@link Mandates#verifyChain} by the hop that derives it
 *   <li>the context names an agent ({@link RunContext#agentId()}) other than the mandate's subject:
 *       {@code DENY} ({@value #REASON_SUBJECT_MISMATCH}); a mandate issued to one agent is not
 *       honoured when carried by another
 *   <li>mandate expired: {@code DENY} ({@value #REASON_EXPIRED})
 *   <li>tool not in the mandate's allowed tools: {@code DENY} ({@value #REASON_TOOL_NOT_PERMITTED})
 *   <li>effective classification risk above {@code maxRisk}: {@code DENY} ({@value
 *       #REASON_RISK_EXCEEDED})
 *   <li>otherwise the delegate's verdict, or {@code ALLOW} without a delegate
 * </ol>
 *
 * <p>Deny reasons carry the mandate id (bounded to 64 characters) after a second colon ({@code
 * mandate:expired:<id>}) so the audit trail written by {@code GovernedTool} names the authority
 * that was exceeded. The gate emits nothing itself.
 *
 * <p><b>Trust model.</b> Mandates are unsigned in-process capabilities ({@link Mandate}). This gate
 * detects accidental widening, a stale or expired mandate and a mandate carried by the wrong agent;
 * it cannot stop code that is already inside the trust domain from minting a fresh root. Use signed
 * tokens at the boundary of the trust domain.
 */
@Experimental
public final class MandateGate implements ToolDispatchGate {

  public static final String REASON_MISSING = "mandate:missing";
  public static final String REASON_MALFORMED = "mandate:malformed";
  public static final String REASON_EXPIRED = "mandate:expired";
  public static final String REASON_TOOL_NOT_PERMITTED = "mandate:tool-not-permitted";
  public static final String REASON_RISK_EXCEEDED = "mandate:risk-exceeded";
  public static final String REASON_DIGEST_MISMATCH = "mandate:digest-mismatch";
  public static final String REASON_SUBJECT_MISMATCH = "mandate:subject-mismatch";

  private static final Logger log = LoggerFactory.getLogger(MandateGate.class);

  private final ClassificationResolver resolver;
  private final boolean requireMandate;
  private final @Nullable ToolDispatchGate delegate;
  private final Clock clock;

  /** A gate with no delegate that uses the system clock. */
  public MandateGate(ClassificationResolver resolver, boolean requireMandate) {
    this(resolver, requireMandate, null, Clock.systemUTC());
  }

  /** A gate that defers to {@code delegate} once the mandate check passes. */
  public MandateGate(
      ClassificationResolver resolver,
      boolean requireMandate,
      @Nullable ToolDispatchGate delegate) {
    this(resolver, requireMandate, delegate, Clock.systemUTC());
  }

  public MandateGate(
      ClassificationResolver resolver,
      boolean requireMandate,
      @Nullable ToolDispatchGate delegate,
      Clock clock) {
    this.resolver = Objects.requireNonNull(resolver, "resolver");
    this.requireMandate = requireMandate;
    this.delegate = delegate;
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public boolean requireMandate() {
    return requireMandate;
  }

  @Override
  public Verdict check(ToolCall call, RunContext context) {
    Optional<Mandate> found;
    try {
      found = Mandates.from(context);
    } catch (IllegalArgumentException e) {
      // The message echoes the attacker-controlled attribute; log only the class.
      log.debug(
          "run {} carries a malformed mandate ({})", context.runId(), e.getClass().getSimpleName());
      return Verdict.deny(REASON_MALFORMED);
    }
    if (found.isEmpty()) {
      return requireMandate ? Verdict.deny(REASON_MISSING) : next(call, context);
    }
    Mandate mandate = found.get();
    String id = Text.bounded(mandate.id(), 64);
    String tool = call.toolName();
    if (mandate.depth() == 0 && !mandate.isSelfConsistent()) {
      return Verdict.deny(REASON_DIGEST_MISMATCH + ":" + id);
    }
    String agentId = context.agentId();
    if (agentId != null && !agentId.equals(mandate.subject())) {
      return Verdict.deny(REASON_SUBJECT_MISMATCH + ":" + id);
    }
    if (mandate.isExpired(clock.instant())) {
      return Verdict.deny(REASON_EXPIRED + ":" + id);
    }
    if (!mandate.permits(tool)) {
      return Verdict.deny(REASON_TOOL_NOT_PERMITTED + ":" + id);
    }
    RiskLabel risk = resolver.effective(tool).risk();
    if (risk.compareTo(mandate.maxRisk()) > 0) {
      return Verdict.deny(REASON_RISK_EXCEEDED + ":" + id);
    }
    return next(call, context);
  }

  private Verdict next(ToolCall call, RunContext context) {
    return delegate == null ? Verdict.allow() : delegate.check(call, context);
  }
}
