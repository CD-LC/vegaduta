package io.vegaduta.agent.api.gate;

import io.vegaduta.agent.api.GovernanceException;

/** Thrown by adapters that prefer an exception over a {@code DENIED} result. */
public class ToolDeniedException extends GovernanceException {

  private static final long serialVersionUID = 1L;

  private final String toolName;
  private final String reason;

  public ToolDeniedException(String toolName, String reason, String message) {
    super(message);
    this.toolName = toolName;
    this.reason = reason;
  }

  public String toolName() {
    return toolName;
  }

  public String reason() {
    return reason;
  }
}
