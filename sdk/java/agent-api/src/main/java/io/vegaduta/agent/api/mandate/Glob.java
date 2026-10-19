package io.vegaduta.agent.api.mandate;

/**
 * Minimal glob matching for tool names: {@code *} matches any run of characters. Linear-time
 * two-pointer algorithm with backtracking to the last star only, so a hostile pattern such as
 * {@code *a*a*a*a*a*c} cannot stall a gate check.
 */
final class Glob {

  private Glob() {}

  static boolean matches(String glob, String value) {
    int gi = 0;
    int vi = 0;
    int starGi = -1;
    int starVi = -1;
    while (vi < value.length()) {
      if (gi < glob.length() && glob.charAt(gi) == '*') {
        starGi = gi++;
        starVi = vi;
      } else if (gi < glob.length() && glob.charAt(gi) == value.charAt(vi)) {
        gi++;
        vi++;
      } else if (starGi >= 0) {
        gi = starGi + 1;
        vi = ++starVi;
      } else {
        return false;
      }
    }
    while (gi < glob.length() && glob.charAt(gi) == '*') {
      gi++;
    }
    return gi == glob.length();
  }
}
