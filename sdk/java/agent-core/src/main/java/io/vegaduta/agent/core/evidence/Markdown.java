package io.vegaduta.agent.core.evidence;

import io.vegaduta.agent.api.audit.AuditEvent;
import io.vegaduta.agent.core.text.Text;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Markdown helpers shared by the exporters. */
final class Markdown {

  private Markdown() {}

  static void table(StringBuilder sb, List<String> header, List<List<String>> rows) {
    row(sb, header);
    sb.append('|');
    for (int i = 0; i < header.size(); i++) {
      sb.append(" --- |");
    }
    sb.append('\n');
    for (List<String> r : rows) {
      row(sb, r);
    }
    sb.append('\n');
  }

  private static void row(StringBuilder sb, List<String> cells) {
    sb.append('|');
    for (String c : cells) {
      sb.append(' ').append(cell(c)).append(" |");
    }
    sb.append('\n');
  }

  /**
   * Pipes and newlines would break the table; escape or flatten them, and bound the length (tool
   * exceptions and guardrail violation lists land in {@code detail} verbatim). {@link #table}
   * applies this to every cell, so callers pass raw strings.
   */
  static String cell(@Nullable String s) {
    return Text.cell(s);
  }

  /** A raw cell value for {@link #table}: {@code null} renders as {@code -}; no escaping here. */
  static String text(@Nullable String s) {
    return s == null ? "-" : s;
  }

  static String shortId(AuditEvent e) {
    String id = e.id().toString();
    return id.length() > 8 ? id.substring(0, 8) : id;
  }

  static String percent(long part, long whole) {
    if (whole == 0) {
      return "n/a";
    }
    return String.format(java.util.Locale.ROOT, "%.1f%%", 100.0 * part / whole);
  }

  static double ratio(long part, long whole) {
    return whole == 0 ? 0.0 : (double) part / whole;
  }

  static String plural(long n, String noun) {
    return n + " " + noun + (n == 1 ? "" : "s");
  }
}
