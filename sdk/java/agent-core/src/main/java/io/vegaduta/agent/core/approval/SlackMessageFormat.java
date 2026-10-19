package io.vegaduta.agent.core.approval;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.core.hook.JsonLite;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Slack incoming-webhook body in Block Kit form: a header, a section with the facts, and a context
 * block telling the approver which command to type. The commands match what {@link
 * ChatCommandParser} understands, so a slash-command or bot handler can pass the reply straight to
 * {@link ChatApprovalHandler}.
 */
@Experimental
public final class SlackMessageFormat implements MessageFormat {

  private final String commandPrefix;

  /** Commands rendered as {@code approve <id>} and {@code reject <id> <reason>}. */
  public SlackMessageFormat() {
    this("");
  }

  /**
   * @param commandPrefix text in front of each command hint, for example {@code "/vegaduta"}
   */
  public SlackMessageFormat(String commandPrefix) {
    this.commandPrefix = Objects.requireNonNull(commandPrefix, "commandPrefix").strip();
  }

  @Override
  public String requested(ApprovalNotification n) {
    String fallback = "Approval requested: " + mrkdwn(title(n)) + " [" + n.approvalId() + "]";
    StringBuilder facts = new StringBuilder();
    facts.append("*Action:* ").append(mrkdwn(n.what()));
    if (n.summary() != null) {
      facts.append("\\n*Summary:* ").append(mrkdwn(n.summary()));
    }
    appendRunFacts(facts, n);
    facts.append("\\n*Approval id:* `").append(mrkdwn(n.approvalId())).append('`');
    String hint =
        "Reply `"
            + command("approve", n.approvalId(), null)
            + "` or `"
            + command("reject", n.approvalId(), "<reason>")
            + "`";
    return "{\"text\":\""
        + JsonLite.escape(fallback)
        + "\",\"blocks\":["
        + header("Approval requested")
        + ","
        + section(facts.toString())
        + ","
        + context(hint)
        + "]}";
  }

  @Override
  public String decided(ApprovalNotification n) {
    String decision = n.decision() == null ? "decided" : n.decision();
    String fallback =
        "Approval "
            + mrkdwn(decision)
            + ": "
            + mrkdwn(title(n))
            + " ["
            + n.approvalId()
            + "] by "
            + mrkdwn(String.valueOf(n.decidedBy()));
    StringBuilder facts = new StringBuilder();
    facts.append("*Action:* ").append(mrkdwn(n.what()));
    facts.append("\\n*Decision:* ").append(mrkdwn(decision));
    facts.append("\\n*Decided by:* ").append(mrkdwn(String.valueOf(n.decidedBy())));
    if (n.reason() != null) {
      facts.append("\\n*Reason:* ").append(mrkdwn(n.reason()));
    }
    appendRunFacts(facts, n);
    facts.append("\\n*Approval id:* `").append(mrkdwn(n.approvalId())).append('`');
    return "{\"text\":\""
        + JsonLite.escape(fallback)
        + "\",\"blocks\":["
        + header("Approval " + decision)
        + ","
        + section(facts.toString())
        + "]}";
  }

  private String command(String verb, String id, @Nullable String tail) {
    StringBuilder sb = new StringBuilder();
    if (!commandPrefix.isEmpty()) {
      sb.append(commandPrefix).append(' ');
    }
    sb.append(verb).append(' ').append(id);
    if (tail != null) {
      sb.append(' ').append(tail);
    }
    return sb.toString();
  }

  private static void appendRunFacts(StringBuilder facts, ApprovalNotification n) {
    if (n.context().tenantId() != null) {
      facts.append("\\n*Tenant:* ").append(mrkdwn(n.context().tenantId()));
    }
    if (n.context().principal() != null) {
      facts.append("\\n*Requested by:* ").append(mrkdwn(n.context().principal()));
    }
    if (n.context().agentId() != null) {
      facts.append("\\n*Agent:* ").append(mrkdwn(n.context().agentId()));
    }
  }

  private static String title(ApprovalNotification n) {
    return n.summary() != null ? n.summary() : n.what();
  }

  private static String header(String text) {
    return "{\"type\":\"header\",\"text\":{\"type\":\"plain_text\",\"text\":\""
        + JsonLite.escape(text)
        + "\"}}";
  }

  /** The facts are pre-escaped mrkdwn, so they are inserted without a second escaping pass. */
  private static String section(String escapedMrkdwn) {
    return "{\"type\":\"section\",\"text\":{\"type\":\"mrkdwn\",\"text\":\""
        + escapedMrkdwn
        + "\"}}";
  }

  private static String context(String text) {
    return "{\"type\":\"context\",\"elements\":[{\"type\":\"mrkdwn\",\"text\":\""
        + JsonLite.escape(text)
        + "\"}]}";
  }

  /** JSON-escapes a value after neutralising Slack's control characters as Slack documents. */
  private static String mrkdwn(String value) {
    return JsonLite.escape(value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"));
  }
}
