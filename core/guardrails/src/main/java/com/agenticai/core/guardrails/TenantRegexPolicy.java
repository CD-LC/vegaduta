package com.agenticai.core.guardrails;

/**
 * SEC-028: the rules for a regex a tenant types into the guardrail authoring
 * screen, which this server then runs on every inbound message (including
 * anonymous share-link chat) and every tool result.
 *
 * <p>Tenant patterns are compiled with RE2/J ({@code com.google.re2j}), whose
 * matching time is linear in the input: there is no backtracking, so the
 * classic catastrophic cases such as {@code (a+)+$} cannot hang a turn. The
 * price is that RE2 does not support backreferences, lookahead/lookbehind,
 * possessive quantifiers or atomic groups; those are rejected at save time
 * with a readable message.
 *
 * <p>RE2/J has one remaining blow-up of its own: nested counted repetition
 * such as {@code ((a{1000}){1000}){1000}} is expanded at COMPILE time and runs
 * the JVM out of heap. {@link #estimateProgramSize} bounds that before RE2/J
 * ever sees the pattern, both at save time and when stored rows are compiled.
 */
final class TenantRegexPolicy {

    /** Same cap as {@code GuardrailRuleRequest#pattern}'s {@code @Size}; enforced again here so it holds without {@code @Valid}. */
    static final int MAX_PATTERN_LENGTH = 1000;

    /** Upper bound on the estimated compiled program size. {@code [0-9]{1,1000}} is ~1k; {@code (a{100}){100}} (~10.3k) is already over. */
    static final long MAX_PROGRAM_SIZE = 10_000L;

    private static final long SATURATED = Long.MAX_VALUE / 4;

    private TenantRegexPolicy() {
    }

    /**
     * Validates a pattern at authoring time.
     *
     * @throws IllegalArgumentException with a user-facing message (mapped to 400 by the global handler)
     */
    static void validateForAuthoring(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            throw new IllegalArgumentException("A guardrail pattern is required.");
        }
        if (pattern.length() > MAX_PATTERN_LENGTH) {
            throw new IllegalArgumentException(
                "That pattern is too long (" + pattern.length() + " characters; the limit is " + MAX_PATTERN_LENGTH + ").");
        }
        if (estimateProgramSize(pattern) > MAX_PROGRAM_SIZE) {
            throw new IllegalArgumentException(
                "That pattern expands too much (nested or very large {n,m} repetition counts). "
                    + "Simplify the repetition counts.");
        }
        try {
            com.google.re2j.Pattern.compile(pattern, com.google.re2j.Pattern.CASE_INSENSITIVE);
        } catch (com.google.re2j.PatternSyntaxException e) {
            throw new IllegalArgumentException(
                "That pattern is not supported: " + e.getDescription()
                    + ". Guardrail patterns use the linear-time RE2 syntax, which has no backreferences, "
                    + "lookahead/lookbehind, possessive quantifiers or atomic groups.");
        }
    }

    /**
     * Compiles a stored pattern with RE2/J, or returns null when RE2/J cannot
     * take it (a legacy row saved before this policy existed) so the caller can
     * fall back to the budgeted java.util.regex path.
     *
     * @throws IllegalArgumentException when the pattern is too long or expands too much for ANY engine to be safe
     */
    static com.google.re2j.Pattern compileLinearOrNull(String pattern) {
        if (pattern.length() > MAX_PATTERN_LENGTH) {
            throw new IllegalArgumentException("pattern longer than " + MAX_PATTERN_LENGTH + " characters");
        }
        if (estimateProgramSize(pattern) > MAX_PROGRAM_SIZE) {
            throw new IllegalArgumentException("pattern repetition expands beyond " + MAX_PROGRAM_SIZE);
        }
        try {
            return com.google.re2j.Pattern.compile(pattern, com.google.re2j.Pattern.CASE_INSENSITIVE);
        } catch (com.google.re2j.PatternSyntaxException e) {
            return null;
        }
    }

    /**
     * Conservative estimate of how many instructions the pattern compiles to,
     * where a counted repetition {@code X{n,m}} costs {@code size(X) * (max(n,m)+1)}.
     * Over-estimates (alternation is summed, escapes count as one) but never
     * under-estimates nested repetition, which is the only thing it has to catch.
     */
    static long estimateProgramSize(String pattern) {
        int[] pos = { 0 };
        long total = 0;
        // A stray ')' is a syntax error the compiler reports; skip it so the estimate still terminates.
        while (pos[0] < pattern.length()) {
            total = add(total, alternation(pattern, pos));
            if (pos[0] < pattern.length()) {
                pos[0]++;
            }
        }
        return total;
    }

    private static long alternation(String p, int[] pos) {
        long size = 0;
        while (pos[0] < p.length() && p.charAt(pos[0]) != ')') {
            char c = p.charAt(pos[0]);
            if (c == '|') {
                pos[0]++;
                size = add(size, 1);
                continue;
            }
            size = add(size, item(p, pos));
        }
        return size;
    }

    private static long item(String p, int[] pos) {
        long size;
        char c = p.charAt(pos[0]);
        if (c == '(') {
            pos[0]++;
            size = add(alternation(p, pos), 1);
            if (pos[0] < p.length()) {
                pos[0]++; // ')'
            }
        } else if (c == '[') {
            skipClass(p, pos);
            size = 1;
        } else if (c == '\\') {
            size = skipEscape(p, pos);
        } else {
            pos[0]++;
            size = 1;
        }
        // Quantifiers (possibly stacked, which RE2 rejects later anyway).
        while (pos[0] < p.length()) {
            char q = p.charAt(pos[0]);
            if (q == '*' || q == '+' || q == '?') {
                pos[0]++;
                size = add(size, 1);
            } else if (q == '{') {
                long repeat = parseRepeat(p, pos);
                if (repeat < 0) {
                    break; // literal '{' - the next item() call consumes it
                }
                size = mul(size, repeat + 1);
            } else {
                break;
            }
        }
        return size;
    }

    private static void skipClass(String p, int[] pos) {
        int i = pos[0] + 1;
        if (i < p.length() && p.charAt(i) == '^') {
            i++;
        }
        if (i < p.length() && p.charAt(i) == ']') {
            i++;
        }
        while (i < p.length() && p.charAt(i) != ']') {
            char c = p.charAt(i);
            if (c == '\\') {
                i += 2;
            } else if (c == '[' && i + 1 < p.length() && p.charAt(i + 1) == ':') {
                int close = p.indexOf(":]", i + 2);
                i = close < 0 ? i + 1 : close + 2;
            } else {
                i++;
            }
        }
        pos[0] = Math.min(p.length(), i + 1);
    }

    private static long skipEscape(String p, int[] pos) {
        int i = pos[0] + 1;
        if (i >= p.length()) {
            pos[0] = i;
            return 1;
        }
        char e = p.charAt(i);
        if (e == 'Q') {
            int end = p.indexOf("\\E", i + 1);
            int stop = end < 0 ? p.length() : end + 2;
            pos[0] = stop;
            return Math.max(1, stop - i);
        }
        i++;
        if ((e == 'p' || e == 'P' || e == 'x') && i < p.length() && p.charAt(i) == '{') {
            int close = p.indexOf('}', i);
            i = close < 0 ? p.length() : close + 1;
        }
        pos[0] = i;
        return 1;
    }

    /** @return max(n, m) for a valid {@code {n}}, {@code {n,}} or {@code {n,m}} at pos (advancing past it), else -1 without advancing. */
    private static long parseRepeat(String p, int[] pos) {
        int i = pos[0] + 1;
        long n = 0;
        int digits = 0;
        while (i < p.length() && Character.isDigit(p.charAt(i))) {
            n = digits < 9 ? n * 10 + (p.charAt(i) - '0') : n;
            digits++;
            i++;
        }
        if (digits == 0 || i >= p.length()) {
            return -1;
        }
        long max = n;
        if (p.charAt(i) == ',') {
            i++;
            long m = 0;
            int mDigits = 0;
            while (i < p.length() && Character.isDigit(p.charAt(i))) {
                m = mDigits < 9 ? m * 10 + (p.charAt(i) - '0') : m;
                mDigits++;
                i++;
            }
            if (mDigits > 0) {
                max = Math.max(n, m);
            }
        }
        if (i >= p.length() || p.charAt(i) != '}') {
            return -1;
        }
        pos[0] = i + 1;
        return max;
    }

    private static long add(long a, long b) {
        long r = a + b;
        return r < 0 || r > SATURATED ? SATURATED : r;
    }

    private static long mul(long a, long b) {
        if (a == 0 || b == 0) {
            return 0;
        }
        return a > SATURATED / b ? SATURATED : Math.min(SATURATED, a * b);
    }
}
