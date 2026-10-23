package com.agenticai.core.chat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Reads how long a provider asked the caller to wait out of a failure. Spring AI's default error
 * handler throws {@code "<status> - <body>"} and drops the response headers, so two carriers are
 * read: the headers when the exception still has them (the streaming client, a {@code
 * RestClientResponseException}), and otherwise the wait the provider states in its error body
 * ("Please try again in 3.5s", Gemini's {@code retryDelay}). Pure and static.
 */
final class RetryAfter {

    private static final int MAX_CAUSE_DEPTH = 12;

    private static final Pattern TRY_AGAIN_IN =
            Pattern.compile("(?i)try again in\\s+(\\d+(?:\\.\\d+)?)\\s*(ms|s|sec|secs|seconds|m|min|minutes)?");
    private static final Pattern RETRY_DELAY = Pattern.compile("(?i)\"?retryDelay\"?\\s*[:=]\\s*\"?(\\d+(?:\\.\\d+)?)s");
    private static final Pattern RETRY_AFTER_TEXT = Pattern.compile("(?i)retry[- ]after[^0-9]{0,6}(\\d+(?:\\.\\d+)?)");

    private RetryAfter() {}

    /** The wait the provider asked for, found anywhere in {@code failure}'s cause chain. */
    static Optional<Duration> of(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            Optional<Duration> found = fromHeaders(headersOf(current));
            if (found.isEmpty()) {
                found = fromText(current instanceof WebClientResponseException w ? w.getResponseBodyAsString() : null);
            }
            if (found.isEmpty()) {
                found = fromText(current.getMessage());
            }
            if (found.isPresent()) {
                return found;
            }
            Throwable cause = current.getCause();
            current = cause == current ? null : cause;
        }
        return Optional.empty();
    }

    private static HttpHeaders headersOf(Throwable t) {
        if (t instanceof WebClientResponseException w) {
            return w.getHeaders();
        }
        if (t instanceof RestClientResponseException r) {
            return r.getResponseHeaders();
        }
        return null;
    }

    static Optional<Duration> fromHeaders(HttpHeaders headers) {
        if (headers == null) {
            return Optional.empty();
        }
        String millis = headers.getFirst("retry-after-ms");
        if (millis != null) {
            try {
                return positive(Duration.ofMillis((long) Double.parseDouble(millis.trim())));
            } catch (NumberFormatException ignored) {
                // fall through to Retry-After
            }
        }
        String value = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String trimmed = value.trim();
        try {
            return positive(Duration.ofMillis((long) (Double.parseDouble(trimmed) * 1000)));
        } catch (NumberFormatException notSeconds) {
            // an HTTP-date
        }
        try {
            Instant when = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            return positive(Duration.between(Instant.now(), when));
        } catch (DateTimeParseException notADate) {
            return Optional.empty();
        }
    }

    static Optional<Duration> fromText(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Matcher tryAgain = TRY_AGAIN_IN.matcher(text);
        if (tryAgain.find()) {
            double amount = Double.parseDouble(tryAgain.group(1));
            String unit = tryAgain.group(2) == null ? "s" : tryAgain.group(2).toLowerCase(java.util.Locale.ROOT);
            double millis =
                    switch (unit) {
                        case "ms" -> amount;
                        case "m", "min", "minutes" -> amount * 60_000;
                        default -> amount * 1000;
                    };
            return positive(Duration.ofMillis((long) millis));
        }
        Matcher delay = RETRY_DELAY.matcher(text);
        if (delay.find()) {
            return positive(Duration.ofMillis((long) (Double.parseDouble(delay.group(1)) * 1000)));
        }
        Matcher after = RETRY_AFTER_TEXT.matcher(text);
        if (after.find()) {
            return positive(Duration.ofMillis((long) (Double.parseDouble(after.group(1)) * 1000)));
        }
        return Optional.empty();
    }

    private static Optional<Duration> positive(Duration d) {
        return d.isNegative() || d.isZero() ? Optional.empty() : Optional.of(d);
    }
}
