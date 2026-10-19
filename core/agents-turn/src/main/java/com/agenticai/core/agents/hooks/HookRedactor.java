package com.agenticai.core.agents.hooks;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The {@code redact} hook action: built-in PII patterns plus a few author-supplied
 * regexes, applied to tool arguments, a tool result, or the model's reply.
 *
 * <p>The built-in patterns are an independent copy of the shapes
 * {@code LlmGatewayPiiRedactor} (core/chat, package-private) and {@code PiiGuardrail}
 * (core/guardrails) already use - email, phone (NANP and the India 5-5 grouping),
 * credit card, US SSN, India Aadhaar and PAN - kept equivalent on purpose and
 * duplicated for the same reason that class duplicates {@code PiiGuardrail}: neither
 * module may depend on the other without a cycle, and this one is not allowed to widen
 * a package-private helper in another lane's module.
 *
 * <p><b>Author-supplied regexes are run under a time budget.</b> The text being
 * redacted can be attacker-influenced (a tool result from the open web), so a
 * catastrophic-backtracking pattern must not be able to hold a request thread. Matching
 * runs over a {@link CharSequence} that checks a deadline as the engine reads it and
 * aborts with {@link RedactionException}; callers treat that as "redaction did not
 * happen" and fail closed. A pattern that matches the empty string is rejected at save
 * time (it would "match" everywhere) and its empty matches are skipped at run time.
 */
public final class HookRedactor {

    public static final String DEFAULT_REPLACEMENT = "[REDACTED]";
    public static final int MAX_CUSTOM_PATTERNS = 5;
    public static final int MAX_PATTERN_LENGTH = 200;
    public static final int MAX_REPLACEMENT_LENGTH = 40;
    /** Hard stop on matches per pattern, so a pathological pattern cannot build an unbounded result. */
    private static final int MAX_MATCHES_PER_PATTERN = 5_000;
    private static final int MAX_COMPILED_CACHE = 256;

    private static final Pattern EMAIL = Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b");
    private static final Pattern PHONE = Pattern.compile(
        "(?<!\\d)(?:\\+?\\d{1,3}[-. ]?)?"
            + "(?:"
            + "(?:\\(\\d{3}\\)|\\d{3})[-. ]?\\d{3}[-. ]?\\d{4}"
            + "|\\d{5}[-. ]?\\d{5}"
            + ")(?!\\d)");
    private static final Pattern CREDIT_CARD = Pattern.compile("\\b\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}\\b");
    private static final Pattern SSN = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");
    private static final Pattern AADHAAR = Pattern.compile("(?<!\\d)\\d{4}[-\\s]?\\d{4}[-\\s]?\\d{4}(?!\\d)");
    private static final Pattern PAN = Pattern.compile("\\b[A-Za-z]{5}\\d{4}[A-Za-z]\\b");

    private record Builtin(Pattern pattern, String placeholder) {
    }

    /** Application order matters only in that the longer digit patterns run before the shorter ones. */
    private static final Map<String, Builtin> BUILTINS = new LinkedHashMap<>();

    static {
        BUILTINS.put("email", new Builtin(EMAIL, "[REDACTED_EMAIL]"));
        BUILTINS.put("credit_card", new Builtin(CREDIT_CARD, "[REDACTED_CARD]"));
        BUILTINS.put("ssn", new Builtin(SSN, "[REDACTED_GOVERNMENT_ID]"));
        BUILTINS.put("aadhaar", new Builtin(AADHAAR, "[REDACTED_GOVERNMENT_ID]"));
        BUILTINS.put("pan", new Builtin(PAN, "[REDACTED_GOVERNMENT_ID]"));
        BUILTINS.put("phone", new Builtin(PHONE, "[REDACTED_PHONE]"));
    }

    private static final Map<String, Pattern> COMPILED = new ConcurrentHashMap<>();

    private HookRedactor() {
    }

    /** The accepted names for the {@code patterns} parameter. */
    public static Set<String> builtinNames() {
        return Set.copyOf(BUILTINS.keySet());
    }

    /**
     * @param text what was redacted (the input when nothing matched)
     * @param count how many substrings were replaced
     */
    public record Result(String text, int count) {
    }

    /** Redaction could not be completed safely (time budget exceeded or a runaway match count). */
    public static final class RedactionException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public RedactionException(String message) {
            super(message);
        }
    }

    /**
     * @param builtinNames names from {@link #builtinNames()}; unknown names are ignored here (save-time
     *     validation already rejected them)
     * @param customRegex author regexes; one that no longer compiles is skipped here for the same reason
     * @param replacement overrides every placeholder when non-blank
     * @param budgetMillis wall-clock budget for the whole call
     * @throws RedactionException when the budget is exceeded
     */
    public static Result redact(
        String text, List<String> builtinNames, List<String> customRegex, String replacement, long budgetMillis
    ) {
        if (text == null || text.isEmpty()) {
            return new Result(text, 0);
        }
        long deadline = System.nanoTime() + Math.max(1L, budgetMillis) * 1_000_000L;
        boolean override = replacement != null && !replacement.isBlank();
        String current = text;
        int total = 0;
        if (builtinNames != null) {
            for (Map.Entry<String, Builtin> entry : BUILTINS.entrySet()) {
                if (!builtinNames.contains(entry.getKey())) {
                    continue;
                }
                Result r = apply(current, entry.getValue().pattern(),
                    override ? replacement : entry.getValue().placeholder(), deadline);
                current = r.text();
                total += r.count();
            }
        }
        if (customRegex != null) {
            for (String regex : customRegex) {
                Pattern p = compile(regex);
                if (p == null) {
                    continue;
                }
                Result r = apply(current, p, override ? replacement : DEFAULT_REPLACEMENT, deadline);
                current = r.text();
                total += r.count();
            }
        }
        return new Result(current, total);
    }

    private static Result apply(String input, Pattern pattern, String replacement, long deadlineNanos) {
        Matcher m = pattern.matcher(new DeadlineCharSequence(input, deadlineNanos));
        StringBuilder out = null;
        int last = 0;
        int count = 0;
        while (m.find()) {
            if (m.end() == m.start()) {
                continue;
            }
            if (++count > MAX_MATCHES_PER_PATTERN) {
                throw new RedactionException("too many matches");
            }
            if (out == null) {
                out = new StringBuilder(input.length() + 16);
            }
            out.append(input, last, m.start()).append(replacement);
            last = m.end();
        }
        if (out == null) {
            return new Result(input, 0);
        }
        out.append(input, last, input.length());
        return new Result(out.toString(), count);
    }

    /**
     * Save-time check of one author regex.
     *
     * @return an error message, or {@code null} when the pattern is acceptable
     */
    public static String validateRegex(String regex) {
        if (regex == null || regex.isBlank()) {
            return "a regex must not be blank";
        }
        if (regex.length() > MAX_PATTERN_LENGTH) {
            return "a regex may be at most " + MAX_PATTERN_LENGTH + " characters";
        }
        Pattern p;
        try {
            p = Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            return "not a valid regular expression (" + e.getDescription() + ")";
        }
        if (p.matcher("").find()) {
            return "a regex must not match the empty string";
        }
        return null;
    }

    private static Pattern compile(String regex) {
        if (regex == null || regex.isBlank()) {
            return null;
        }
        Pattern cached = COMPILED.get(regex);
        if (cached != null) {
            return cached;
        }
        try {
            Pattern p = Pattern.compile(regex);
            if (COMPILED.size() >= MAX_COMPILED_CACHE) {
                COMPILED.clear();
            }
            COMPILED.put(regex, p);
            return p;
        } catch (PatternSyntaxException e) {
            return null;
        }
    }

    /**
     * A {@link CharSequence} that aborts a runaway regex: the engine reads characters through
     * {@link #charAt}, so checking the clock there bounds the whole match. The clock is read every
     * 256th access, which keeps the overhead negligible and the overshoot to microseconds.
     */
    private static final class DeadlineCharSequence implements CharSequence {

        private final String base;
        private final long deadlineNanos;
        private int reads;

        DeadlineCharSequence(String base, long deadlineNanos) {
            this.base = base;
            this.deadlineNanos = deadlineNanos;
        }

        @Override
        public int length() {
            return base.length();
        }

        @Override
        public char charAt(int index) {
            if ((++reads & 0xFF) == 0 && System.nanoTime() - deadlineNanos > 0) {
                throw new RedactionException("redaction time budget exceeded");
            }
            return base.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return base.subSequence(start, end);
        }

        @Override
        public String toString() {
            return base;
        }
    }
}
