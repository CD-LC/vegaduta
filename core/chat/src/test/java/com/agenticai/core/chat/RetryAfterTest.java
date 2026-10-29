package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * How long a provider asked the caller to wait, recovered from whichever carrier survives the
 * wrapping: headers when the exception still has them, the error body's own hint ("try again in
 * 3.5s", Gemini's {@code retryDelay}) when it doesn't, anywhere in the cause chain.
 */
class RetryAfterTest {

    // --- fromHeaders ---------------------------------------------------------

    @Test
    void retryAfterMsWinsOverThePlainRetryAfterHeader() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("retry-after-ms", "2500");
        headers.add(HttpHeaders.RETRY_AFTER, "30");

        assertEquals(Optional.of(Duration.ofMillis(2500)), RetryAfter.fromHeaders(headers));
    }

    @Test
    void aGarbageRetryAfterMsFallsThroughToRetryAfter() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("retry-after-ms", "not-a-number");
        headers.add(HttpHeaders.RETRY_AFTER, "7");

        assertEquals(Optional.of(Duration.ofSeconds(7)), RetryAfter.fromHeaders(headers));
    }

    @Test
    void retryAfterAcceptsFractionalSecondsAndHttpDates() {
        HttpHeaders seconds = new HttpHeaders();
        seconds.add(HttpHeaders.RETRY_AFTER, "1.5");
        assertEquals(Optional.of(Duration.ofMillis(1500)), RetryAfter.fromHeaders(seconds));

        HttpHeaders date = new HttpHeaders();
        date.add(HttpHeaders.RETRY_AFTER,
            DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.now().plusSeconds(60).atZone(ZoneOffset.UTC)));
        Optional<Duration> wait = RetryAfter.fromHeaders(date);
        assertTrue(wait.isPresent() && wait.get().toSeconds() <= 60 && wait.get().toSeconds() > 50,
            "an HTTP-date ~60s out should yield roughly a minute, got " + wait);
    }

    @Test
    void unusableHeaderContentIsEmptyNotAnError() {
        assertTrue(RetryAfter.fromHeaders(null).isEmpty());
        assertTrue(RetryAfter.fromHeaders(HttpHeaders.EMPTY).isEmpty());

        HttpHeaders past = new HttpHeaders();
        past.add(HttpHeaders.RETRY_AFTER,
            DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.now().minusSeconds(60).atZone(ZoneOffset.UTC)));
        assertTrue(RetryAfter.fromHeaders(past).isEmpty(), "a date in the past is no wait at all");

        HttpHeaders garbage = new HttpHeaders();
        garbage.add(HttpHeaders.RETRY_AFTER, "whenever");
        assertTrue(RetryAfter.fromHeaders(garbage).isEmpty());
    }

    // --- fromText ------------------------------------------------------------

    @Test
    void theTryAgainInShapeReadsEveryUnit() {
        assertEquals(Optional.of(Duration.ofMillis(500)), RetryAfter.fromText("Please try again in 500ms"));
        assertEquals(Optional.of(Duration.ofMillis(3500)), RetryAfter.fromText("Please try again in 3.5s"));
        assertEquals(Optional.of(Duration.ofSeconds(4)), RetryAfter.fromText("try again in 4 seconds"));
        assertEquals(Optional.of(Duration.ofMinutes(2)), RetryAfter.fromText("try again in 2 min"));
        assertEquals(Optional.of(Duration.ofMinutes(1)), RetryAfter.fromText("try again in 1m"));
        // No unit means seconds.
        assertEquals(Optional.of(Duration.ofSeconds(9)), RetryAfter.fromText("try again in 9"));
    }

    @Test
    void theGeminiRetryDelayShapeIsRecognized() {
        assertEquals(Optional.of(Duration.ofSeconds(20)),
            RetryAfter.fromText("{\"error\":{\"retryDelay\":\"20s\"}}"));
        assertEquals(Optional.of(Duration.ofMillis(1500)),
            RetryAfter.fromText("retryDelay: \"1.5s\""));
    }

    @Test
    void aBareRetryAfterMentionInTextIsReadToo() {
        assertEquals(Optional.of(Duration.ofSeconds(12)), RetryAfter.fromText("Retry-After: 12"));
        assertEquals(Optional.of(Duration.ofSeconds(3)), RetryAfter.fromText("retry after 3"));
    }

    @Test
    void unusableTextIsEmptyNotAnError() {
        assertTrue(RetryAfter.fromText(null).isEmpty());
        assertTrue(RetryAfter.fromText("   ").isEmpty());
        assertTrue(RetryAfter.fromText("no wait hinted here").isEmpty());
    }

    // --- of(Throwable): the carrier hunt ---------------------------------------

    @Test
    void headersOfAWebClientResponseExceptionAreRead() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, "5");
        WebClientResponseException wcre = WebClientResponseException.create(
            429, "Too Many Requests", headers, new byte[0], StandardCharsets.UTF_8);

        assertEquals(Optional.of(Duration.ofSeconds(5)), RetryAfter.of(wcre));
    }

    @Test
    void theBodyOfAWebClientResponseExceptionIsReadWhenNoHeadersSurvive() {
        WebClientResponseException wcre = WebClientResponseException.create(
            429, "Too Many Requests", HttpHeaders.EMPTY,
            "{\"error\":{\"message\":\"Please try again in 2.5s\"}}".getBytes(StandardCharsets.UTF_8),
            StandardCharsets.UTF_8);

        assertEquals(Optional.of(Duration.ofMillis(2500)), RetryAfter.of(wcre));
    }

    @Test
    void aRestClientResponseExceptionHeadersAreRead() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, "8");
        RestClientResponseException rcre = new RestClientResponseException(
            "Too Many Requests", 429, "Too Many Requests", headers, new byte[0], StandardCharsets.UTF_8);

        assertEquals(Optional.of(Duration.ofSeconds(8)), RetryAfter.of(rcre));
    }

    @Test
    void theHintIsFoundBuriedInACauseChain() {
        RuntimeException wrapped = new RuntimeException("outer",
            new RuntimeException("mid", new RuntimeException("try again in 11s")));

        assertEquals(Optional.of(Duration.ofSeconds(11)), RetryAfter.of(wrapped));
    }

    @Test
    void noHintAnywhereIsEmptyAndASelfCauseDoesNotHang() {
        assertTrue(RetryAfter.of(new RuntimeException("plain failure")).isEmpty());

        RuntimeException loop = new RuntimeException("loop") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        assertTrue(RetryAfter.of(loop).isEmpty());
    }
}
