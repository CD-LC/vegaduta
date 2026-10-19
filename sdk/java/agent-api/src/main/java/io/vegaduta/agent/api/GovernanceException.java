package io.vegaduta.agent.api;

/** Base class for every exception the SDK raises on purpose. */
public class GovernanceException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public GovernanceException(String message) {
    super(message);
  }

  public GovernanceException(String message, Throwable cause) {
    super(message, cause);
  }
}
