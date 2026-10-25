package com.agenticai.core.agents.tools.desktop;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The placeholder grammar a desktop recipe may contain - and the ONLY way a recipe refers to
 * anything variable or secret, so a stored recipe can never hold a credential or a user-supplied
 * value:
 *
 * <ul>
 *   <li>{@code {{name}}} - a parameter the caller supplies per run (letters/digits/underscore,
 *       starts with a letter, at most 32 chars; {@code secret} is reserved);
 *   <li>{@code {{secret.NAME}}} - a vault secret, resolved server-side at replay and never shown to
 *       the model.
 * </ul>
 *
 * There is deliberately no {@code {{totp.NAME}}} here: the desktop step vocabulary types into
 * whatever has focus, not a named field, so the platform cannot tell a code field from any other
 * text. The {@code secret.} syntax is the same {@code SecretReferenceResolver} documents for the
 * rest of the platform. Mirrors {@code BrowserRecipePlaceholders}.
 */
final class DesktopRecipePlaceholders {

    enum Kind {
        PARAM,
        SECRET
    }

    /** One placeholder occurrence: its kind and bare name (no {@code secret.} prefix). */
    record Token(Kind kind, String name, String raw) {}

    private static final Pattern TOKEN =
            Pattern.compile(
                    "\\{\\{\\s*(?:secret\\.([A-Z][A-Z0-9_]{0,63})|([A-Za-z][A-Za-z0-9_]{0,31}))\\s*\\}\\}");

    static final Pattern PARAM_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,31}");

    private DesktopRecipePlaceholders() {}

    static boolean isReservedParamName(String name) {
        return "secret".equalsIgnoreCase(name);
    }

    /** Every well-formed placeholder in {@code text}, in order. */
    static List<Token> tokens(String text) {
        List<Token> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        Matcher m = TOKEN.matcher(text);
        while (m.find()) {
            if (m.group(1) != null) {
                out.add(new Token(Kind.SECRET, m.group(1), m.group(0)));
            } else {
                out.add(new Token(Kind.PARAM, m.group(2), m.group(0)));
            }
        }
        return out;
    }

    /**
     * True when {@code text} contains an opening or closing double brace that is NOT part of a
     * well-formed placeholder.
     */
    static boolean hasMalformedBraces(String text) {
        if (text == null) {
            return false;
        }
        String stripped = TOKEN.matcher(text).replaceAll("");
        return stripped.contains("{{") || stripped.contains("}}");
    }

    /** {@code text} with every placeholder removed - what is left is literal content. */
    static String literalPart(String text) {
        return text == null ? "" : TOKEN.matcher(text).replaceAll("");
    }

    /** True when {@code text} is exactly one placeholder (surrounding whitespace ignored). */
    static boolean isSinglePlaceholder(String text) {
        if (text == null) {
            return false;
        }
        Matcher m = TOKEN.matcher(text.strip());
        return m.matches();
    }

    /**
     * Replaces each placeholder with {@code resolver}'s result (never re-scanned, so a value cannot
     * inject another token).
     */
    static String substitute(String text, Function<Token, String> resolver) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Matcher m = TOKEN.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            Token token =
                    m.group(1) != null
                            ? new Token(Kind.SECRET, m.group(1), m.group(0))
                            : new Token(Kind.PARAM, m.group(2), m.group(0));
            m.appendReplacement(out, Matcher.quoteReplacement(resolver.apply(token)));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** A bare {@code {{name}}} placeholder for parameter {@code name}. */
    static String param(String name) {
        return "{{" + name + "}}";
    }
}
