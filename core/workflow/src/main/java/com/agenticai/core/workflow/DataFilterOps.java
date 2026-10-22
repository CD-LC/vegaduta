package com.agenticai.core.workflow;

import static com.agenticai.core.workflow.DataPathOps.get;
import static com.agenticai.core.workflow.DataValues.abbreviate;
import static com.agenticai.core.workflow.DataValues.isAbsent;
import static com.agenticai.core.workflow.DataValues.isEmpty;
import static com.agenticai.core.workflow.DataValues.text;
import static com.agenticai.core.workflow.NumberCanon.canonicalNumber;
import static com.agenticai.core.workflow.NumberCanon.lenientNumber;
import static com.agenticai.core.workflow.NumberCanon.parseDate;
import static com.agenticai.core.workflow.WorkflowDataOps.SWITCH_DEFAULT_HANDLE;
import static com.agenticai.core.workflow.WorkflowDataOps.bool;
import static com.agenticai.core.workflow.WorkflowDataOps.mapList;
import static com.agenticai.core.workflow.WorkflowDataOps.str;

import com.agenticai.core.workflow.WorkflowDataOps.Condition;
import com.agenticai.core.workflow.WorkflowDataOps.Items;
import com.agenticai.core.workflow.WorkflowDataOps.Outcome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The {@code filter} and {@code switch} data operations and the condition language they share
 * ({@code field operator value}). Every operator is a {@link Operator} strategy (name aliases ->
 * predicate), so adding one is one enum constant. Regex safety (pattern/input caps, per-match
 * deadline, shared budget) lives in {@link ConditionEvaluator}. Extracted from {@link
 * WorkflowDataOps}.
 */
final class DataFilterOps {

    static final int MAX_REGEX_PATTERN_CHARS = 200;
    static final int MAX_REGEX_INPUT_CHARS = 10_000;
    static final long REGEX_MATCH_BUDGET_NANOS = 50_000_000L;
    static final long REGEX_TOTAL_BUDGET_NANOS = 2_000_000_000L;
    static final int MAX_REGEX_TIMEOUTS = 3;

    /** A condition operator: its canonical id, the spellings it accepts and the test it applies. */
    enum Operator {
        EQ("eq", (ev, v, rhs) -> ev.looseEquals(v, rhs), "=", "==", "equals", "equal"),
        NEQ("neq", (ev, v, rhs) -> !ev.looseEquals(v, rhs), "!=", "<>", "notequals", "notequal"),
        GT("gt", (ev, v, rhs) -> ev.ordered(v, rhs, r -> r > 0), ">"),
        LT("lt", (ev, v, rhs) -> ev.ordered(v, rhs, r -> r < 0), "<"),
        GTE("gte", (ev, v, rhs) -> ev.ordered(v, rhs, r -> r >= 0), ">="),
        LTE("lte", (ev, v, rhs) -> ev.ordered(v, rhs, r -> r <= 0), "<="),
        CONTAINS("contains", (ev, v, rhs) -> ev.contains(v, rhs)),
        STARTS("starts", (ev, v, rhs) -> ev.fold(text(v)).startsWith(ev.fold(rhs)), "startswith"),
        ENDS("ends", (ev, v, rhs) -> ev.fold(text(v)).endsWith(ev.fold(rhs)), "endswith"),
        EMPTY("empty", (ev, v, rhs) -> isEmpty(v), "isempty"),
        NOT_EMPTY("notEmpty", (ev, v, rhs) -> !isEmpty(v), "notempty", "isnotempty"),
        REGEX("regex", (ev, v, rhs) -> ev.regex(v, rhs), "matches");

        /** The test of one operator against one item value and the configured right-hand side. */
        @FunctionalInterface
        interface Test {
            boolean apply(ConditionEvaluator ev, JsonNode v, String rhs);
        }

        private final String id;
        private final Test test;
        private final List<String> aliases;

        Operator(String id, Test test, String... aliases) {
            this.id = id;
            this.test = test;
            this.aliases = List.of(aliases);
        }

        String id() {
            return id;
        }

        boolean test(ConditionEvaluator ev, JsonNode v, String rhs) {
            return test.apply(ev, v, rhs);
        }

        /**
         * Canonical operator for a spelling (symbols, case and "_ - space" insensitive), or null
         * when unknown.
         */
        static Operator parse(String raw) {
            if (raw == null) {
                return null;
            }
            String s =
                    raw.strip()
                            .toLowerCase(Locale.ROOT)
                            .replace("_", "")
                            .replace("-", "")
                            .replace(" ", "");
            for (Operator op : values()) {
                if (op.id.toLowerCase(Locale.ROOT).equals(s) || op.aliases.contains(s)) {
                    return op;
                }
            }
            return null;
        }
    }

    private DataFilterOps() {}

    /**
     * Canonical operator name, or null when unknown. Accepts the symbols and a few common
     * spellings.
     */
    static String normalizeOperator(String raw) {
        Operator op = Operator.parse(raw);
        return op == null ? null : op.id();
    }

    /**
     * {@code conditions: [{field, operator, value}]}, {@code combinator: and|or}, {@code
     * ignoreCase}, {@code itemsField}.
     */
    static Outcome filter(
            ObjectMapper mapper, Map<String, Object> config, Supplier<Items> itemsSource) {
        List<Condition> conditions = conditions(config.get("conditions"));
        boolean any = isOr(config.get("combinator"));
        ConditionEvaluator evaluator =
                new ConditionEvaluator(bool(config.get("ignoreCase"), false));
        Items items = itemsSource.get();
        List<String> log = new ArrayList<>();
        ArrayNode kept = mapper.createArrayNode();
        if (conditions.isEmpty()) {
            kept.addAll(items.array());
            log.add("No conditions configured - every item kept.");
        } else {
            for (JsonNode item : items.array()) {
                if (evaluator.matches(item, conditions, any)) {
                    kept.add(item);
                }
            }
        }
        evaluator.addWarnings(log);
        int total = items.array().size();
        return Outcome.ok(
                WorkflowDataOps.withItems(items, kept).toString(),
                "Filter kept " + kept.size() + " of " + total + " item(s).",
                log);
    }

    /**
     * {@code cases: [{name, combinator: and|or, conditions: [{field, operator, value}]}]}, {@code
     * ignoreCase}. The FIRST case whose conditions hold wins and its {@code name} becomes the
     * node's {@code branch} - the very mechanism {@code condition} (true/false) and {@code
     * supervisor} (route handle) use, resolved by {@link WorkflowGraphSupport#nextNodeId} against
     * each outgoing edge's {@code sourceHandle}. No match routes to {@value
     * #SWITCH_DEFAULT_HANDLE}. The input passes through unchanged. The condition fields are read
     * from the input as one item (a JSON object; a blank field tests the whole input text).
     */
    static Outcome switchRoute(ObjectMapper mapper, Map<String, Object> config, String input) {
        List<Map<String, Object>> cases = mapList(config.get("cases"));
        ConditionEvaluator evaluator =
                new ConditionEvaluator(bool(config.get("ignoreCase"), false));
        JsonNode item = DataValues.parse(mapper, input);
        if (item == null) {
            item = TextNode.valueOf(input == null ? "" : input);
        }
        List<String> log = new ArrayList<>();
        for (Map<String, Object> c : cases) {
            String name = str(c.get("name"));
            List<Condition> conditions = conditions(c.get("conditions"));
            if (name == null || name.isBlank() || conditions.isEmpty()) {
                continue;
            }
            if (evaluator.matches(item, conditions, isOr(c.get("combinator")))) {
                evaluator.addWarnings(log);
                log.add("Matched case '" + name.strip() + "'.");
                return Outcome.routed(
                        input, "Switch routed to '" + name.strip() + "'.", name.strip(), log);
            }
        }
        evaluator.addWarnings(log);
        log.add("No case matched.");
        return Outcome.routed(
                input,
                "Switch defaulted to '" + SWITCH_DEFAULT_HANDLE + "' (no case matched).",
                SWITCH_DEFAULT_HANDLE,
                log);
    }

    static List<Condition> conditions(Object raw) {
        List<Condition> out = new ArrayList<>();
        for (Map<String, Object> m : mapList(raw)) {
            String field = str(m.get("field"));
            String op = str(m.get("operator"));
            Object value = m.get("value");
            out.add(
                    new Condition(
                            field == null ? "" : field.strip(),
                            op == null || op.isBlank() ? "eq" : op,
                            value == null ? "" : value.toString()));
        }
        return out;
    }

    static boolean isOr(Object combinator) {
        return combinator != null && combinator.toString().strip().equalsIgnoreCase("or");
    }

    /**
     * Evaluates conditions against one item. One instance per node execution (it carries the regex
     * budget).
     */
    static final class ConditionEvaluator {
        private final boolean ignoreCase;
        private final long totalDeadline;
        private final Map<String, Pattern> patterns = new HashMap<>();
        private int regexTimeouts;
        private int regexSkippedLong;
        private int regexTooDeep;

        ConditionEvaluator(boolean ignoreCase) {
            this.ignoreCase = ignoreCase;
            this.totalDeadline = System.nanoTime() + REGEX_TOTAL_BUDGET_NANOS;
        }

        /**
         * AND (any=false) / OR (any=true) over {@code conditions}; the caller handles an empty
         * list.
         */
        boolean matches(JsonNode item, List<Condition> conditions, boolean any) {
            for (Condition c : conditions) {
                boolean r = test(item, c);
                if (any && r) {
                    return true;
                }
                if (!any && !r) {
                    return false;
                }
            }
            return !any;
        }

        void addWarnings(List<String> log) {
            if (regexTimeouts > 0) {
                log.add(regexTimeouts + " regex match(es) timed out and were treated as no match.");
            }
            if (regexTooDeep > 0) {
                log.add(
                        regexTooDeep
                                + " regex match(es) were too deep for the input and were treated as no match.");
            }
            if (regexSkippedLong > 0) {
                log.add(
                        regexSkippedLong
                                + " value(s) longer than "
                                + MAX_REGEX_INPUT_CHARS
                                + " characters were not regex-tested (treated as no match).");
            }
        }

        boolean test(JsonNode item, Condition c) {
            Operator op = Operator.parse(c.operator());
            if (op == null) {
                throw new IllegalArgumentException("Unknown operator '" + c.operator() + "'.");
            }
            return op.test(this, get(item, c.field()), c.value());
        }

        private boolean ordered(JsonNode v, String rhs, java.util.function.IntPredicate accepts) {
            Integer r = order(v, rhs);
            return r != null && accepts.test(r);
        }

        private boolean contains(JsonNode v, String rhs) {
            if (v.isArray()) {
                for (JsonNode e : v) {
                    if (looseEquals(e, rhs)) {
                        return true;
                    }
                }
                return false;
            }
            return fold(text(v)).contains(fold(rhs));
        }

        private String fold(String s) {
            return ignoreCase ? s.toLowerCase(Locale.ROOT) : s;
        }

        private boolean looseEquals(JsonNode v, String rhs) {
            BigDecimal rn = canonicalNumber(rhs);
            if (rn != null) {
                BigDecimal vn = canonicalNumber(v);
                if (vn != null) {
                    return vn.compareTo(rn) == 0;
                }
            }
            if (v.isBoolean()) {
                return String.valueOf(v.asBoolean()).equalsIgnoreCase(rhs.strip());
            }
            String t = text(v);
            return ignoreCase ? t.equalsIgnoreCase(rhs) : t.equals(rhs);
        }

        /**
         * Ordering of the item value against the config value; null when the item has no value to
         * order.
         */
        private Integer order(JsonNode v, String rhs) {
            if (isAbsent(v)) {
                return null;
            }
            BigDecimal a = lenientNumber(v);
            BigDecimal b = lenientNumber(rhs);
            if (a != null && b != null) {
                return a.compareTo(b);
            }
            Long da = v.isTextual() ? parseDate(v.asText()) : null;
            Long db = parseDate(rhs);
            if (da != null && db != null) {
                return Long.compare(da, db);
            }
            String ta = text(v);
            return ignoreCase ? ta.compareToIgnoreCase(rhs) : ta.compareTo(rhs);
        }

        private boolean regex(JsonNode v, String pattern) {
            if (pattern.length() > MAX_REGEX_PATTERN_CHARS) {
                throw new IllegalArgumentException(
                        "Regex pattern is longer than " + MAX_REGEX_PATTERN_CHARS + " characters.");
            }
            String haystack = text(v);
            if (haystack.length() > MAX_REGEX_INPUT_CHARS) {
                regexSkippedLong++;
                return false;
            }
            Pattern p = patterns.get(pattern);
            if (p == null) {
                p =
                        Pattern.compile(
                                pattern,
                                ignoreCase ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0);
                patterns.put(pattern, p);
            }
            long now = System.nanoTime();
            if (now - totalDeadline > 0) {
                throw new IllegalStateException(
                        "Regex evaluation budget exceeded - simplify the pattern or reduce the input.");
            }
            long deadline = Math.min(now + REGEX_MATCH_BUDGET_NANOS, totalDeadline);
            try {
                return p.matcher(new DeadlineCharSequence(haystack, deadline)).find();
            } catch (RegexTimeout e) {
                regexTimeouts++;
                if (regexTimeouts >= MAX_REGEX_TIMEOUTS) {
                    throw new IllegalStateException(
                            "Regex '"
                                    + abbreviate(pattern, 40)
                                    + "' timed out repeatedly (catastrophic backtracking?) - simplify the pattern.");
                }
                return false;
            } catch (StackOverflowError e) {
                // java.util.regex recurses per character for constructs like (a|b)*; a long value
                // can blow the stack.
                // An Error would kill the run thread, so treat it as a non-match and say so.
                regexTooDeep++;
                return false;
            }
        }
    }

    /**
     * Thrown from {@link DeadlineCharSequence#charAt} when the per-match budget is spent. No stack
     * trace: it is control flow.
     */
    static final class RegexTimeout extends RuntimeException {
        private static final long serialVersionUID = 1L;

        RegexTimeout() {
            super("regex deadline exceeded", null, false, false);
        }
    }

    /** A CharSequence whose reads watch a deadline, so java.util.regex cannot backtrack forever. */
    static final class DeadlineCharSequence implements CharSequence {
        private final CharSequence inner;
        private final long deadline;
        private int reads;

        DeadlineCharSequence(CharSequence inner, long deadline) {
            this.inner = inner;
            this.deadline = deadline;
        }

        @Override
        public int length() {
            return inner.length();
        }

        @Override
        public char charAt(int index) {
            if ((++reads & 0xFF) == 0 && System.nanoTime() - deadline > 0) {
                throw new RegexTimeout();
            }
            return inner.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new DeadlineCharSequence(inner.subSequence(start, end), deadline);
        }

        @Override
        public String toString() {
            return inner.toString();
        }
    }
}
