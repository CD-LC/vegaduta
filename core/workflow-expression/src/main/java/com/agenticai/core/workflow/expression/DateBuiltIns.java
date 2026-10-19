package com.agenticai.core.workflow.expression;

import static com.agenticai.core.workflow.expression.BuiltInArgs.def;
import static com.agenticai.core.workflow.expression.BuiltInArgs.err;
import static com.agenticai.core.workflow.expression.BuiltInArgs.get;
import static com.agenticai.core.workflow.expression.BuiltInArgs.has;
import static com.agenticai.core.workflow.expression.BuiltInArgs.lng;
import static com.agenticai.core.workflow.expression.BuiltInArgs.str;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAccessor;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Date built-ins of {@link BuiltIns}: ISO-8601 / epoch parsing, formatting and arithmetic in
 * IANA zones.
 */
final class DateBuiltIns {

    private DateBuiltIns() {}

    static void register(Map<String, BuiltIn> m) {
        String c = "Date";
        def(
                m,
                "now",
                c,
                0,
                0,
                "now()",
                "The current instant as an ISO-8601 UTC string (same value as $now).",
                "now()",
                "\"2026-09-30T10:00:00Z\"",
                (ev, a) -> ev.clock().instant().toString());
        def(
                m,
                "formatDate",
                c,
                1,
                3,
                "formatDate(date, pattern = ISO, zone = \"UTC\")",
                "Formats an ISO-8601 date or epoch-millis number with a java.time pattern (yyyy-MM-dd HH:mm) in an IANA zone.",
                "formatDate('2026-09-30T10:00:00Z', 'yyyy-MM-dd')",
                "\"2026-09-30\"",
                (ev, a) -> {
                    ZoneId zone = zone(a, 2);
                    Instant t = instant(get(a, 0), zone, "formatDate");
                    String pattern = has(a, 1) ? str(a, 1) : "yyyy-MM-dd'T'HH:mm:ssXXX";
                    if (pattern.length() > ev.limits().maxRegexLength()) {
                        throw err("formatDate", "pattern is too long");
                    }
                    return DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH)
                            .withZone(zone)
                            .format(t);
                });
        def(
                m,
                "parseDate",
                c,
                1,
                3,
                "parseDate(text, pattern = ISO, zone = \"UTC\")",
                "Parses text (ISO-8601, or a java.time pattern) into an ISO-8601 UTC string.",
                "parseDate('30/09/2026', 'dd/MM/yyyy')",
                "\"2026-09-30T00:00:00Z\"",
                (ev, a) -> {
                    ZoneId zone = zone(a, 2);
                    if (!has(a, 1)) {
                        return instant(get(a, 0), zone, "parseDate").toString();
                    }
                    String pattern = str(a, 1);
                    if (pattern.length() > ev.limits().maxRegexLength()) {
                        throw err("parseDate", "pattern is too long");
                    }
                    TemporalAccessor t =
                            DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH)
                                    .parseBest(
                                            str(a, 0).strip(),
                                            ZonedDateTime::from,
                                            LocalDateTime::from,
                                            LocalDate::from);
                    if (t instanceof ZonedDateTime z) {
                        return z.toInstant().toString();
                    }
                    if (t instanceof LocalDateTime ldt) {
                        return ldt.atZone(zone).toInstant().toString();
                    }
                    return ((LocalDate) t).atStartOfDay(zone).toInstant().toString();
                });
        def(
                m,
                "addDays",
                c,
                2,
                3,
                "addDays(date, days, zone = \"UTC\")",
                "Adds (or subtracts, if negative) whole days, calendar-aware in the given zone.",
                "addDays('2026-09-30T00:00:00Z', 2)",
                "\"2026-10-02T00:00:00Z\"",
                (ev, a) -> {
                    ZoneId zone = zone(a, 2);
                    return instant(get(a, 0), zone, "addDays")
                            .atZone(zone)
                            .plusDays(lng(a, 1, "addDays"))
                            .toInstant()
                            .toString();
                });
        def(
                m,
                "addHours",
                c,
                2,
                2,
                "addHours(date, hours)",
                "Adds (or subtracts) whole hours.",
                "addHours('2026-09-30T00:00:00Z', 5)",
                "\"2026-09-30T05:00:00Z\"",
                (ev, a) ->
                        instant(get(a, 0), ZoneOffset.UTC, "addHours")
                                .plus(Duration.ofHours(lng(a, 1, "addHours")))
                                .toString());
        def(
                m,
                "diffDays",
                c,
                2,
                3,
                "diffDays(from, to, zone = \"UTC\")",
                "Whole days from the first date to the second (negative if it is earlier).",
                "diffDays('2026-09-01T00:00:00Z', '2026-09-11T00:00:00Z')",
                "10",
                (ev, a) -> {
                    ZoneId zone = zone(a, 2);
                    return ChronoUnit.DAYS.between(
                            instant(get(a, 0), zone, "diffDays").atZone(zone),
                            instant(get(a, 1), zone, "diffDays").atZone(zone));
                });
        def(
                m,
                "toEpoch",
                c,
                1,
                1,
                "toEpoch(date)",
                "Milliseconds since 1970-01-01T00:00:00Z.",
                "toEpoch('1970-01-01T00:00:01Z')",
                "1000",
                (ev, a) -> instant(get(a, 0), ZoneOffset.UTC, "toEpoch").toEpochMilli());
    }

    private static ZoneId zone(List<Object> a, int i) {
        return has(a, i) ? ZoneId.of(Values.toStr(get(a, i))) : ZoneOffset.UTC;
    }

    private static Instant instant(Object v, ZoneId zone, String fn) {
        try {
            if (v instanceof Long || v instanceof Double) {
                return Instant.ofEpochMilli(((Number) v).longValue());
            }
            if (v instanceof String s) {
                String t = s.strip();
                try {
                    return Instant.parse(t);
                } catch (DateTimeException ignored) {
                    // try the next ISO shape
                }
                try {
                    return OffsetDateTime.parse(t).toInstant();
                } catch (DateTimeException ignored) {
                    // try the next ISO shape
                }
                try {
                    return ZonedDateTime.parse(t).toInstant();
                } catch (DateTimeException ignored) {
                    // try the next ISO shape
                }
                try {
                    return LocalDateTime.parse(t).atZone(zone).toInstant();
                } catch (DateTimeException ignored) {
                    // try the next ISO shape
                }
                return LocalDate.parse(t).atStartOfDay(zone).toInstant();
            }
        } catch (DateTimeException e) {
            // The offending text is deliberately not quoted: it is data (often from a webhook) and
            // this message reaches traces.
            throw err(fn, "not a valid ISO-8601 date (" + Values.toStr(v).length() + " chars)");
        }
        throw err(
                fn,
                "expected an ISO-8601 date string or epoch milliseconds, got " + Values.typeOf(v));
    }
}
