package io.vegaduta.agent.core.approval;

import io.vegaduta.agent.api.Experimental;

/**
 * Renders an approval notification as the request body a chat platform expects. {@link
 * SlackMessageFormat} and {@link TeamsMessageFormat} are provided; a host adds its own for
 * ServiceNow, Jira or e-mail by implementing this interface.
 */
@Experimental
public interface MessageFormat {

  /** The body for a request that is waiting for a human. */
  String requested(ApprovalNotification notification);

  /** The body for a request a human has decided. */
  String decided(ApprovalNotification notification);

  /** The {@code Content-Type} of the bodies; JSON by default. */
  default String contentType() {
    return "application/json";
  }

  /** Dispatches on the notification's hook point. */
  default String format(ApprovalNotification notification) {
    return notification.isDecision() ? decided(notification) : requested(notification);
  }
}
