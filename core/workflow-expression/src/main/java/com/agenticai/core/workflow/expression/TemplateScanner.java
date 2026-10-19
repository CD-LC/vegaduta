package com.agenticai.core.workflow.expression;

/**
 * Finds {@code {{ ... }}} spans in a template. The closing braces are located with awareness of
 * string literals and object-literal braces, so {@code {{ '}}' }} and {@code {{ {a: 1} }}} work.
 * Unclosed openers, {@code {{}}} and blank {@code {{ }}} are plain text.
 */
final class TemplateScanner {

    /**
     * {@code start..end} covers the braces; {@code exprStart..exprEnd} is the text between them.
     */
    record Match(int start, int end, int exprStart, int exprEnd) {}

    private static final int NOT_FOUND = -1;
    private static final int TOO_LONG = -2;

    private TemplateScanner() {}

    /**
     * Next expression at or after {@code from}, or null. {@code maxExprChars} bounds the scan per
     * opener. Each call also has a total scan budget, so a template full of unmatched openers (a
     * big JSON or code body) cannot make the scan quadratic: once the budget is spent the rest is
     * treated as literal text.
     */
    static Match next(String t, int from, int maxExprChars) {
        long work = 64L * maxExprChars + 4096;
        int i = from;
        while (true) {
            int j = t.indexOf("{{", i);
            if (j < 0) {
                return null;
            }
            if (t.indexOf("}}", j + 2) < 0) {
                return null; // nothing can close any opener from here on
            }
            int close = findClose(t, j + 2, maxExprChars);
            work -= Math.min(t.length() - j, (long) maxExprChars + 3);
            if (close == TOO_LONG || close == NOT_FOUND) {
                // A stray "{{" is literal text, as it always was; a later one may still close. A
                // long span that
                // merely has "}}" somewhere after it (a JSON body, a code sample) must not become
                // an error.
                if (work <= 0) {
                    return null;
                }
                i = j + 1;
                continue;
            }
            if (t.substring(j + 2, close).isBlank()) {
                i = close + 2;
                continue;
            }
            return new Match(j, close + 2, j + 2, close);
        }
    }

    private static int findClose(String t, int from, int maxExprChars) {
        int n = t.length();
        int limit = (int) Math.min(n, (long) from + maxExprChars + 1);
        int depth = 0;
        char quote = 0;
        for (int k = from; k < limit; k++) {
            char c = t.charAt(k);
            if (quote != 0) {
                if (c == '\\') {
                    k++;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                if (depth > 0) {
                    depth--;
                } else if (k + 1 < n && t.charAt(k + 1) == '}') {
                    return k;
                }
            }
        }
        return limit < n && t.indexOf("}}", limit) >= 0 ? TOO_LONG : NOT_FOUND;
    }
}
