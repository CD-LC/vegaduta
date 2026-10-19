package io.vegaduta.agent.core.approval;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.core.hook.JsonLite;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Microsoft Teams incoming-webhook body carrying an Adaptive Card (schema 1.4): a title, a fact set
 * and the command hint. Teams has no slash commands for webhooks, so the hint names the text a bot
 * or ServiceNow integration should relay to {@link ChatApprovalHandler}.
 */
@Experimental
public final class TeamsMessageFormat implements MessageFormat {

  private final String commandPrefix;

  public TeamsMessageFormat() {
    this("");
  }

  /**
   * @param commandPrefix text in front of each command hint, for example {@code "@vegaduta"}
   */
  public TeamsMessageFormat(String commandPrefix) {
    this.commandPrefix = Objects.requireNonNull(commandPrefix, "commandPrefix").strip();
  }

  @Override
  public String requested(ApprovalNotification n) {
    List<String[]> facts = new ArrayList<>();
    facts.add(new String[] {"Action", n.what()});
    if (n.summary() != null) {
      facts.add(new String[] {"Summary", n.summary()});
    }
    addRunFacts(facts, n);
    facts.add(new String[] {"Approval id", n.approvalId()});
    String hint =
        "Reply '"
            + command("approve", n.approvalId(), null)
            + "' or '"
            + command("reject", n.approvalId(), "<reason>")
            + "'";
    return card("Approval requested", facts, hint);
  }

  @Override
  public String decided(ApprovalNotification n) {
    String decision = n.decision() == null ? "decided" : n.decision();
    List<String[]> facts = new ArrayList<>();
    facts.add(new String[] {"Action", n.what()});
    facts.add(new String[] {"Decision", decision});
    facts.add(new String[] {"Decided by", String.valueOf(n.decidedBy())});
    if (n.reason() != null) {
      facts.add(new String[] {"Reason", n.reason()});
    }
    addRunFacts(facts, n);
    facts.add(new String[] {"Approval id", n.approvalId()});
    return card("Approval " + decision, facts, null);
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

  private static void addRunFacts(List<String[]> facts, ApprovalNotification n) {
    if (n.context().tenantId() != null) {
      facts.add(new String[] {"Tenant", n.context().tenantId()});
    }
    if (n.context().principal() != null) {
      facts.add(new String[] {"Requested by", n.context().principal()});
    }
    if (n.context().agentId() != null) {
      facts.add(new String[] {"Agent", n.context().agentId()});
    }
  }

  private static String card(String title, List<String[]> facts, @Nullable String hint) {
    StringBuilder body = new StringBuilder();
    body.append("{\"type\":\"TextBlock\",\"size\":\"Large\",\"weight\":\"Bolder\",\"text\":\"")
        .append(JsonLite.escape(title))
        .append("\"}");
    body.append(",{\"type\":\"FactSet\",\"facts\":[");
    for (int i = 0; i < facts.size(); i++) {
      if (i > 0) {
        body.append(',');
      }
      body.append("{\"title\":\"")
          .append(JsonLite.escape(facts.get(i)[0]))
          .append("\",\"value\":\"")
          .append(JsonLite.escape(facts.get(i)[1]))
          .append("\"}");
    }
    body.append("]}");
    if (hint != null) {
      body.append(",{\"type\":\"TextBlock\",\"wrap\":true,\"text\":\"")
          .append(JsonLite.escape(hint))
          .append("\"}");
    }
    return "{\"type\":\"message\",\"attachments\":[{\"contentType\":"
        + "\"application/vnd.microsoft.card.adaptive\",\"content\":{"
        + "\"$schema\":\"http://adaptivecards.io/schemas/adaptive-card.json\","
        + "\"type\":\"AdaptiveCard\",\"version\":\"1.4\",\"body\":["
        + body
        + "]}}]}";
  }
}
