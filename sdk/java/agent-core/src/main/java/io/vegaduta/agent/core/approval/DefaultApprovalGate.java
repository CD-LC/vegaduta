package io.vegaduta.agent.core.approval;

import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.approval.ApprovalActionExecutor;
import io.vegaduta.agent.api.approval.ApprovalAlreadyDecidedException;
import io.vegaduta.agent.api.approval.ApprovalDecisionAuthorizer;
import io.vegaduta.agent.api.approval.ApprovalGate;
import io.vegaduta.agent.api.approval.ApprovalNotFoundException;
import io.vegaduta.agent.api.approval.ApprovalRequest;
import io.vegaduta.agent.api.approval.ApprovalStatus;
import io.vegaduta.agent.api.approval.ApprovalStore;
import io.vegaduta.agent.api.audit.AuditEvent;
import io.vegaduta.agent.api.audit.AuditOutcome;
import io.vegaduta.agent.api.audit.AuditSink;
import io.vegaduta.agent.api.hook.HookDecision;
import io.vegaduta.agent.api.hook.HookEvent;
import io.vegaduta.agent.api.hook.HookPoint;
import io.vegaduta.agent.api.risk.AsiRisk;
import io.vegaduta.agent.api.tool.ToolCall;
import io.vegaduta.agent.core.hook.HookEngine;
import io.vegaduta.agent.core.text.Text;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records requests, applies the authorizer, expires stale requests on read, and runs the registered
 * executor on approval. Tool calls use the action type {@code tool:<name>} and keep the raw
 * arguments as payload, so no JSON parsing is needed to replay them.
 *
 * <p>Hooks at the approval points are honoured: a {@link HookDecision.Block} at {@code
 * ON_APPROVAL_REQUESTED} marks the request {@code FAILED} and throws {@link
 * ApprovalNotificationBlockedException} (nobody would have seen it); a block at {@code
 * ON_APPROVAL_DECIDED} records the decision but does not execute the action. Decisions are
 * compare-and-set on the store ({@link ApprovalStore#saveIfStatus}), so of two concurrent approvals
 * exactly one executes and the other gets {@link ApprovalAlreadyDecidedException}; this holds across
 * gates and processes only if the store's {@code saveIfStatus} is atomic (see {@link
 * ApprovalStore}). Decision reasons are bounded to {@value #MAX_REASON} characters. A failed
 * execution stores and audits {@link Text#failure the exception type and a redacted, bounded
 * message}, not the raw exception text.
 *
 * <p>The authorizer is a required constructor argument. {@code Governance} supplies {@link
 * FourEyesAuthorizer} unless the policy turns {@code fourEyes} off or the host passes its own;
 * {@link ApprovalDecisionAuthorizer#allowAny()} is the explicit opt-out.
 */
public final class DefaultApprovalGate implements ApprovalGate {

  private static final Logger log = LoggerFactory.getLogger(DefaultApprovalGate.class);

  public static final String TOOL_ACTION_PREFIX = "tool:";

  /** Upper bound on a stored decision reason. */
  public static final int MAX_REASON = 1000;

  private final ApprovalStore store;
  private final ApprovalDecisionAuthorizer authorizer;
  private final ApprovalActionExecutorRegistry executors;
  private final AuditSink audit;
  private final HookEngine hooks;
  private final Duration ttl;
  private final Clock clock;

  public DefaultApprovalGate(
      ApprovalStore store,
      ApprovalDecisionAuthorizer authorizer,
      ApprovalActionExecutorRegistry executors,
      AuditSink audit,
      HookEngine hooks,
      Duration ttl,
      Clock clock) {
    this.store = store;
    this.authorizer = authorizer;
    this.executors = executors;
    this.audit = audit;
    this.hooks = hooks;
    this.ttl = ttl;
    this.clock = clock;
  }

  public static String toolActionType(String toolName) {
    return TOOL_ACTION_PREFIX + toolName;
  }

  public ApprovalActionExecutorRegistry executors() {
    return executors;
  }

  @Override
  public ApprovalRequest requestForTool(ToolCall call, RunContext context, String summary) {
    return request(toolActionType(call.toolName()), summary, call.argumentsJson(), context);
  }

  @Override
  public ApprovalRequest request(
      String actionType, String summary, String payloadJson, RunContext context) {
    Instant now = clock.instant();
    ApprovalRequest request =
        ApprovalRequest.pending(actionType, summary, payloadJson, context, now, now.plus(ttl));
    store.save(request);
    audit.emit(
        AuditEvent.builder("approval.requested", "approval", request.id().toString())
            .context(context)
            .outcome(AuditOutcome.PENDING_APPROVAL)
            .detail(summary)
            .risk(AsiRisk.ASI09)
            .attribute("actionType", actionType)
            .at(now)
            .build());
    HookDecision decision =
        hooks.run(
            new HookEvent(
                HookPoint.ON_APPROVAL_REQUESTED,
                context,
                toolNameOf(actionType),
                payloadJson,
                Map.of("approvalId", request.id().toString(), "actionType", actionType)));
    if (decision instanceof HookDecision.Block block) {
      // Fail closed: a request nobody was told about must not sit PENDING until it expires.
      String reason = Text.shortText(block.reason());
      ApprovalRequest failed =
          request.withResult(ApprovalStatus.FAILED, "notification blocked: " + reason);
      store.saveIfStatus(failed, ApprovalStatus.PENDING);
      audit.emit(
          decisionEvent(failed, AuditOutcome.FAILURE, "system", "notification blocked: " + reason));
      throw new ApprovalNotificationBlockedException(request.id(), reason);
    }
    return request;
  }

  @Override
  public ApprovalRequest approve(UUID id, String decidedBy) {
    ApprovalRequest request = pendingOrThrow(id);
    authorizer.authorize(request, decidedBy);
    Instant now = clock.instant();
    ApprovalRequest approved = request.withDecision(ApprovalStatus.APPROVED, now, decidedBy, null);
    if (!store.saveIfStatus(approved, ApprovalStatus.PENDING)) {
      throw new ApprovalAlreadyDecidedException(pendingOrThrowRefreshed(id));
    }
    audit.emit(
        decisionEvent(approved, AuditOutcome.APPROVED, decidedBy, "approved by " + decidedBy));
    HookDecision decision = hooks.run(decisionHook(approved, "approved"));
    if (decision instanceof HookDecision.Block block) {
      audit.emit(
          decisionEvent(
              approved,
              AuditOutcome.FAILURE,
              "system",
              "decision hook blocked execution: " + Text.shortText(block.reason())));
      return approved;
    }
    return execute(approved);
  }

  @Override
  public ApprovalRequest reject(UUID id, String decidedBy, @Nullable String reason) {
    ApprovalRequest request = pendingOrThrow(id);
    authorizer.authorize(request, decidedBy);
    Instant now = clock.instant();
    reason = reason == null ? null : Text.bounded(reason, MAX_REASON);
    ApprovalRequest rejected =
        request.withDecision(ApprovalStatus.REJECTED, now, decidedBy, reason);
    if (!store.saveIfStatus(rejected, ApprovalStatus.PENDING)) {
      throw new ApprovalAlreadyDecidedException(pendingOrThrowRefreshed(id));
    }
    audit.emit(decisionEvent(rejected, AuditOutcome.REJECTED, decidedBy, reason));
    hooks.notify(decisionHook(rejected, "rejected"));
    return rejected;
  }

  /** The stored request after a lost decision race, for the exception's message. */
  private ApprovalRequest pendingOrThrowRefreshed(UUID id) {
    return store.find(id).orElseThrow(() -> new ApprovalNotFoundException(id));
  }

  @Override
  public Optional<ApprovalRequest> find(UUID id) {
    return store.find(id).map(this::expireIfDue);
  }

  @Override
  public List<ApprovalRequest> pending(@Nullable String tenantId, int limit) {
    return store.findPending(tenantId, limit).stream()
        .map(this::expireIfDue)
        .filter(r -> r.status() == ApprovalStatus.PENDING)
        .toList();
  }

  private ApprovalRequest pendingOrThrow(UUID id) {
    ApprovalRequest request =
        store.find(id).map(this::expireIfDue).orElseThrow(() -> new ApprovalNotFoundException(id));
    if (request.status() != ApprovalStatus.PENDING) {
      throw new ApprovalAlreadyDecidedException(request);
    }
    return request;
  }

  private ApprovalRequest expireIfDue(ApprovalRequest request) {
    if (!request.isExpired(clock.instant())) {
      return request;
    }
    ApprovalRequest expired = request.withStatus(ApprovalStatus.EXPIRED);
    // Compare-and-set: a decision that landed after this read (another gate, another process)
    // must not be overwritten by the expiry. On a lost race report what is stored.
    if (!store.saveIfStatus(expired, ApprovalStatus.PENDING)) {
      return store.find(request.id()).orElse(request);
    }
    audit.emit(decisionEvent(expired, AuditOutcome.EXPIRED, "system", "expired"));
    return expired;
  }

  private ApprovalRequest execute(ApprovalRequest approved) {
    Optional<ApprovalActionExecutor> executor = executors.find(approved.actionType());
    if (executor.isEmpty()) {
      log.debug(
          "No executor registered for action type '{}'; request {} stays APPROVED",
          approved.actionType(),
          approved.id());
      return approved;
    }
    try {
      String result = executor.get().execute(approved);
      ApprovalRequest executed = approved.withResult(ApprovalStatus.EXECUTED, result);
      store.save(executed);
      audit.emit(decisionEvent(executed, AuditOutcome.SUCCESS, "system", "executed"));
      return executed;
    } catch (RuntimeException e) {
      // The stored result and the audit detail carry the exception type and a bounded, redacted
      // message, never e.toString(): provider errors and SQL exceptions echo secrets and PII.
      String failure = Text.failure(e);
      ApprovalRequest failed = approved.withResult(ApprovalStatus.FAILED, failure);
      store.save(failed);
      audit.emit(decisionEvent(failed, AuditOutcome.FAILURE, "system", failure));
      log.warn("Approved action {} failed: {}", approved.id(), e.toString());
      return failed;
    }
  }

  private static AuditEvent decisionEvent(
      ApprovalRequest request, AuditOutcome outcome, String actor, @Nullable String detail) {
    return AuditEvent.builder("approval.decided", "approval", request.id().toString())
        .context(request.context())
        .actor(actor)
        .outcome(outcome)
        .detail(detail)
        .risk(AsiRisk.ASI09)
        .attribute("actionType", request.actionType())
        .attribute("status", request.status().name())
        .build();
  }

  private static HookEvent decisionHook(ApprovalRequest request, String decision) {
    return new HookEvent(
        HookPoint.ON_APPROVAL_DECIDED,
        request.context(),
        toolNameOf(request.actionType()),
        request.payloadJson(),
        Map.of(
            "approvalId",
            request.id().toString(),
            "decision",
            decision,
            "decidedBy",
            String.valueOf(request.decidedBy())));
  }

  private static @Nullable String toolNameOf(String actionType) {
    return actionType.startsWith(TOOL_ACTION_PREFIX)
        ? actionType.substring(TOOL_ACTION_PREFIX.length())
        : null;
  }
}
