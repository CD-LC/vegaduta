package io.vegaduta.agent.core.evidence;

import io.vegaduta.agent.core.hook.JsonLite;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A tiny JSON object writer for the exporters: strings, numbers, booleans, string arrays and nested
 * objects, with proper escaping. Write-only; the SDK has no JSON parser and does not need one here.
 * Keys are unique: writing a key twice overwrites the earlier value in place, so the output is
 * always a valid object for strict parsers.
 */
final class EvidenceJson {

  private final Map<String, String> fields = new LinkedHashMap<>();

  EvidenceJson() {}

  static EvidenceJson object() {
    return new EvidenceJson();
  }

  static String quote(String s) {
    return '"' + JsonLite.escape(s) + '"';
  }

  EvidenceJson put(String key, @Nullable String value) {
    return raw(key, value == null ? "null" : quote(value));
  }

  EvidenceJson put(String key, long value) {
    return raw(key, Long.toString(value));
  }

  /** A number, or JSON {@code null} when absent. */
  EvidenceJson put(String key, java.util.OptionalLong value) {
    return raw(key, value.isPresent() ? Long.toString(value.getAsLong()) : "null");
  }

  EvidenceJson put(String key, double value) {
    return raw(key, Double.isFinite(value) ? Double.toString(value) : "null");
  }

  EvidenceJson put(String key, boolean value) {
    return raw(key, Boolean.toString(value));
  }

  EvidenceJson put(String key, EvidenceJson nested) {
    return raw(key, nested.toString());
  }

  EvidenceJson putStrings(String key, Collection<String> values) {
    StringBuilder arr = new StringBuilder("[");
    boolean f = true;
    for (String v : values) {
      if (!f) {
        arr.append(',');
      }
      f = false;
      arr.append(quote(v));
    }
    return raw(key, arr.append(']').toString());
  }

  EvidenceJson putObjects(String key, Collection<EvidenceJson> values) {
    StringBuilder arr = new StringBuilder("[");
    boolean f = true;
    for (EvidenceJson v : values) {
      if (!f) {
        arr.append(',');
      }
      f = false;
      arr.append(v);
    }
    return raw(key, arr.append(']').toString());
  }

  /** Every entry as a string field. */
  EvidenceJson putAll(Map<String, String> fields) {
    fields.forEach(this::put);
    return this;
  }

  private EvidenceJson raw(String key, String json) {
    fields.put(key, json);
    return this;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("{");
    boolean first = true;
    for (Map.Entry<String, String> e : fields.entrySet()) {
      if (!first) {
        sb.append(',');
      }
      first = false;
      sb.append(quote(e.getKey())).append(':').append(e.getValue());
    }
    return sb.append('}').toString();
  }
}
