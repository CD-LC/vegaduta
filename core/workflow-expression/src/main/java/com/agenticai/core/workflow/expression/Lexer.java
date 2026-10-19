package com.agenticai.core.workflow.expression;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns expression source into tokens. Every token carries its source offsets for error reporting.
 */
final class Lexer {

    enum Kind {
        NUM,
        STR,
        IDENT,
        PUNCT,
        EOF
    }

    record Token(Kind kind, String text, Object value, int start, int end) {
        String describe() {
            return kind == Kind.EOF ? "end of expression" : "'" + text + "'";
        }
    }

    private static final String[] TWO_CHAR = {"?.", "??", "||", "&&", "==", "!=", "<=", ">=", "=>"};
    private static final String ONE_CHAR = "()[]{},:.?+-*/%!<>";

    private Lexer() {}

    static List<Token> lex(String src) {
        List<Token> out = new ArrayList<>();
        int n = src.length();
        int i = 0;
        while (i < n) {
            char c = src.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c >= '0' && c <= '9') {
                i = number(src, i, out);
            } else if (c == '"' || c == '\'') {
                i = string(src, i, out);
            } else if (isIdentStart(c)) {
                int j = i + 1;
                while (j < n && isIdentPart(src.charAt(j))) {
                    j++;
                }
                out.add(new Token(Kind.IDENT, src.substring(i, j), null, i, j));
                i = j;
            } else {
                i = punct(src, i, out);
            }
        }
        out.add(new Token(Kind.EOF, "", null, n, n));
        return out;
    }

    private static boolean isIdentStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c == '$';
    }

    private static boolean isIdentPart(char c) {
        return isIdentStart(c) || (c >= '0' && c <= '9');
    }

    private static int punct(String src, int i, List<Token> out) {
        for (String two : TWO_CHAR) {
            if (src.startsWith(two, i)) {
                // "a ?.5 : 1" is a ternary with a number, not optional chaining.
                if (two.equals("?.")
                        && i + 2 < src.length()
                        && Character.isDigit(src.charAt(i + 2))) {
                    continue;
                }
                out.add(new Token(Kind.PUNCT, two, null, i, i + 2));
                return i + 2;
            }
        }
        char c = src.charAt(i);
        if (ONE_CHAR.indexOf(c) >= 0) {
            out.add(new Token(Kind.PUNCT, String.valueOf(c), null, i, i + 1));
            return i + 1;
        }
        throw new ExpressionException("Unexpected character '" + c + "'", i, i + 1);
    }

    private static int number(String src, int start, List<Token> out) {
        int n = src.length();
        int i = start;
        while (i < n && Character.isDigit(src.charAt(i))) {
            i++;
        }
        boolean floating = false;
        if (i + 1 < n && src.charAt(i) == '.' && Character.isDigit(src.charAt(i + 1))) {
            floating = true;
            i++;
            while (i < n && Character.isDigit(src.charAt(i))) {
                i++;
            }
        }
        if (i < n && (src.charAt(i) == 'e' || src.charAt(i) == 'E')) {
            int j = i + 1;
            if (j < n && (src.charAt(j) == '+' || src.charAt(j) == '-')) {
                j++;
            }
            if (j < n && Character.isDigit(src.charAt(j))) {
                floating = true;
                while (j < n && Character.isDigit(src.charAt(j))) {
                    j++;
                }
                i = j;
            }
        }
        if (i < n && isIdentPart(src.charAt(i))) {
            throw new ExpressionException("Invalid number", start, i + 1);
        }
        String text = src.substring(start, i);
        Object value;
        if (floating) {
            value = Double.parseDouble(text);
        } else {
            try {
                value = Long.parseLong(text);
            } catch (NumberFormatException e) {
                value = Double.parseDouble(text);
            }
        }
        out.add(new Token(Kind.NUM, text, value, start, i));
        return i;
    }

    private static int string(String src, int start, List<Token> out) {
        char quote = src.charAt(start);
        StringBuilder sb = new StringBuilder();
        int n = src.length();
        int i = start + 1;
        while (i < n) {
            char c = src.charAt(i);
            if (c == quote) {
                out.add(
                        new Token(
                                Kind.STR,
                                src.substring(start, i + 1),
                                sb.toString(),
                                start,
                                i + 1));
                return i + 1;
            }
            if (c == '\\') {
                if (i + 1 >= n) {
                    break;
                }
                char e = src.charAt(i + 1);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case '\\', '\'', '"', '/' -> sb.append(e);
                    case 'u' -> {
                        if (i + 6 > n) {
                            throw new ExpressionException("Invalid \\u escape", i, n);
                        }
                        try {
                            sb.append((char) Integer.parseInt(src.substring(i + 2, i + 6), 16));
                        } catch (NumberFormatException ex) {
                            throw new ExpressionException("Invalid \\u escape", i, i + 6);
                        }
                        i += 4;
                    }
                    default ->
                            throw new ExpressionException("Unknown escape '\\" + e + "'", i, i + 2);
                }
                i += 2;
            } else {
                sb.append(c);
                i++;
            }
        }
        throw new ExpressionException("Unterminated string", start, n);
    }
}
