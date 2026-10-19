package com.agenticai.core.agents.tools.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** {@link HttpRequestExecutor}: the legacy path stays untouched. */
class HttpRequestExecutorSafeModeLegacyPathTest extends HttpRequestExecutorSafeModeTestSupport {

    // ------------------------------------------------------------------ legacy path untouched

    @Test
    void theLegacyExecuteStillAcceptsInlineVaultReferencesAndAnyMethod() {
        // Safe mode must not change what the default (flag off) tool and the workflow http node do.
        RecordingExecutor executor = new RecordingExecutor(resolverWith("STRIPE_KEY", SECRET));
        executor.responses.add(json("{}"));

        executor.execute(
                spec(
                        "POST",
                        "https://" + HOST + "/",
                        Map.of("Authorization", "Bearer {{secret.STRIPE_KEY}}"),
                        "{}"),
                "u1");

        assertEquals("Bearer " + SECRET, executor.sent.get(0).header("authorization"));
        assertEquals("POST", executor.sent.get(0).method());
    }

    @Test
    void readCappedNeverReadsPastTheCap() {
        CountingStream stream = new CountingStream(100_000);
        HttpRequestExecutor.CappedBody body =
                HttpRequestExecutor.readCapped(stream, 1_500, Duration.ofSeconds(5));
        assertEquals(1_500, body.bytes().length);
        assertTrue(body.truncated());
        assertEquals(
                1_501, stream.consumed, "the cap plus one peek byte, never a whole chunk more");
    }

    @Test
    void aBodyExactlyTheCapLongIsNotReportedAsTruncated() {
        HttpRequestExecutor.CappedBody exact =
                HttpRequestExecutor.readCapped(
                        new CountingStream(1_500), 1_500, Duration.ofSeconds(5));
        assertEquals(1_500, exact.bytes().length);
        assertFalse(exact.truncated(), "nothing was dropped");

        RecordingExecutor executor = new RecordingExecutor(null);
        executor.responses.add(response(200, "text/plain", new CountingStream(2_048)));
        HttpRequestPolicy capped =
                new HttpRequestPolicy(
                        HttpRequestSafetyConfig.READ_METHODS,
                        false,
                        1024,
                        2_048,
                        3,
                        List.of("text/*"),
                        Set.of(),
                        List.of(),
                        List.of(),
                        true);

        HttpRequestResult result =
                executor.executeSafe(
                        HttpRequestSpec.get("https://" + HOST + "/exact"), "u1", capped, null);

        assertFalse(result.bodyTruncated());
        assertFalse(result.body().endsWith(HttpRequestExecutor.TRUNCATION_MARKER));
    }

    /**
     * Sends a byte, then stalls: the slowloris shape (headers arrived, the body never finishes).
     */
    private static final class DrippingStream extends InputStream {
        volatile boolean closed;

        @Override
        public int read() throws IOException {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            }
            return 'a';
        }

        @Override
        public int read(byte[] buffer, int off, int len) throws IOException {
            int b = read();
            buffer[off] = (byte) b;
            return 1;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    @Test
    void aBodyThatNeverFinishesIsAbortedAtTheDeadline() {
        DrippingStream stream = new DrippingStream();
        long started = System.nanoTime();

        WebPageFetchException failure =
                assertThrows(
                        WebPageFetchException.class,
                        () ->
                                HttpRequestExecutor.readCapped(
                                        stream, 1_000, Duration.ofMillis(200)));

        assertTrue(failure.getMessage().contains("too long"), failure.getMessage());
        assertTrue(stream.closed, "the stream is closed so the connection is abandoned");
        assertTrue(
                Duration.ofNanos(System.nanoTime() - started).toSeconds() < 5,
                "bounded by the deadline, not the drip");
    }
}
