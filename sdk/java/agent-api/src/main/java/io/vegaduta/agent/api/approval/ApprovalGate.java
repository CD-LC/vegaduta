package io.vegaduta.agent.api.approval;

import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.tool.ToolCall;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Records actions that need a human and applies the decisions.
 *
 * <p>The host application owns the user interface: it lists {@link #pending(String, int)} and calls
 * {@link #approve(UUID, String)} or {@link #reject(UUID, String, String)} from its own UI, chat
 * command, ticket system or the optional starter endpoints.
 */
public interface ApprovalGate {

  /** Records a tool call that must wait for a human. */
  ApprovalRequest requestForTool(ToolCall call, RunContext context, String summary);

  /** Records any action (not only a tool call) that must wait for a human. */
  ApprovalRequest request(
      String actionType, String summary, String payloadJson, RunContext context);

  /**
   * Approves and, when an {@link ApprovalActionExecutor} is registered for the action type,
   * executes the action.
   *
   * @throws ApprovalNotFoundException when the id is unknown
   * @throws ApprovalAlreadyDecidedException when the request is no longer pending
   * @throws ApprovalNotAuthorizedException when {@code decidedBy} may not decide this request
   */
  ApprovalRequest approve(UUID id, String decidedBy);

  ApprovalRequest reject(UUID id, String decidedBy, @Nullable String reason);

  Optional<ApprovalRequest> find(UUID id);

  java.util.List<ApprovalRequest> pending(@Nullable String tenantId, int limit);
}
