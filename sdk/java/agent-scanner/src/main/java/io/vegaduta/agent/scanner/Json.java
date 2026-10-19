package io.vegaduta.agent.scanner;

import java.util.Collection;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Minimal JSON writer for the report: objects (insertion-ordered maps), arrays, strings, numbers,
 * booleans and null. No library, no parsing.
 */
final class Json {

  private Json() {}

  static String write(@Nullable Object value) {
    StringBuilder sb = new StringBuilder();
    write(value, sb);
    return sb.toString();
  }

  private static void write(@Nullable Object value, StringBuilder sb) {
    if (value == null) {
      sb.append("null");
    } else if (value instanceof String s) {
      sb.append('"').append(escape(s)).append('"');
    } else if (value instanceof Number || value instanceof Boolean) {
      sb.append(value);
    } else if (value instanceof Enum<?> e) {
      sb.append('"').append(escape(e.name())).append('"');
    } else if (value instanceof Map<?, ?> map) {
      sb.append('{');
      boolean first = true;
      for (Map.Entry<?, ?> e : map.entrySet()) {
        if (!first) {
          sb.append(',');
        }
        first = false;
        sb.append('"').append(escape(String.valueOf(e.getKey()))).append("\":");
        write(e.getValue(), sb);
      }
      sb.append('}');
    } else if (value instanceof Collection<?> items) {
      sb.append('[');
      boolean first = true;
      for (Object item : items) {
        if (!first) {
          sb.append(',');
        }
        first = false;
        write(item, sb);
      }
      sb.append(']');
    } else {
      sb.append('"').append(escape(String.valueOf(value))).append('"');
    }
  }

  static String escape(String s) {
    StringBuilder sb = new StringBuilder(s.length() + 8);
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
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
}
