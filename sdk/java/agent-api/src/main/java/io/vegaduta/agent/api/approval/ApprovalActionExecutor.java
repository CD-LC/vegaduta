package io.vegaduta.agent.api.approval;

/**
 * Runs an approved action. The SDK registers one executor per governed tool (action type {@code
 * "tool:<name>"}); hosts register their own for other action types.
 */
public interface ApprovalActionExecutor {

  /** The {@link ApprovalRequest#actionType()} this executor handles. */
  String actionType();

  /**
   * Executes the action described by {@link ApprovalRequest#payloadJson()}.
   *
   * @return the action's result, stored on the request
   */
  String execute(ApprovalRequest request);
}
