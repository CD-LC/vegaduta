package com.agenticai.core.workflow.expression;

import static com.agenticai.core.workflow.expression.BuiltInArgs.arr;
import static com.agenticai.core.workflow.expression.BuiltInArgs.clampInt;
import static com.agenticai.core.workflow.expression.BuiltInArgs.def;
import static com.agenticai.core.workflow.expression.BuiltInArgs.err;
import static com.agenticai.core.workflow.expression.BuiltInArgs.get;
import static com.agenticai.core.workflow.expression.BuiltInArgs.has;
import static com.agenticai.core.workflow.expression.BuiltInArgs.lng;
import static com.agenticai.core.workflow.expression.BuiltInArgs.str;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The String built-ins of {@link BuiltIns}: case, split/join, replace, padding and encodings. */
final class StringBuiltIns {

    private StringBuiltIns() {}

    /**
     * JDK case mapping is quadratic for some characters (U+00DF upper, U+0130 lower: 200k chars ran
     * for close to a minute) and cannot be interrupted, so the wall cap would never fire. Map in
     * small chunks and bill each chunk to the budget. Chunks end after whitespace where possible so
     * the context-sensitive final-sigma rule gives the same result as mapping the whole string.
     */
    private static String caseMap(Evaluator ev, String s, boolean upper) {
        final int chunk = 2048;
        if (s.length() <= chunk) {
            return upper ? s.toUpperCase(Locale.ROOT) : s.toLowerCase(Locale.ROOT);
        }
        StringBuilder out = new StringBuilder(s.length());
        int pos = 0;
        while (pos < s.length()) {
            int end = Math.min(s.length(), pos + chunk);
            if (end < s.length()) {
                int back = end;
                while (back > pos + chunk - 512 && !Character.isWhitespace(s.charAt(back - 1))) {
                    back--;
                }
                if (back > pos + chunk - 512) {
                    end = back;
                } else if (Character.isHighSurrogate(s.charAt(end - 1))) {
                    end--;
                }
            }
            ev.budget().tick();
            String part = s.substring(pos, end);
            out.append(upper ? part.toUpperCase(Locale.ROOT) : part.toLowerCase(Locale.ROOT));
            pos = end;
        }
        return out.toString();
    }

    static void register(Map<String, BuiltIn> m) {
        String c = "String";
        def(
                m,
                "lower",
                c,
                1,
                1,
                "lower(text)",
                "Converts text to lower case.",
                "lower('HeLLo')",
                "\"hello\"",
                (ev, a) -> caseMap(ev, str(a, 0), false));
        def(
                m,
                "upper",
                c,
                1,
                1,
                "upper(text)",
                "Converts text to upper case.",
                "upper('abc')",
                "\"ABC\"",
                (ev, a) -> caseMap(ev, str(a, 0), true));
        def(
                m,
                "trim",
                c,
                1,
                1,
                "trim(text)",
                "Removes leading and trailing whitespace.",
                "trim('  hi  ')",
                "\"hi\"",
                (ev, a) -> str(a, 0).strip());
        def(
                m,
                "split",
                c,
                2,
                2,
                "split(text, separator)",
                "Splits text into an array on a literal separator. An empty separator splits into characters.",
                "split('a,b,c', ',')",
                "[\"a\",\"b\",\"c\"]",
                (ev, a) -> {
                    String s = str(a, 0);
                    String sep = str(a, 1);
                    List<Object> out = new ArrayList<>();
                    if (sep.isEmpty()) {
                        ev.checkItems(s.length());
                        for (int i = 0; i < s.length(); i++) {
                            out.add(String.valueOf(s.charAt(i)));
                        }
                        return out;
                    }
                    int from = 0;
                    while (true) {
                        int i = s.indexOf(sep, from);
                        if (i < 0) {
                            out.add(s.substring(from));
                            return out;
                        }
                        out.add(s.substring(from, i));
                        if ((out.size() & 1023) == 0) {
                            ev.checkItems(out.size());
                        }
                        from = i + sep.length();
                    }
                });
        def(
                m,
                "join",
                c,
                1,
                2,
                "join(array, separator = \",\")",
                "Joins array items into one string; null items become empty text.",
                "join(['a','b'], '-')",
                "\"a-b\"",
                (ev, a) -> {
                    List<Object> l = arr(a, 0, "join");
                    String sep = has(a, 1) ? str(a, 1) : ",";
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < l.size(); i++) {
                        if (i > 0) {
                            sb.append(sep);
                        }
                        sb.append(ev.str(l.get(i)));
                        ev.checkLen(sb.length());
                    }
                    return sb.toString();
                });
        def(
                m,
                "replace",
                c,
                3,
                3,
                "replace(text, search, replacement)",
                "Replaces every occurrence of a literal substring.",
                "replace('a-b-c', '-', '+')",
                "\"a+b+c\"",
                (ev, a) -> {
                    String s = str(a, 0);
                    String find = str(a, 1);
                    String rep = str(a, 2);
                    if (find.isEmpty()) {
                        return s;
                    }
                    StringBuilder sb = new StringBuilder();
                    int from = 0;
                    int i;
                    while ((i = s.indexOf(find, from)) >= 0) {
                        sb.append(s, from, i).append(rep);
                        ev.checkLen(sb.length());
                        from = i + find.length();
                    }
                    sb.append(s, from, s.length());
                    ev.checkLen(sb.length());
                    return sb.toString();
                });
        def(
                m,
                "regexReplace",
                c,
                3,
                4,
                "regexReplace(text, pattern, replacement, flags = \"\")",
                "Replaces every regex match. The replacement may reference numbered capture groups; flags are i, m, s. Patterns are length-capped and time-limited.",
                "regexReplace('a1b22', '[0-9]+', '#')",
                "\"a#b#\"",
                (ev, a) -> {
                    String s = str(a, 0);
                    Pattern p = ev.pattern(str(a, 1), has(a, 3) ? str(a, 3) : "");
                    String rep = str(a, 2);
                    Matcher mt = p.matcher(ev.guard(s));
                    StringBuilder sb = new StringBuilder();
                    try {
                        while (mt.find()) {
                            mt.appendReplacement(sb, rep);
                            ev.checkLen(sb.length());
                        }
                        mt.appendTail(sb);
                    } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
                        throw err("regexReplace", "invalid replacement (" + e.getMessage() + ")");
                    }
                    ev.checkLen(sb.length());
                    return sb.toString();
                });
        def(
                m,
                "substring",
                c,
                2,
                3,
                "substring(text, start, end = length)",
                "Returns the part of text between two zero-based positions (clamped to the text).",
                "substring('abcdef', 1, 4)",
                "\"bcd\"",
                (ev, a) -> {
                    String s = str(a, 0);
                    int len = s.length();
                    int st = Math.max(0, Math.min(len, clampInt(lng(a, 1, "substring"))));
                    int en =
                            has(a, 2)
                                    ? Math.max(0, Math.min(len, clampInt(lng(a, 2, "substring"))))
                                    : len;
                    return st <= en ? s.substring(st, en) : s.substring(en, st);
                });
        def(
                m,
                "contains",
                c,
                2,
                2,
                "contains(textOrArrayOrObject, value)",
                "True if the text contains the substring, the array contains the value, or the object has the key.",
                "contains('hello', 'ell')",
                "true",
                (ev, a) -> {
                    Object x = get(a, 0);
                    Object needle = get(a, 1);
                    if (x instanceof String s) {
                        return s.contains(Values.toStr(needle));
                    }
                    if (x instanceof List<?> l) {
                        for (Object e : l) {
                            if (Values.looseEquals(e, needle, ev.budget())) {
                                return true;
                            }
                        }
                        return false;
                    }
                    if (x instanceof Map<?, ?> mp) {
                        return mp.containsKey(Values.toStr(needle));
                    }
                    return false;
                });
        def(
                m,
                "startsWith",
                c,
                2,
                2,
                "startsWith(text, prefix)",
                "True if the text starts with the prefix.",
                "startsWith('hello', 'he')",
                "true",
                (ev, a) -> str(a, 0).startsWith(str(a, 1)));
        def(
                m,
                "endsWith",
                c,
                2,
                2,
                "endsWith(text, suffix)",
                "True if the text ends with the suffix.",
                "endsWith('hello', 'lo')",
                "true",
                (ev, a) -> str(a, 0).endsWith(str(a, 1)));
        def(
                m,
                "length",
                c,
                1,
                1,
                "length(textOrArrayOrObject)",
                "Number of characters, array items or object keys; 0 for null.",
                "length([1, 2, 3])",
                "3",
                (ev, a) -> {
                    Object x = get(a, 0);
                    if (x == null) {
                        return 0L;
                    }
                    if (x instanceof String s) {
                        return (long) s.length();
                    }
                    if (x instanceof List<?> l) {
                        return (long) l.size();
                    }
                    if (x instanceof Map<?, ?> mp) {
                        return (long) mp.size();
                    }
                    throw err(
                            "length",
                            "expects text, an array or an object, got " + Values.typeOf(x));
                });
        def(
                m,
                "padStart",
                c,
                2,
                3,
                "padStart(text, length, pad = \" \")",
                "Left-pads text to the given length by repeating the pad text.",
                "padStart('7', 3, '0')",
                "\"007\"",
                (ev, a) -> {
                    String s = str(a, 0);
                    long target = lng(a, 1, "padStart");
                    String pad = has(a, 2) ? str(a, 2) : " ";
                    ev.checkLen(target);
                    if (pad.isEmpty() || s.length() >= target) {
                        return s;
                    }
                    int need = (int) target - s.length();
                    StringBuilder sb = new StringBuilder(need);
                    while (sb.length() < need) {
                        sb.append(pad);
                    }
                    sb.setLength(need);
                    return sb.append(s).toString();
                });
        def(
                m,
                "urlEncode",
                c,
                1,
                1,
                "urlEncode(text)",
                "Percent-encodes text for use in a URL component.",
                "urlEncode('a b&c')",
                "\"a%20b%26c\"",
                (ev, a) ->
                        URLEncoder.encode(str(a, 0), StandardCharsets.UTF_8).replace("+", "%20"));
        def(
                m,
                "base64",
                c,
                1,
                1,
                "base64(text)",
                "Base64-encodes the UTF-8 bytes of the text.",
                "base64('hi')",
                "\"aGk=\"",
                (ev, a) -> {
                    String s = str(a, 0);
                    ev.checkLen(((long) s.length() + 2) / 3 * 4);
                    return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
                });
        def(
                m,
                "base64Decode",
                c,
                1,
                1,
                "base64Decode(text)",
                "Decodes base64 text to a UTF-8 string.",
                "base64Decode('aGk=')",
                "\"hi\"",
                (ev, a) -> {
                    String s = str(a, 0).strip();
                    ev.checkLen((long) s.length());
                    return new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8);
                });
    }
}
