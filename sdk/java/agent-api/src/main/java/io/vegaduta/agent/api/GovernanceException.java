package io.vegaduta.agent.api;

/**
 * Base class for every exception the SDK raises on purpose at run time, so one
 * {@code catch (GovernanceException)} covers every deliberate failure mode (denied tools,
 * budget overruns, guardrail violations, approval and notification failures, store errors).
 * The test kit's assertion failures ({@code ReplayMismatchException}) are deliberately not
 * part of this type: a replay mismatch is a test failure, not a governance outcome.
 */
public class GovernanceException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public GovernanceException(String message) {
    super(message);
  }

  public GovernanceException(String message, Throwable cause) {
    super(message, cause);
  }
}
