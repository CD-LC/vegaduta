package io.vegaduta.agent.core.approval;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.core.text.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Parses the reply an approver types in chat.
 *
 * <p>Accepted shapes, case-insensitive, whitespace-tolerant:
 *
 * <pre>
 * approve &lt;id&gt;
 * reject &lt;id&gt; &lt;reason...&gt;
 * /vegaduta approve &lt;id&gt;
 * {@literal @}vegaduta reject &lt;id&gt; too expensive
 * </pre>
 *
 * {@code deny} is a synonym for {@code reject}. The id may be wrapped in backticks, quotes or angle
 * brackets, as chat clients tend to do. Anything else parses to empty.
 */
@Experimental
public final class ChatCommandParser {

  /** Upper bound on a rejection reason taken from a chat message. */
  public static final int MAX_REASON = 1000;

  /** The bot name a command may be addressed to, with or without {@code /} or {@code @}. */
  public static final String BOT_NAME = "vegaduta";

  /** What the approver asked for. */
  public enum Action {
    APPROVE,
    REJECT
  }

  /**
   * A parsed command.
   *
   * @param action approve or reject
   * @param approvalId the request to decide
   * @param reason the rest of the line, or {@code null} when there was none
   */
  public record Command(Action action, UUID approvalId, @Nullable String reason) {
    public Command {
      Objects.requireNonNull(action, "action");
      Objects.requireNonNull(approvalId, "approvalId");
    }
  }

  private ChatCommandParser() {}

  public static Optional<Command> parse(@Nullable String text) {
    if (text == null) {
      return Optional.empty();
    }
    List<String> tokens = tokens(text);
    if (!tokens.isEmpty() && isBotMention(tokens.get(0))) {
      tokens.remove(0);
    }
    if (tokens.size() < 2) {
      return Optional.empty();
    }
    Action action = actionOf(tokens.get(0));
    if (action == null) {
      return Optional.empty();
    }
    UUID id = uuidOf(tokens.get(1));
    if (id == null) {
      return Optional.empty();
    }
    String reason =
        tokens.size() > 2
            ? Text.bounded(String.join(" ", tokens.subList(2, tokens.size())), MAX_REASON)
            : null;
    return Optional.of(new Command(action, id, reason));
  }

  /** One line explaining the accepted commands, for an "unknown command" reply. */
  public static String usage() {
    return "Usage: approve <approval-id>  |  reject <approval-id> <reason>  (reason up to "
        + MAX_REASON
        + " characters)";
  }

  private static List<String> tokens(String text) {
    List<String> tokens = new ArrayList<>();
    for (String t : text.strip().split("\\s+")) {
      if (!t.isEmpty()) {
        tokens.add(t);
      }
    }
    return tokens;
  }

  private static boolean isBotMention(String token) {
    String t = token.toLowerCase(Locale.ROOT);
    if (t.startsWith("/") || t.startsWith("@")) {
      t = t.substring(1);
    }
    if (t.endsWith(":") || t.endsWith(",")) {
      t = t.substring(0, t.length() - 1);
    }
    return t.equals(BOT_NAME);
  }

  private static @Nullable Action actionOf(String token) {
    return switch (token.toLowerCase(Locale.ROOT)) {
      case "approve", "approved" -> Action.APPROVE;
      case "reject", "rejected", "deny", "denied" -> Action.REJECT;
      default -> null;
    };
  }

  private static @Nullable UUID uuidOf(String token) {
    int start = 0;
    int end = token.length();
    while (start < end && !isUuidChar(token.charAt(start))) {
      start++;
    }
    while (end > start && !isUuidChar(token.charAt(end - 1))) {
      end--;
    }
    String candidate = token.substring(start, end);
    if (candidate.length() != 36) {
      return null;
    }
    try {
      return UUID.fromString(candidate);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static boolean isUuidChar(char c) {
    return c == '-' || (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
  }
}
