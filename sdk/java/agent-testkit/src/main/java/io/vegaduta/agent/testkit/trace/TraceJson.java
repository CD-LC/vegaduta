package io.vegaduta.agent.testkit.trace;

import io.vegaduta.agent.api.Experimental;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * A minimal, dependency-free JSON reader and writer for traces.
 *
 * <p>Values are modelled as {@link Map} (object, keys sorted), {@link List} (array), {@link
 * String}, {@link BigDecimal} (number), {@link Boolean} and {@code null}. {@link
 * #canonicalize(String)} produces the stable form used to match replayed calls: object keys sorted,
 * no whitespace, numbers normalised ({@code 1.0} and {@code 1} are the same call).
 */
@Experimental
public final class TraceJson {

  private TraceJson() {}

  /**
   * The canonical form of a JSON text. Invalid JSON is returned trimmed, unchanged, so a tool whose
   * arguments are not JSON can still be recorded and replayed.
   */
  public static String canonicalize(@Nullable String json) {
    if (json == null) {
      return "";
    }
    try {
      return write(parse(json));
    } catch (IllegalArgumentException e) {
      return json.trim();
    }
  }

  /** Parses a JSON text. Throws {@link IllegalArgumentException} on malformed input. */
  public static @Nullable Object parse(String json) {
    Parser parser = new Parser(json);
    Object value = parser.readValue();
    parser.skipWhitespace();
    if (parser.pos != json.length()) {
      throw parser.error("trailing characters");
    }
    return value;
  }

  /** Writes a value as compact JSON, map keys in the map's iteration order. */
  public static String write(@Nullable Object value) {
    StringBuilder sb = new StringBuilder();
    writeTo(sb, value);
    return sb.toString();
  }

  /** The JSON string literal for {@code s}, with quotes. */
  public static String quote(String s) {
    StringBuilder sb = new StringBuilder(s.length() + 2);
    writeString(sb, s);
    return sb.toString();
  }

  /** Reads an object text into a map, or throws {@link IllegalArgumentException}. */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> parseObject(String json) {
    Object value = parse(json);
    if (value instanceof Map<?, ?> map) {
      return (Map<String, Object>) map;
    }
    throw new IllegalArgumentException("expected a JSON object but got: " + json);
  }

  @SuppressWarnings("unchecked")
  private static void writeTo(StringBuilder sb, @Nullable Object value) {
    if (value == null) {
      sb.append("null");
    } else if (value instanceof String s) {
      writeString(sb, s);
    } else if (value instanceof Boolean b) {
      sb.append(b ? "true" : "false");
    } else if (value instanceof BigDecimal d) {
      sb.append(d.signum() == 0 ? "0" : d.stripTrailingZeros().toPlainString());
    } else if (value instanceof Number n) {
      sb.append(n);
    } else if (value instanceof Map<?, ?> map) {
      // Iteration order is kept: parsed objects are TreeMaps (sorted, hence canonical) while the
      // trace writer passes LinkedHashMaps so file lines stay in a readable field order.
      sb.append('{');
      boolean first = true;
      for (Map.Entry<?, ?> e : map.entrySet()) {
        if (!first) {
          sb.append(',');
        }
        first = false;
        writeString(sb, String.valueOf(e.getKey()));
        sb.append(':');
        writeTo(sb, e.getValue());
      }
      sb.append('}');
    } else if (value instanceof List<?> list) {
      sb.append('[');
      boolean first = true;
      for (Object item : (List<Object>) list) {
        if (!first) {
          sb.append(',');
        }
        first = false;
        writeTo(sb, item);
      }
      sb.append(']');
    } else {
      writeString(sb, String.valueOf(value));
    }
  }

  private static void writeString(StringBuilder sb, String s) {
    sb.append('"');
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
    sb.append('"');
  }

  private static final class Parser {
    /** Nesting bound: a model can emit {@code [[[[...} tens of thousands deep. */
    private static final int MAX_DEPTH = 256;

    /** Bound on a number literal's exponent and digits, so canonicalisation cannot OOM. */
    private static final int MAX_NUMBER_DIGITS = 400;

    private final String s;
    private int pos;
    private int depth;

    Parser(String s) {
      this.s = s;
    }

    @Nullable Object readValue() {
      skipWhitespace();
      if (pos >= s.length()) {
        throw error("unexpected end of input");
      }
      char c = s.charAt(pos);
      return switch (c) {
        case '{' -> readObject();
        case '[' -> readArray();
        case '"' -> readString();
        case 't' -> readLiteral("true", Boolean.TRUE);
        case 'f' -> readLiteral("false", Boolean.FALSE);
        case 'n' -> readLiteral("null", null);
        default -> {
          if (c == '-' || (c >= '0' && c <= '9')) {
            yield readNumber();
          }
          throw error("unexpected character '" + c + "'");
        }
      };
    }

    private void enter() {
      if (++depth > MAX_DEPTH) {
        throw error("nesting deeper than " + MAX_DEPTH);
      }
    }

    private Map<String, Object> readObject() {
      enter();
      Map<String, Object> map = new TreeMap<>();
      pos++; // {
      skipWhitespace();
      if (peek() == '}') {
        pos++;
        depth--;
        return map;
      }
      while (true) {
        skipWhitespace();
        if (peek() != '"') {
          throw error("expected a string key");
        }
        String key = readString();
        skipWhitespace();
        expect(':');
        Object value = readValue();
        map.put(key, value);
        skipWhitespace();
        char c = next();
        if (c == '}') {
          depth--;
          return map;
        }
        if (c != ',') {
          throw error("expected ',' or '}'");
        }
      }
    }

    private List<Object> readArray() {
      enter();
      List<Object> list = new ArrayList<>();
      pos++; // [
      skipWhitespace();
      if (peek() == ']') {
        pos++;
        depth--;
        return list;
      }
      while (true) {
        list.add(readValue());
        skipWhitespace();
        char c = next();
        if (c == ']') {
          depth--;
          return list;
        }
        if (c != ',') {
          throw error("expected ',' or ']'");
        }
      }
    }

    private String readString() {
      pos++; // opening quote
      StringBuilder sb = new StringBuilder();
      while (true) {
        if (pos >= s.length()) {
          throw error("unterminated string");
        }
        char c = s.charAt(pos++);
        if (c == '"') {
          return sb.toString();
        }
        if (c != '\\') {
          sb.append(c);
          continue;
        }
        if (pos >= s.length()) {
          throw error("unterminated escape");
        }
        char e = s.charAt(pos++);
        switch (e) {
          case '"' -> sb.append('"');
          case '\\' -> sb.append('\\');
          case '/' -> sb.append('/');
          case 'b' -> sb.append('\b');
          case 'f' -> sb.append('\f');
          case 'n' -> sb.append('\n');
          case 'r' -> sb.append('\r');
          case 't' -> sb.append('\t');
          case 'u' -> {
            if (pos + 4 > s.length()) {
              throw error("truncated \\u escape");
            }
            try {
              sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
            } catch (NumberFormatException ex) {
              throw error("bad \\u escape");
            }
            pos += 4;
          }
          default -> throw error("bad escape '\\" + e + "'");
        }
      }
    }

    private BigDecimal readNumber() {
      int start = pos;
      if (peek() == '-') {
        pos++;
      }
      while (pos < s.length()) {
        char c = s.charAt(pos);
        if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
          pos++;
        } else {
          break;
        }
      }
      String literal = s.substring(start, pos);
      if (literal.length() > MAX_NUMBER_DIGITS) {
        throw error("number literal too long");
      }
      try {
        BigDecimal value = new BigDecimal(literal);
        if (Math.abs(value.scale()) > MAX_NUMBER_DIGITS || value.precision() > MAX_NUMBER_DIGITS) {
          throw error("number out of canonical range");
        }
        return value;
      } catch (NumberFormatException e) {
        throw error("bad number");
      }
    }

    private @Nullable Object readLiteral(String literal, @Nullable Object value) {
      if (!s.startsWith(literal, pos)) {
        throw error("expected " + literal);
      }
      pos += literal.length();
      return value;
    }

    void skipWhitespace() {
      while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
        pos++;
      }
    }

    private char peek() {
      if (pos >= s.length()) {
        throw error("unexpected end of input");
      }
      return s.charAt(pos);
    }

    private char next() {
      char c = peek();
      pos++;
      return c;
    }

    private void expect(char c) {
      if (next() != c) {
        throw error("expected '" + c + "'");
      }
    }

    IllegalArgumentException error(String message) {
      return new IllegalArgumentException("invalid JSON at offset " + pos + ": " + message);
    }
  }
}
