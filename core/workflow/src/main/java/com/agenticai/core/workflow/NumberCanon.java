package com.agenticai.core.workflow;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.DoubleNode;
import tools.jackson.databind.node.LongNode;
import tools.jackson.databind.node.TextNode;

/**
 * Number and date canonicalisation shared by every data operation: canonical (strict) versus
 * lenient number reading, the number text/node forms, and ISO-8601 date parsing. Extracted from
 * {@link WorkflowDataOps}.
 */
final class NumberCanon {
    private static final Pattern CANONICAL_NUMBER =
            Pattern.compile("-?(0|[1-9]\\d*)(\\.\\d+)?([eE][-+]?\\d{1,3})?");
    private static final Pattern LENIENT_NUMBER =
            Pattern.compile("[-+]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][-+]?\\d{1,3})?");
    private static final Pattern DATE_PREFIX = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}.*");
    private static final BigDecimal LONG_MIN = BigDecimal.valueOf(Long.MIN_VALUE);
    private static final BigDecimal LONG_MAX = BigDecimal.valueOf(Long.MAX_VALUE);

    static BigDecimal canonicalNumber(JsonNode v) {
        if (v == null) {
            return null;
        }
        if (v.isNumber()) {
            return v.decimalValue();
        }
        return v.isString() ? canonicalNumber(v.asString()) : null;
    }

    static BigDecimal canonicalNumber(String s) {
        if (s == null) {
            return null;
        }
        String t = s.strip();
        if (t.isEmpty() || t.length() > 64 || !CANONICAL_NUMBER.matcher(t).matches()) {
            return null;
        }
        try {
            return new BigDecimal(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static BigDecimal lenientNumber(JsonNode v) {
        if (v == null) {
            return null;
        }
        if (v.isNumber()) {
            return v.decimalValue();
        }
        return v.isString() ? lenientNumber(v.asString()) : null;
    }

    static BigDecimal lenientNumber(String s) {
        if (s == null) {
            return null;
        }
        String t = s.strip();
        if (t.isEmpty() || t.length() > 64 || !LENIENT_NUMBER.matcher(t).matches()) {
            return null;
        }
        try {
            return new BigDecimal(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String numberText(BigDecimal n) {
        BigDecimal s = n.signum() == 0 ? BigDecimal.ZERO : n.stripTrailingZeros();
        return s.toPlainString();
    }

    /**
     * An integral value that fits a long becomes a JSON integer; anything else a JSON double (text
     * if not finite).
     */
    static JsonNode numberNode(BigDecimal n) {
        BigDecimal s = n.signum() == 0 ? BigDecimal.ZERO : n.stripTrailingZeros();
        if (s.scale() <= 0 && s.compareTo(LONG_MIN) >= 0 && s.compareTo(LONG_MAX) <= 0) {
            return LongNode.valueOf(s.longValueExact());
        }
        double d = s.doubleValue();
        return Double.isFinite(d) ? DoubleNode.valueOf(d) : TextNode.valueOf(s.toPlainString());
    }

    /**
     * Epoch millis of an ISO-8601 date / date-time (zone-less values are read as UTC); null when it
     * is not one.
     */
    static Long parseDate(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.strip();
        if (s.length() < 10 || s.length() > 40 || !DATE_PREFIX.matcher(s).matches()) {
            return null;
        }
        try {
            return Instant.parse(s).toEpochMilli();
        } catch (DateTimeParseException ignored) {
            // try the next shape
        }
        try {
            return OffsetDateTime.parse(s).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignored) {
            // try the next shape
        }
        try {
            return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (DateTimeParseException ignored) {
            // try the next shape
        }
        try {
            return LocalDate.parse(s).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private NumberCanon() {}
}
