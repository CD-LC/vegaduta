package io.vegaduta.agent.core.hook;

import com.google.re2j.Matcher;
import com.google.re2j.Pattern;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Enough JSON to talk to a webhook and persist flat attribute maps without a JSON library: writes
 * flat objects of strings, reads one top-level string field, and reads a flat object of strings
 * back. Not a general parser: nested values are ignored.
 */
public final class JsonLite {

  private static final Pattern PAIR =
      Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

  private JsonLite() {}

  public static String writeObject(Map<String, String> fields) {
    StringBuilder sb = new StringBuilder("{");
    boolean first = true;
    for (Map.Entry<String, String> e : fields.entrySet()) {
      if (!first) {
        sb.append(',');
      }
      first = false;
      sb.append('"')
          .append(escape(e.getKey()))
          .append("\":\"")
          .append(escape(e.getValue()))
          .append('"');
    }
    return sb.append('}').toString();
  }

  /** Every top-level {@code "key":"string"} pair, in order. */
  public static Map<String, String> readFlatObject(@Nullable String json) {
    Map<String, String> map = new LinkedHashMap<>();
    if (json == null || json.isBlank()) {
      return map;
    }
    Matcher m = PAIR.matcher(json);
    while (m.find()) {
      map.put(unescape(m.group(1)), unescape(m.group(2)));
    }
    return map;
  }

  public static String escape(String s) {
    StringBuilder sb = new StringBuilder(s.length() + 8);
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        case '\b' -> sb.append("\\b");
        case '\f' -> sb.append("\\f");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    return sb.toString();
  }

  /** The value of a top-level string field, unescaped, or {@code null} when absent. */
  public static @Nullable String readStringField(String json, String field) {
    Pattern p =
        Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    Matcher m = p.matcher(json);
    return m.find() ? unescape(m.group(1)) : null;
  }

  static String unescape(String s) {
    StringBuilder sb = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c != '\\' || i + 1 >= s.length()) {
        sb.append(c);
        continue;
      }
      char n = s.charAt(++i);
      switch (n) {
        case 'n' -> sb.append('\n');
        case 'r' -> sb.append('\r');
        case 't' -> sb.append('\t');
        case 'b' -> sb.append('\b');
        case 'f' -> sb.append('\f');
        case 'u' -> {
          if (i + 4 < s.length()) {
            sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
            i += 4;
          }
        }
        default -> sb.append(n);
      }
    }
    return sb.toString();
  }
}
