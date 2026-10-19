package io.vegaduta.agent.core.hook;

/** Minimal glob matching: {@code *} matches any run of characters, {@code ?} one character. */
final class Globs {

  private Globs() {}

  static boolean matches(String glob, String value) {
    return match(glob, 0, value, 0);
  }

  private static boolean match(String g, int gi, String v, int vi) {
    while (gi < g.length()) {
      char c = g.charAt(gi);
      if (c == '*') {
        while (gi < g.length() && g.charAt(gi) == '*') {
          gi++;
        }
        if (gi == g.length()) {
          return true;
        }
        for (int k = vi; k <= v.length(); k++) {
          if (match(g, gi, v, k)) {
            return true;
          }
        }
        return false;
      }
      if (vi >= v.length()) {
        return false;
      }
      if (c != '?' && c != v.charAt(vi)) {
        return false;
      }
      gi++;
      vi++;
    }
    return vi == v.length();
  }
}
