package io.vegaduta.agent.testkit.trace;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Field-level difference between two JSON argument objects, for human-readable messages. */
final class ArgumentDiff {

  private ArgumentDiff() {}

  /**
   * Lines such as {@code "- order: 1"}, {@code "+ order: 2"} or {@code "~ amount: 10 -> 20"} for
   * the top-level keys that differ between {@code expected} and {@code actual}. When either side is
   * not a JSON object the two texts are compared whole.
   */
  static List<String> lines(String expected, String actual) {
    List<String> out = new ArrayList<>();
    Map<String, Object> e;
    Map<String, Object> a;
    try {
      e = TraceJson.parseObject(expected);
      a = TraceJson.parseObject(actual);
    } catch (IllegalArgumentException ex) {
      if (!expected.equals(actual)) {
        out.add("- " + expected);
        out.add("+ " + actual);
      }
      return out;
    }
    TreeSet<String> keys = new TreeSet<>(e.keySet());
    keys.addAll(a.keySet());
    for (String key : keys) {
      boolean inE = e.containsKey(key);
      boolean inA = a.containsKey(key);
      String ev = TraceJson.write(e.get(key));
      String av = TraceJson.write(a.get(key));
      if (inE && !inA) {
        out.add("- " + key + ": " + ev);
      } else if (!inE && inA) {
        out.add("+ " + key + ": " + av);
      } else if (!ev.equals(av)) {
        out.add("~ " + key + ": " + ev + " -> " + av);
      }
    }
    return out;
  }

  /** The number of differing lines; the smaller, the closer the two argument objects. */
  static int distance(String expected, String actual) {
    return lines(expected, actual).size();
  }
}
