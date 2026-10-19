package io.vegaduta.agent.core.approval;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.approval.ApprovalRequest;
import io.vegaduta.agent.api.approval.ApprovalStore;
import io.vegaduta.agent.api.hook.FailMode;
import io.vegaduta.agent.api.hook.Hook;
import io.vegaduta.agent.api.hook.HookDecision;
import io.vegaduta.agent.api.hook.HookEvent;
import io.vegaduta.agent.api.hook.HookPoint;
import io.vegaduta.agent.core.hook.HookRegistration;
import java.net.URI;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Posts approval requests and decisions to the place people already work: a Slack or Teams channel,
 * or anything else with a webhook. Register it at {@link HookPoint#ON_APPROVAL_REQUESTED} and
 * {@link HookPoint#ON_APPROVAL_DECIDED} ({@link #registration(String)} does both).
 *
 * <p>By default a delivery failure is logged and the hook returns {@link HookDecision#CONTINUE}: a
 * chat outage must not stop the approval from being recorded. A host that wants the registration's
 * {@link FailMode} to apply (fail closed when the approvers cannot be reached) builds the notifier
 * with {@link Builder#propagateFailures(boolean) propagateFailures(true)}; the hook then rethrows
 * and {@code HookEngine} applies the registration's fail mode.
 *
 * <p>When an {@link ApprovalStore} is given, the message carries the request's summary and reason;
 * the hook event alone has only the id, the tool and the payload.
 */
@Experimental
public final class ApprovalNotifier implements Hook {

  private static final Logger log = LoggerFactory.getLogger(ApprovalNotifier.class);

  /** The points this hook handles. */
  public static final Set<HookPoint> POINTS =
      Set.of(HookPoint.ON_APPROVAL_REQUESTED, HookPoint.ON_APPROVAL_DECIDED);

  private final MessageFormat format;
  private final Transport transport;
  private final URI url;
  private final @Nullable ApprovalStore approvals;
  private final boolean propagateFailures;

  private ApprovalNotifier(Builder b) {
    this.format = Objects.requireNonNull(b.format, "format");
    this.transport = Objects.requireNonNull(b.transport, "transport");
    this.url = Objects.requireNonNull(b.url, "url");
    this.approvals = b.approvals;
    this.propagateFailures = b.propagateFailures;
  }

  public static Builder builder() {
    return new Builder();
  }

  /** A Slack incoming-webhook notifier with the JDK HTTP transport. */
  public static ApprovalNotifier slack(URI webhookUrl) {
    return builder().format(new SlackMessageFormat()).url(webhookUrl).build();
  }

  /** A Teams incoming-webhook notifier with the JDK HTTP transport. */
  public static ApprovalNotifier teams(URI webhookUrl) {
    return builder().format(new TeamsMessageFormat()).url(webhookUrl).build();
  }

  /** A fail-open registration at both approval points for every tool. */
  public HookRegistration registration(String name) {
    return registration(name, FailMode.OPEN);
  }

  public HookRegistration registration(String name, FailMode failMode) {
    return new HookRegistration(name, POINTS, "*", this, failMode);
  }

  @Override
  public HookDecision apply(HookEvent event) {
    if (!POINTS.contains(event.point())) {
      return HookDecision.CONTINUE;
    }
    try {
      ApprovalNotification notification = ApprovalNotification.from(event, lookup(event));
      String body = format.format(notification);
      transport.send(url, format.contentType(), body);
    } catch (RuntimeException e) {
      if (propagateFailures) {
        throw e;
      }
      log.warn(
          "Approval notification at {} for {} failed (continuing): {}",
          event.point(),
          event.attributes().get("approvalId"),
          e.toString());
    }
    return HookDecision.CONTINUE;
  }

  private @Nullable ApprovalRequest lookup(HookEvent event) {
    if (approvals == null) {
      return null;
    }
    String id = event.attributes().get("approvalId");
    if (id == null) {
      return null;
    }
    try {
      return approvals.find(UUID.fromString(id)).orElse(null);
    } catch (RuntimeException e) {
      log.debug("Could not read approval {} for its notification: {}", id, e.toString());
      return null;
    }
  }

  public static final class Builder {
    private @Nullable MessageFormat format;
    private @Nullable Transport transport;
    private @Nullable URI url;
    private @Nullable ApprovalStore approvals;
    private boolean propagateFailures;

    private Builder() {}

    public Builder format(MessageFormat value) {
      this.format = Objects.requireNonNull(value, "format");
      return this;
    }

    public Builder transport(Transport value) {
      this.transport = Objects.requireNonNull(value, "transport");
      return this;
    }

    public Builder url(URI value) {
      this.url = Objects.requireNonNull(value, "url");
      return this;
    }

    public Builder url(String value) {
      return url(URI.create(value));
    }

    /** Lets messages carry the request summary and the decision reason. */
    public Builder approvalStore(@Nullable ApprovalStore value) {
      this.approvals = value;
      return this;
    }

    /**
     * When {@code true}, a delivery failure is rethrown so the registration's {@link FailMode}
     * applies. Default {@code false}: log and continue.
     */
    public Builder propagateFailures(boolean value) {
      this.propagateFailures = value;
      return this;
    }

    public ApprovalNotifier build() {
      if (transport == null) {
        transport = new JdkHttpTransport();
      }
      return new ApprovalNotifier(this);
    }
  }
}
