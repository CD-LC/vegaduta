package io.vegaduta.agent.core;

import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.approval.ApprovalActionExecutor;
import io.vegaduta.agent.api.approval.ApprovalGate;
import io.vegaduta.agent.api.approval.ApprovalRequest;
import io.vegaduta.agent.api.audit.AuditEvent;
import io.vegaduta.agent.api.audit.AuditOutcome;
import io.vegaduta.agent.api.audit.AuditSink;
import io.vegaduta.agent.api.budget.Budget;
import io.vegaduta.agent.api.budget.BudgetExceededException;
import io.vegaduta.agent.api.budget.BudgetReservation;
import io.vegaduta.agent.api.gate.Verdict;
import io.vegaduta.agent.api.guardrail.ContentGuardrail;
import io.vegaduta.agent.api.guardrail.GuardrailResult;
import io.vegaduta.agent.api.guardrail.GuardrailStage;
import io.vegaduta.agent.api.hook.HookDecision;
import io.vegaduta.agent.api.hook.HookEvent;
import io.vegaduta.agent.api.hook.HookPoint;
import io.vegaduta.agent.api.journal.EffectJournal;
import io.vegaduta.agent.api.observe.ToolCallObserver;
import io.vegaduta.agent.api.risk.AsiRisk;
import io.vegaduta.agent.api.risk.ToolClassification;
import io.vegaduta.agent.api.tool.Tool;
import io.vegaduta.agent.api.tool.ToolCall;
import io.vegaduta.agent.api.tool.ToolDescriptor;
import io.vegaduta.agent.api.tool.ToolResult;
import io.vegaduta.agent.core.approval.DefaultApprovalGate;
import io.vegaduta.agent.core.classification.ClassificationResolver;
import io.vegaduta.agent.core.halt.HaltGate;
import io.vegaduta.agent.core.halt.HaltRecord;
import io.vegaduta.agent.core.halt.HaltSwitch;
import io.vegaduta.agent.core.hook.HookEngine;
import io.vegaduta.agent.core.journal.EffectKeys;
import io.vegaduta.agent.core.journal.EffectUnresolvedException;
import io.vegaduta.agent.core.journal.ReconcilingEffectJournal;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The pipeline around one tool. In order: hooks, effect-journal replay (an unresolved interrupted
 * effect fails closed), gate, approval, argument guardrails, budget, the call, journal record,
 * output guardrails, hooks, audit. Every exit path produces exactly one audit event and one
 * observer result.
 *
 * <p>Every {@code tool.call} audit event carries a {@value #STAGE_ATTRIBUTE} attribute naming the
 * pipeline stage that produced it ({@link #STAGE_GATE}, {@link #STAGE_GUARDRAIL}, ...), so a reader
 * such as {@code PolicyReplay} can tell a gate denial from a guardrail or hook denial.
 *
 * <p>Effect journal: a replay-safe tool (idempotent or read-only) is always journaled. A
 * side-effecting, non-idempotent tool is journaled too when the governance has a {@link
 * ReconcilingEffectJournal} (a reconciler was configured), because that is exactly the tool whose
 * interrupted call must not be retried blindly: a recorded result is replayed, an interrupted call
 * is reconciled first. Without a reconciler such a tool is not journaled.
 *
 * <p>Obtain instances from {@link Governance#govern(Tool)}.
 */
public final class GovernedTool implements Tool, ApprovalActionExecutor {

  /** Audit attribute naming the pipeline stage that produced the event. */
  public static final String STAGE_ATTRIBUTE = "stage";

  public static final String STAGE_CLASSIFICATION = "classification";
  public static final String STAGE_HOOK = "hook";
  public static final String STAGE_JOURNAL = "journal";
  public static final String STAGE_GATE = "gate";
  public static final String STAGE_APPROVAL = "approval";
  public static final String STAGE_GUARDRAIL = "guardrail";
  public static final String STAGE_BUDGET = "budget";
  public static final String STAGE_CALL = "call";
  public static final String STAGE_OUTPUT_GUARDRAIL = "output_guardrail";
  public static final String STAGE_HOOK_AFTER = "hook_after";

  private static final Logger log = LoggerFactory.getLogger(GovernedTool.class);

  private final Tool delegate;
  private final Governance governance;

  GovernedTool(Tool delegate, Governance governance) {
    this.delegate = delegate;
    this.governance = governance;
  }

  public Tool delegate() {
    return delegate;
  }

  public ToolClassification classification() {
    return governance.resolver().effective(delegate.name());
  }

  @Override
  public ToolDescriptor descriptor() {
    return delegate.descriptor();
  }

  @Override
  public String call(String argumentsJson, RunContext context) {
    return invoke(ToolCall.of(delegate.name(), argumentsJson), context).output();
  }

  /** The full outcome, for adapters that want the status and not only the text. */
  public ToolResult invoke(ToolCall call, RunContext context) {
    if (governance.policy().mode() == GovernanceMode.OFF) {
      return ToolResult.ok(delegate.call(call.argumentsJson(), context));
    }
    ToolCallObserver.Scope scope = governance.observer().onStart(call, context);
    try {
      ToolResult result = pipeline(call, context);
      scope.onResult(result);
      return result;
    } catch (RuntimeException e) {
      scope.onError(e);
      throw e;
    } finally {
      scope.close();
    }
  }

  /** Whether the effect journal takes part in calls of this classification (see class docs). */
  private boolean journaled(ToolClassification classification) {
    if (classification.replaySafe()) {
      return true;
    }
    return classification.sideEffecting()
        && governance.journal() instanceof ReconcilingEffectJournal;
  }

  private ToolResult pipeline(ToolCall original, RunContext context) {
    String toolName = original.toolName();
    ClassificationResolver resolver = governance.resolver();
    ToolClassification classification = resolver.effective(toolName);
    if (!resolver.isClassified(toolName) && governance.policy().mode() == GovernanceMode.WARN) {
      audit(
          context,
          toolName,
          classification,
          AuditOutcome.WARNING,
          "unclassified tool; default classification applied",
          original,
          STAGE_CLASSIFICATION);
    }

    // 1. hooks before the call
    HookEngine hooks = governance.hooks();
    ToolCall call = original;
    if (hooks.hasHooksFor(HookPoint.BEFORE_TOOL_CALL)) {
      HookDecision decision =
          hooks.run(
              new HookEvent(
                  HookPoint.BEFORE_TOOL_CALL, context, toolName, call.argumentsJson(), Map.of()));
      if (decision instanceof HookDecision.Block block) {
        return denied(context, call, classification, "hook:" + block.reason(), STAGE_HOOK);
      }
      if (decision instanceof HookDecision.Replace replace) {
        call = new ToolCall(call.callId(), toolName, replace.payload());
      }
    }

    // 2. effect journal lookup: an approved and executed call must not run (or ask) twice
    EffectJournal journal = governance.journal();
    String key = EffectKeys.key(context, call);
    boolean journaled = journaled(classification);
    if (journaled) {
      Optional<String> replayed;
      try {
        replayed = journal.lookup(key);
      } catch (EffectUnresolvedException e) {
        // A ReconcilingEffectJournal under UnknownEffectPolicy.HOLD: an earlier attempt was
        // interrupted and the reconciler could not tell whether the effect happened. Fail closed
        // rather than risk a duplicate side effect; an operator resolves the key.
        audit(
            context,
            toolName,
            classification,
            AuditOutcome.FAILURE,
            e.getMessage(),
            call,
            STAGE_JOURNAL);
        return ToolResult.error(
            "effect_unresolved",
            "Tool '"
                + toolName
                + "' was interrupted earlier and its effect could not be verified; not retried to"
                + " avoid a duplicate. An operator must resolve effect key "
                + e.key()
                + ".");
      }
      if (replayed.isPresent()) {
        audit(
            context,
            toolName,
            classification,
            AuditOutcome.REPLAYED,
            "served from effect journal",
            call,
            STAGE_JOURNAL);
        return ToolResult.replayed(replayed.get());
      }
    }

    // 3. gate
    Verdict verdict = governance.gate().check(call, context);
    if (verdict.outcome() == Verdict.Outcome.DENY) {
      return denied(context, call, classification, verdict.reason(), STAGE_GATE);
    }
    if (verdict.outcome() == Verdict.Outcome.REQUIRE_APPROVAL) {
      return escalate(context, call, classification, verdict.reason());
    }

    // 4. argument guardrails
    ContentGuardrail guardrails = governance.guardrails();
    if (guardrails.appliesTo(GuardrailStage.TOOL_ARGUMENTS)) {
      GuardrailResult checked =
          guardrails.evaluate(call.argumentsJson(), GuardrailStage.TOOL_ARGUMENTS, context);
      if (checked.blocked()) {
        hooks.notify(
            new HookEvent(
                HookPoint.ON_GUARDRAIL_VIOLATION,
                context,
                toolName,
                call.argumentsJson(),
                Map.of("stage", "TOOL_ARGUMENTS")));
        audit(
            context,
            toolName,
            classification,
            AuditOutcome.DENIED,
            "guardrail: " + checked.violations(),
            call,
            STAGE_GUARDRAIL);
        return ToolResult.denied(
            "guardrail", Explanations.guardrailBlocked(toolName, checked.violations()));
      }
      if (!checked.clean()) {
        call = new ToolCall(call.callId(), toolName, checked.content());
      }
    }

    // 5. budget
    Budget budget = governance.budgets().budgetFor(context);
    BudgetReservation reservation;
    try {
      reservation = budget.reserve(governance.estimateFor(classification));
    } catch (BudgetExceededException e) {
      hooks.notify(
          new HookEvent(
              HookPoint.ON_BUDGET_EXCEEDED,
              context,
              toolName,
              call.argumentsJson(),
              Map.of("detail", e.getMessage())));
      audit(
          context,
          toolName,
          classification,
          AuditOutcome.BUDGET_EXCEEDED,
          e.getMessage(),
          call,
          STAGE_BUDGET);
      return ToolResult.budgetExceeded(Explanations.budgetExceeded(toolName));
    }

    // 6. the call
    String output;
    try (reservation) {
      if (journaled) {
        journal.begin(key);
      }
      try {
        output = delegate.call(call.argumentsJson(), context);
      } catch (RuntimeException e) {
        // The tool may well have run (and been billed) before it threw: charge what was reserved,
        // so the per-run USD cap keeps bounding a retry loop of failing calls.
        reservation.commit(governance.estimateFor(classification));
        audit(
            context,
            toolName,
            classification,
            AuditOutcome.FAILURE,
            e.toString(),
            call,
            STAGE_CALL);
        log.debug("Tool '{}' threw", toolName, e);
        return ToolResult.error("tool_failed", Explanations.failed(toolName, e));
      }
      reservation.commit(governance.estimateFor(classification));
    }
    if (journaled) {
      journal.record(key, output);
    }

    // 7. output guardrails
    if (guardrails.appliesTo(GuardrailStage.TOOL_OUTPUT)) {
      GuardrailResult checked = guardrails.evaluate(output, GuardrailStage.TOOL_OUTPUT, context);
      if (checked.blocked()) {
        hooks.notify(
            new HookEvent(
                HookPoint.ON_GUARDRAIL_VIOLATION,
                context,
                toolName,
                "",
                Map.of("stage", "TOOL_OUTPUT")));
        audit(
            context,
            toolName,
            classification,
            AuditOutcome.DENIED,
            "output guardrail: " + checked.violations(),
            call,
            STAGE_OUTPUT_GUARDRAIL);
        return ToolResult.denied(
            "guardrail_output", Explanations.outputBlocked(toolName, checked.violations()));
      }
      if (!checked.clean()) {
        audit(
            context,
            toolName,
            classification,
            AuditOutcome.WARNING,
            "output redacted: " + checked.violations(),
            call,
            STAGE_OUTPUT_GUARDRAIL);
      }
      output = checked.content();
    }

    // 8. hooks after the call
    if (hooks.hasHooksFor(HookPoint.AFTER_TOOL_CALL)) {
      HookDecision decision =
          hooks.run(new HookEvent(HookPoint.AFTER_TOOL_CALL, context, toolName, output, Map.of()));
      if (decision instanceof HookDecision.Block block) {
        audit(
            context,
            toolName,
            classification,
            AuditOutcome.DENIED,
            "hook after call: " + block.reason(),
            call,
            STAGE_HOOK_AFTER);
        return ToolResult.denied(
            "hook:" + block.reason(), Explanations.outputBlocked(toolName, java.util.List.of()));
      }
      if (decision instanceof HookDecision.Replace replace) {
        output = replace.payload();
      }
    }

    audit(context, toolName, classification, AuditOutcome.SUCCESS, null, call, STAGE_CALL);
    return ToolResult.ok(output);
  }

  private ToolResult denied(
      RunContext context,
      ToolCall call,
      ToolClassification classification,
      String reason,
      String stage) {
    governance
        .hooks()
        .notify(
            new HookEvent(
                HookPoint.ON_TOOL_DENIED,
                context,
                call.toolName(),
                call.argumentsJson(),
                Map.of("reason", reason)));
    audit(context, call.toolName(), classification, AuditOutcome.DENIED, reason, call, stage);
    return ToolResult.denied(reason, Explanations.denied(call.toolName(), reason));
  }

  private ToolResult escalate(
      RunContext context, ToolCall call, ToolClassification classification, String reason) {
    ApprovalGate approvals = governance.approvals();
    String summary = summary(context, call);
    ApprovalRequest request = approvals.requestForTool(call, context, summary);
    audit(
        context,
        call.toolName(),
        classification,
        AuditOutcome.PENDING_APPROVAL,
        reason,
        call,
        STAGE_GATE,
        Map.of("approvalId", request.id().toString()));
    return ToolResult.pendingApproval(
        request.id().toString(),
        Explanations.pendingApproval(call.toolName(), request.id().toString()));
  }

  private String summary(RunContext context, ToolCall call) {
    int max = governance.policy().argumentPreviewChars();
    String args = call.argumentsJson();
    if (args.length() > max) {
      args = args.substring(0, max) + "…";
    }
    return "Agent "
        + (context.agentId() == null ? "" : context.agentId() + " ")
        + "acting for "
        + context.principalOrAnonymous()
        + " wants to call '"
        + call.toolName()
        + "' with "
        + args;
  }

  // ApprovalActionExecutor: runs the delegate once a human approved the call.

  @Override
  public String actionType() {
    return DefaultApprovalGate.toolActionType(delegate.name());
  }

  /**
   * Runs the approved call. The halt switch is consulted again here: an approval granted after an
   * operator engaged a halt must not run the effect. The call goes through the observer, so cost
   * attribution and other observers see the execution like any other call.
   *
   * @throws IllegalStateException when the run is halted; the approval is then marked FAILED
   */
  @Override
  public String execute(ApprovalRequest request) {
    RunContext context = request.context();
    ToolCall call = ToolCall.of(delegate.name(), request.payloadJson());
    ToolClassification classification = classification();
    Map<String, String> approval = Map.of("approvalId", request.id().toString());
    HaltSwitch halt = governance.haltSwitch();
    if (halt != null) {
      Optional<HaltRecord> halted = halt.isHalted(context);
      if (halted.isPresent()) {
        String reason = HaltGate.reasonFor(halted.get());
        audit(
            context,
            delegate.name(),
            classification,
            AuditOutcome.DENIED,
            reason,
            call,
            STAGE_GATE,
            approval);
        throw new IllegalStateException(reason);
      }
    }
    ToolCallObserver.Scope scope = governance.observer().onStart(call, context);
    try {
      String key = EffectKeys.key(context, call);
      EffectJournal journal = governance.journal();
      journal.begin(key);
      String output = delegate.call(call.argumentsJson(), context);
      journal.record(key, output);
      audit(
          context,
          delegate.name(),
          classification,
          AuditOutcome.SUCCESS,
          "executed after approval",
          call,
          STAGE_APPROVAL,
          approval);
      scope.onResult(ToolResult.ok(output));
      return output;
    } catch (RuntimeException e) {
      scope.onError(e);
      throw e;
    } finally {
      scope.close();
    }
  }

  private void audit(
      RunContext context,
      String toolName,
      ToolClassification classification,
      AuditOutcome outcome,
      String detail,
      ToolCall call,
      String stage) {
    audit(context, toolName, classification, outcome, detail, call, stage, Map.of());
  }

  private void audit(
      RunContext context,
      String toolName,
      ToolClassification classification,
      AuditOutcome outcome,
      String detail,
      ToolCall call,
      String stage,
      Map<String, String> extra) {
    AuditSink sink = governance.audit();
    AuditEvent.Builder builder =
        AuditEvent.builder("tool.call", "tool", toolName)
            .context(context)
            .outcome(outcome)
            .detail(detail)
            .risks(risksFor(classification))
            .attribute("callId", call.callId())
            .attribute("risk", classification.risk().name())
            .attribute(STAGE_ATTRIBUTE, stage);
    extra.forEach(builder::attribute);
    sink.emit(builder.build());
  }

  private static Set<AsiRisk> risksFor(ToolClassification classification) {
    if (!classification.risks().isEmpty()) {
      return classification.risks();
    }
    return classification.sideEffecting() ? Set.of(AsiRisk.ASI02) : Set.of();
  }
}
