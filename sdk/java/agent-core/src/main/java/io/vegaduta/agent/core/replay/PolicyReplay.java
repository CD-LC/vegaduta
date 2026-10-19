package io.vegaduta.agent.core.replay;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.audit.AuditEvent;
import io.vegaduta.agent.api.gate.ToolDispatchGate;
import io.vegaduta.agent.api.gate.Verdict;
import io.vegaduta.agent.api.risk.ToolClassification;
import io.vegaduta.agent.api.tool.ToolCall;
import io.vegaduta.agent.core.GovernancePolicy;
import io.vegaduta.agent.core.classification.ClassificationResolver;
import io.vegaduta.agent.core.classification.StaticClassificationSource;
import io.vegaduta.agent.core.gate.PolicyToolDispatchGate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Re-evaluates recorded tool calls through a candidate policy and reports what would change.
 *
 * <p>The input is the audit trail ({@code tool.call} events, see {@link RecordedCall}); the
 * candidate is a {@link GovernancePolicy} plus the classifications it would run with. Each call is
 * pushed through a fresh {@link PolicyToolDispatchGate} built over a {@link ClassificationResolver}
 * of a {@link StaticClassificationSource}, exactly as {@code Governance} would build it, and the
 * verdict is compared with the one the live gate applied.
 *
 * <p>Only the gate is replayed. Guardrails, budgets and hooks depend on content and run state that
 * the audit trail does not carry.
 *
 * <p>Configuration methods ({@link #allow}, {@link #deny}, {@link #maxExamples}) all mutate this
 * instance and return it; build a second {@code PolicyReplay} for an independent configuration.
 *
 * <pre>{@code
 * ReplayReport report =
 *     PolicyReplay.of(candidatePolicy, candidateClassifications)
 *         .deny("shell")
 *         .replay(auditSink.events());
 * System.out.println(report.toMarkdown());
 * }</pre>
 */
@Experimental
public final class PolicyReplay {

  private static final int DEFAULT_MAX_EXAMPLES = 10;

  private final GovernancePolicy policy;
  private final PolicyToolDispatchGate gate;
  private int maxExamples;

  private PolicyReplay(GovernancePolicy policy, PolicyToolDispatchGate gate, int maxExamples) {
    this.policy = policy;
    this.gate = gate;
    this.maxExamples = maxExamples;
  }

  /** A replay of {@code candidate} with the given classifications and no allow or deny lists. */
  public static PolicyReplay of(
      GovernancePolicy candidate, Collection<ToolClassification> classifications) {
    Objects.requireNonNull(candidate, "candidate");
    Objects.requireNonNull(classifications, "classifications");
    ClassificationResolver resolver =
        new ClassificationResolver(new StaticClassificationSource(classifications), candidate);
    return new PolicyReplay(candidate, new PolicyToolDispatchGate(resolver), DEFAULT_MAX_EXAMPLES);
  }

  /** Adds tools to the candidate gate's allow list (see {@link PolicyToolDispatchGate#allow}). */
  public PolicyReplay allow(String... toolNames) {
    gate.allow(toolNames);
    return this;
  }

  /** Adds tools to the candidate gate's deny list (see {@link PolicyToolDispatchGate#deny}). */
  public PolicyReplay deny(String... toolNames) {
    gate.deny(toolNames);
    return this;
  }

  /** How many example call ids the report keeps per change kind; default 10. */
  public PolicyReplay maxExamples(int value) {
    if (value < 0) {
      throw new IllegalArgumentException("maxExamples must not be negative");
    }
    this.maxExamples = value;
    return this;
  }

  /** The candidate gate, for callers that want to probe it directly. */
  public ToolDispatchGate gate() {
    return gate;
  }

  public GovernancePolicy policy() {
    return policy;
  }

  /** Replays every {@code tool.call} event in {@code events}. */
  public ReplayReport replay(List<AuditEvent> events) {
    return replayCalls(RecordedCall.from(events));
  }

  /** Replays already-folded calls. */
  public ReplayReport replayCalls(List<RecordedCall> calls) {
    List<ReplayReport.Row> rows = new ArrayList<>(calls.size());
    for (RecordedCall call : calls) {
      // The gate reads only the tool name; arguments are not in the audit trail.
      ToolCall toolCall = new ToolCall(call.callId(), call.toolName(), "{}");
      Verdict replayed = gate.check(toolCall, call.context());
      rows.add(
          new ReplayReport.Row(
              call.callId(),
              call.toolName(),
              call.recordedAt(),
              call.actual(),
              call.actualOutcome(),
              replayed.outcome(),
              replayed.reason(),
              ChangeKind.between(call.actual(), replayed.outcome())));
    }
    return ReplayReport.build(PolicyDiff.describe(policy), rows, maxExamples);
  }

  /** Convenience for callers holding a {@link Set} of classifications from a registry. */
  public static ReplayReport replay(
      List<AuditEvent> events,
      GovernancePolicy candidate,
      Collection<ToolClassification> classifications) {
    return of(candidate, classifications).replay(events);
  }
}
