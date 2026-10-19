package com.agenticai.core.guardrails.toolargs;

/**
 * Case-sensitive glob match for tool names: {@code *} is any run of characters (including none), {@code ?}
 * is exactly one. Iterative with a single backtrack point, so it is O(n*m) at worst and cannot be driven
 * into exponential work by a hostile pattern. Pure.
 */
public final class ToolArgGlob {

    private ToolArgGlob() {
    }

    public static boolean matches(String glob, String name) {
        if (glob == null || name == null) {
            return false;
        }
        int g = 0;
        int s = 0;
        int starG = -1;
        int starS = -1;
        while (s < name.length()) {
            if (g < glob.length() && (glob.charAt(g) == '?' || glob.charAt(g) == name.charAt(s))) {
                g++;
                s++;
            } else if (g < glob.length() && glob.charAt(g) == '*') {
                starG = g++;
                starS = s;
            } else if (starG >= 0) {
                g = starG + 1;
                s = ++starS;
            } else {
                return false;
            }
        }
        while (g < glob.length() && glob.charAt(g) == '*') {
            g++;
        }
        return g == glob.length();
    }
}
