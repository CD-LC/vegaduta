package io.vegaduta.agent.core.text;

import org.jspecify.annotations.Nullable;

/**
 * Bounds and neutralises free text that came from outside the SDK (run attributes, chat messages,
 * tool names from a remote server, exception messages) before it is put into a log line, an audit
 * reason, a verdict or a Markdown report. Every helper is pure and never throws.
 */
public final class Text {

  /** Default bound for reasons, ids and other short free text embedded in reasons and logs. */
  public static final int SHORT = 256;

  /** Default bound for a single Markdown table cell. */
  public static final int CELL = 512;

  private static final String ELLIPSIS = "…";

  private Text() {}

  /**
   * Replaces control characters (including CR and LF) by a space and truncates to {@code max}
   * characters, marking the cut with an ellipsis. {@code null} stays {@code null}.
   */
  public static @Nullable String bounded(@Nullable String s, int max) {
    if (s == null) {
      return null;
    }
    StringBuilder sb = new StringBuilder(Math.min(s.length(), max + 1));
    for (int i = 0; i < s.length() && sb.length() <= max; i++) {
      char c = s.charAt(i);
      sb.append(c < 0x20 || c == 0x7F ? ' ' : c);
    }
    if (sb.length() > max) {
      sb.setLength(Math.max(0, max - 1));
      sb.append(ELLIPSIS);
    }
    return sb.toString();
  }

  /** {@link #bounded(String, int)} with {@link #SHORT}, mapping {@code null} to {@code ""}. */
  public static String shortText(@Nullable String s) {
    String b = bounded(s, SHORT);
    return b == null ? "" : b;
  }

  /**
   * Escapes a Markdown table cell: pipes are escaped, control characters flattened, length bounded
   * to {@link #CELL}. Empty input renders as {@code -}.
   */
  public static String cell(@Nullable String s) {
    if (s == null || s.isEmpty()) {
      return "-";
    }
    String b = bounded(s, CELL);
    return b == null ? "-" : b.replace("|", "\\|");
  }

  /**
   * Renders a name inside a Markdown code span. Backticks are replaced so the name cannot close the
   * span; control characters are flattened and the length is bounded. Pipes are left for the table
   * writer to escape ({@link #cell} does), so the result can be used in prose and in cells alike.
   */
  public static String code(@Nullable String s) {
    String b = bounded(s == null ? "" : s, CELL);
    return "`" + (b == null ? "" : b).replace("`", "'") + "`";
  }

  /** A CSV cell that cannot become a spreadsheet formula and is quoted when it needs to be. */
  public static String csv(@Nullable String s) {
    if (s == null) {
      return "";
    }
    String v = s;
    boolean formula =
        !v.isEmpty()
            && (v.charAt(0) == '='
                || v.charAt(0) == '+'
                || v.charAt(0) == '-'
                || v.charAt(0) == '@'
                || v.charAt(0) == '\t'
                || v.charAt(0) == '\r');
    if (formula) {
      v = "'" + v;
    }
    boolean quote =
        formula
            || v.indexOf(',') >= 0
            || v.indexOf('"') >= 0
            || v.indexOf('\n') >= 0
            || v.indexOf('\r') >= 0;
    return quote ? '"' + v.replace("\"", "\"\"") + '"' : v;
  }
}
