package com.agenticai.core.quality.grader;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A deterministic ("code") grader for one eval case: no LLM, no judge noise, same input always
 * gives the same verdict. Three kinds, selected by the spec's {@code type}:
 *
 * <pre>
 *   {"type":"regex","pattern":"(?i)\\bseine\\b","mode":"find"}        mode: find (default) | matches
 *   {"type":"exact","value":"2","ignoreCase":true,"trim":true}        trim defaults to true
 *   {"type":"json_schema","schema":{...}}                             subset, see {@link MiniJsonSchema}
 * </pre>
 *
 * <p>{@link #parse} validates the spec up front (a bad pattern or an unsupported schema keyword is
 * an {@link IllegalArgumentException} at write time, never a silent pass at run time). {@code
 * ignoreCase} on a regex spec is the same as a leading {@code (?i)}.
 *
 * <p>Safety: regexes are author-supplied, so matching runs on a bounded daemon pool under a hard
 * wall-clock timeout ({@link #REGEX_TIMEOUT_MS}) with a charAt step/deadline budget as a second
 * fence - a pathological pattern fails the case with a clear detail instead of pinning the run's
 * worker thread. A pattern that never calls charAt cannot be killed, only abandoned (its daemon
 * thread keeps burning CPU until it ends); the pool is bounded so that cannot grow without limit.
 */
public final class CodeGrader {

    /** Outcome of grading one response. {@code detail} is human-readable and safe to store. */
    public record Result(boolean passed, String detail) {}

    public enum Kind {
        REGEX,
        EXACT,
        JSON_SCHEMA
    }

    static final int MAX_PATTERN_LENGTH = 500;
    static final int MAX_EXPECTED_LENGTH = 2000;

    /** Responses longer than this are truncated before grading (the verdict says so). */
    static final int MAX_RESPONSE_CHARS = 200_000;

    /**
     * charAt budget for one regex evaluation; ~tens of ms of matching, orders of magnitude above
     * sane use.
     */
    static final long REGEX_STEP_BUDGET = 20_000_000L;

    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final Kind kind;
    private final Pattern pattern;
    private final boolean matchesWhole;
    private final String expected;
    private final boolean ignoreCase;
    private final boolean trim;
    private final JsonNode schema;

    private CodeGrader(
            Kind kind,
            Pattern pattern,
            boolean matchesWhole,
            String expected,
            boolean ignoreCase,
            boolean trim,
            JsonNode schema) {
        this.kind = kind;
        this.pattern = pattern;
        this.matchesWhole = matchesWhole;
        this.expected = expected;
        this.ignoreCase = ignoreCase;
        this.trim = trim;
        this.schema = schema;
    }

    public Kind kind() {
        return kind;
    }

    /**
     * @param specJson the stored spec text
     * @throws IllegalArgumentException when blank, malformed, of an unknown type, or invalid for
     *     its type
     */
    public static CodeGrader parse(String specJson) {
        if (specJson == null || specJson.isBlank()) {
            throw new IllegalArgumentException("Grader spec is empty.");
        }
        JsonNode spec;
        try {
            spec = MAPPER.readTree(specJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("Grader spec is not valid JSON: " + e.getMessage());
        }
        if (spec == null || !spec.isObject()) {
            throw new IllegalArgumentException("Grader spec must be a JSON object with a 'type'.");
        }
        String type = spec.path("type").asText("").trim().toLowerCase(java.util.Locale.ROOT);
        switch (type) {
            case "regex":
                {
                    String raw = spec.path("pattern").asText("");
                    if (raw.isEmpty()) {
                        throw new IllegalArgumentException(
                                "Regex grader needs a non-empty 'pattern'.");
                    }
                    if (raw.length() > MAX_PATTERN_LENGTH) {
                        throw new IllegalArgumentException(
                                "Regex 'pattern' is limited to "
                                        + MAX_PATTERN_LENGTH
                                        + " characters.");
                    }
                    String mode =
                            spec.path("mode")
                                    .asText("find")
                                    .trim()
                                    .toLowerCase(java.util.Locale.ROOT);
                    if (!mode.equals("find") && !mode.equals("matches")) {
                        throw new IllegalArgumentException(
                                "Regex 'mode' must be 'find' or 'matches'.");
                    }
                    int flags =
                            spec.path("ignoreCase").asBoolean(false) ? Pattern.CASE_INSENSITIVE : 0;
                    try {
                        return new CodeGrader(
                                Kind.REGEX,
                                Pattern.compile(raw, flags | Pattern.DOTALL),
                                mode.equals("matches"),
                                null,
                                false,
                                false,
                                null);
                    } catch (PatternSyntaxException e) {
                        throw new IllegalArgumentException("Invalid regex: " + e.getDescription());
                    }
                }
            case "exact":
                {
                    JsonNode value = spec.get("value");
                    if (value == null || !value.isTextual()) {
                        throw new IllegalArgumentException("Exact grader needs a string 'value'.");
                    }
                    if (value.asText().length() > MAX_EXPECTED_LENGTH) {
                        throw new IllegalArgumentException(
                                "Exact 'value' is limited to "
                                        + MAX_EXPECTED_LENGTH
                                        + " characters.");
                    }
                    boolean ignore = spec.path("ignoreCase").asBoolean(false);
                    boolean trim = spec.path("trim").asBoolean(true);
                    return new CodeGrader(
                            Kind.EXACT,
                            null,
                            false,
                            trim ? value.asText().trim() : value.asText(),
                            ignore,
                            trim,
                            null);
                }
            case "json_schema":
                {
                    JsonNode schema = spec.get("schema");
                    if (schema == null || !schema.isObject()) {
                        throw new IllegalArgumentException(
                                "JSON-schema grader needs an object 'schema'.");
                    }
                    MiniJsonSchema.assertSupported(schema);
                    return new CodeGrader(
                            Kind.JSON_SCHEMA, null, false, null, false, false, schema);
                }
            default:
                throw new IllegalArgumentException(
                        "Unknown grader type '" + type + "' - use regex, exact or json_schema.");
        }
    }

    /**
     * Grades one response. Never throws; a null/blank response simply fails (or matches an empty
     * expectation).
     */
    public Result grade(String response) {
        String text = response == null ? "" : response;
        String note = "";
        if (text.length() > MAX_RESPONSE_CHARS) {
            text = text.substring(0, MAX_RESPONSE_CHARS);
            note = " (response truncated to " + MAX_RESPONSE_CHARS + " chars)";
        }
        switch (kind) {
            case REGEX:
                {
                    try {
                        boolean ok = matchBounded(pattern, text, matchesWhole);
                        return new Result(
                                ok, (ok ? "regex matched" : "regex did not match") + note);
                    } catch (BudgetExceeded e) {
                        return new Result(
                                false,
                                "regex aborted: evaluation budget exceeded (pattern too expensive)");
                    } catch (StackOverflowError e) {
                        return new Result(
                                false,
                                "regex aborted: pattern recursed too deeply on this response");
                    }
                }
            case EXACT:
                {
                    String actual = trim ? text.trim() : text;
                    boolean ok =
                            ignoreCase
                                    ? actual.equalsIgnoreCase(expected)
                                    : actual.equals(expected);
                    return new Result(
                            ok,
                            (ok ? "exact match" : "response is not exactly the expected value")
                                    + note);
                }
            default:
                {
                    JsonNode data = JsonExtract.firstJson(text);
                    if (data == null) {
                        return new Result(false, "no JSON found in the response" + note);
                    }
                    java.util.List<String> errors = new java.util.ArrayList<>();
                    MiniJsonSchema.validate(schema, data, "$", errors);
                    if (errors.isEmpty()) {
                        return new Result(true, "JSON matches the schema" + note);
                    }
                    String joined =
                            String.join("; ", errors.subList(0, Math.min(errors.size(), 5)));
                    return new Result(
                            false,
                            "JSON violates the schema: "
                                    + joined
                                    + (errors.size() > 5
                                            ? " (+" + (errors.size() - 5) + " more)"
                                            : "")
                                    + note);
                }
        }
    }

    // ------------------------------------------------------------------

    /**
     * Wall-clock bound for one regex evaluation (the REAL bound; the charAt budget is a second
     * fence).
     */
    static final long REGEX_TIMEOUT_MS = 1500L;

    /**
     * Regex matching runs on these daemon threads so the caller can stop WAITING after {@link
     * #REGEX_TIMEOUT_MS} even if the engine never calls charAt (a charAt-counting wrapper alone
     * cannot stop such a pattern). Bounded: when every thread is pinned by a runaway pattern,
     * further regex grading fails fast instead of queueing or hanging the eval worker.
     */
    private static final java.util.concurrent.ThreadPoolExecutor REGEX_POOL = newRegexPool();

    private static java.util.concurrent.ThreadPoolExecutor newRegexPool() {
        java.util.concurrent.atomic.AtomicInteger n =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.ThreadPoolExecutor pool =
                TenantAwareExecutors.newThreadPool(
                        8,
                        8,
                        30L,
                        java.util.concurrent.TimeUnit.SECONDS,
                        new java.util.concurrent.SynchronousQueue<>(),
                        TenantAwareExecutors.numberedThreadFactory("regex-grader-", true));
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    /**
     * Runs {@code find()} / {@code matches()} of an author-supplied pattern under a hard wall-clock
     * timeout. Shared with {@link MiniJsonSchema}.
     *
     * @throws BudgetExceeded on timeout, step-budget exhaustion or a saturated grader pool
     * @throws StackOverflowError when the pattern recursed too deeply
     */
    static boolean matchBounded(Pattern pattern, CharSequence text, boolean whole) {
        Budget budget =
                new Budget(
                        REGEX_STEP_BUDGET,
                        System.nanoTime() + (REGEX_TIMEOUT_MS + 250L) * 1_000_000L);
        java.util.concurrent.Future<Boolean> future;
        try {
            future =
                    REGEX_POOL.submit(
                            () -> {
                                Matcher m = pattern.matcher(new BoundedCharSequence(text, budget));
                                return whole ? m.matches() : m.find();
                            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            throw new BudgetExceeded();
        }
        try {
            return future.get(REGEX_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            throw new BudgetExceeded();
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new BudgetExceeded();
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof BudgetExceeded be) {
                throw be;
            }
            if (cause instanceof StackOverflowError so) {
                throw so;
            }
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new BudgetExceeded();
        }
    }

    static final class BudgetExceeded extends RuntimeException {
        BudgetExceeded() {
            super(null, null, false, false);
        }
    }

    /** Mutable step + deadline state shared by a sequence and its sub-sequences. */
    private static final class Budget {
        long steps;
        final long deadlineNanos;

        Budget(long steps, long deadlineNanos) {
            this.steps = steps;
            this.deadlineNanos = deadlineNanos;
        }
    }

    /**
     * CharSequence that throws once {@code charAt} was called too often, ran too long, or was
     * interrupted.
     */
    private static final class BoundedCharSequence implements CharSequence {
        private final CharSequence inner;
        private final Budget budget;

        BoundedCharSequence(CharSequence inner, Budget budget) {
            this.inner = inner;
            this.budget = budget;
        }

        @Override
        public int length() {
            return inner.length();
        }

        @Override
        public char charAt(int index) {
            long left = --budget.steps;
            if (left < 0) {
                throw new BudgetExceeded();
            }
            if ((left & 0x3FF) == 0
                    && (System.nanoTime() - budget.deadlineNanos > 0
                            || Thread.currentThread().isInterrupted())) {
                throw new BudgetExceeded();
            }
            return inner.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new BoundedCharSequence(inner.subSequence(start, end), budget);
        }

        @Override
        public String toString() {
            return inner.toString();
        }
    }
}
