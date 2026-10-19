package io.vegaduta.agent.core.approval;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.approval.ApprovalAlreadyDecidedException;
import io.vegaduta.agent.api.approval.ApprovalGate;
import io.vegaduta.agent.api.approval.ApprovalNotAuthorizedException;
import io.vegaduta.agent.api.approval.ApprovalNotFoundException;
import io.vegaduta.agent.api.approval.ApprovalRequest;
import io.vegaduta.agent.core.approval.ChatCommandParser.Command;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Applies a chat reply to the {@link ApprovalGate}. The chat user's id becomes {@code decidedBy},
 * so the gate's authorizer (four-eyes by default) and the receipt both see the real approver. The
 * host's bot wires its message handler to {@link #handle(String, String)} and posts the returned
 * text back to the channel.
 *
 * <p>The handler never throws for the expected failures (unknown id, already decided, not
 * authorised); each becomes a sentence for the approver.
 */
@Experimental
public final class ChatApprovalHandler {

  private final ApprovalGate gate;

  public ChatApprovalHandler(ApprovalGate gate) {
    this.gate = Objects.requireNonNull(gate, "gate");
  }

  /**
   * @param text the message as typed
   * @param chatUserId the identity of the author as the chat platform knows it
   * @return a reply for the channel
   */
  public String handle(@Nullable String text, String chatUserId) {
    Optional<Command> command = ChatCommandParser.parse(text);
    if (command.isEmpty()) {
      return ChatCommandParser.usage();
    }
    return apply(command.get(), chatUserId);
  }

  public String apply(Command command, String chatUserId) {
    Objects.requireNonNull(chatUserId, "chatUserId");
    try {
      ApprovalRequest decided =
          switch (command.action()) {
            case APPROVE -> gate.approve(command.approvalId(), chatUserId);
            case REJECT -> gate.reject(command.approvalId(), chatUserId, command.reason());
          };
      return switch (decided.status()) {
        case EXECUTED -> "Approved and executed " + describe(decided) + ".";
        case FAILED ->
            "Approved "
                + describe(decided)
                + ", but execution failed; see the audit trail for approval "
                + decided.id()
                + ".";
        case APPROVED -> "Approved " + describe(decided) + "; it will run when picked up.";
        case REJECTED ->
            "Rejected "
                + describe(decided)
                + (decided.decisionReason() == null ? "." : ": " + decided.decisionReason());
        default -> "Approval " + decided.id() + " is now " + decided.status() + ".";
      };
    } catch (ApprovalNotFoundException e) {
      return "No approval request with id " + command.approvalId() + ".";
    } catch (ApprovalAlreadyDecidedException e) {
      return e.getMessage() + "; nothing changed.";
    } catch (ApprovalNotAuthorizedException e) {
      return "Not allowed: " + e.getMessage();
    }
  }

  private static String describe(ApprovalRequest request) {
    return request.summary() + " (" + request.id() + ")";
  }
}
