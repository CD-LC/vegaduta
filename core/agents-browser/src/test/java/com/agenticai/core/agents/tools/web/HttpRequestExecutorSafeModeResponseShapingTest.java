package com.agenticai.core.agents.tools.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** {@link HttpRequestExecutor} safe mode: response size caps and shaping. */
class HttpRequestExecutorSafeModeResponseShapingTest extends HttpRequestExecutorSafeModeTestSupport {

    @Test
    void theResponseReadIsBoundedByTheByteCapAndMarkedTruncated() {
        RecordingExecutor executor = new RecordingExecutor(null);
        CountingStream body = new CountingStream(10_000_000);
        executor.responses.add(response(200, "text/plain", body));
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
                        HttpRequestSpec.get("https://" + HOST + "/big"), "u1", capped, null);

        // OSS: the body is read by send() over the pinned-DNS client, bounded by its own 2MB
        // transport cap (mirrored
        // by Canned#toRaw), and the policy's smaller byte cap is applied on top in readResultSafe -
        // so the
        // upstream "cap + 1 peek byte consumed" property belongs to send(), not to this seam.
        assertTrue(
                body.consumed <= TRANSPORT_CAP_BYTES,
                "never more than the transport cap is pulled: " + body.consumed);
        assertTrue(result.bodyTruncated());
        assertTrue(
                result.body().endsWith(HttpRequestExecutor.TRUNCATION_MARKER),
                "truncation is visible to the model");
        assertEquals(
                2_048 + HttpRequestExecutor.TRUNCATION_MARKER.length(), result.body().length());
    }

    @Test
    void theCharCapStillClipsWithTheSameMarker() {
        RecordingExecutor executor = new RecordingExecutor(null);
        executor.responses.add(
                response(
                        200,
                        "text/plain",
                        new ByteArrayInputStream(
                                "b".repeat(5_000).getBytes(StandardCharsets.UTF_8))));

        HttpRequestResult result =
                executor.executeSafe(
                        new HttpRequestSpec(
                                "GET",
                                "https://" + HOST + "/",
                                Map.of(),
                                Map.of(),
                                null,
                                null,
                                null,
                                300),
                        "u1",
                        readPolicy(),
                        null);

        assertTrue(result.bodyTruncated());
        assertEquals(300 + HttpRequestExecutor.TRUNCATION_MARKER.length(), result.body().length());
    }

    @Test
    void responseContentTypesOffTheAllowListAreNotShown() {
        RecordingExecutor executor = new RecordingExecutor(null);
        executor.responses.add(
                response(
                        200,
                        "application/x-evil-thing",
                        new ByteArrayInputStream("payload".getBytes(StandardCharsets.UTF_8))));
        executor.responses.add(
                response(
                        200,
                        "application/vnd.api+json",
                        new ByteArrayInputStream("{\"a\":1}".getBytes(StandardCharsets.UTF_8))));
        executor.responses.add(
                response(
                        200,
                        "text/html; charset=utf-8",
                        new ByteArrayInputStream("<p>hi</p>".getBytes(StandardCharsets.UTF_8))));
        executor.responses.add(
                response(
                        200,
                        null,
                        new ByteArrayInputStream("no type".getBytes(StandardCharsets.UTF_8))));
        executor.responses.add(
                response(200, "image/png", new ByteArrayInputStream(new byte[] {1, 2, 3})));

        HttpRequestResult blocked =
                executor.executeSafe(
                        HttpRequestSpec.get("https://" + HOST + "/a"), "u1", readPolicy(), null);
        assertTrue(blocked.body().contains("not allowed"), blocked.body());
        assertFalse(blocked.body().contains("payload"));

        assertTrue(
                executor.executeSafe(
                                HttpRequestSpec.get("https://" + HOST + "/b"),
                                "u1",
                                readPolicy(),
                                null)
                        .body()
                        .contains("\"a\""));
        assertTrue(
                executor.executeSafe(
                                HttpRequestSpec.get("https://" + HOST + "/c"),
                                "u1",
                                readPolicy(),
                                null)
                        .body()
                        .contains("hi"));
        assertTrue(
                executor.executeSafe(
                                HttpRequestSpec.get("https://" + HOST + "/d"),
                                "u1",
                                readPolicy(),
                                null)
                        .body()
                        .contains("no type"));
        assertTrue(
                executor.executeSafe(
                                HttpRequestSpec.get("https://" + HOST + "/e"),
                                "u1",
                                readPolicy(),
                                null)
                        .body()
                        .contains("binary"));
    }

    @Test
    void setCookieNeverReachesTheModel() {
        RecordingExecutor executor = new RecordingExecutor(null);
        executor.responses.add(
                response(
                        200,
                        "application/json",
                        new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)),
                        "Set-Cookie",
                        "session=abc123",
                        "X-Rate-Limit",
                        "99"));

        HttpRequestResult result =
                executor.executeSafe(
                        HttpRequestSpec.get("https://" + HOST + "/"), "u1", readPolicy(), null);

        assertFalse(result.headers().containsKey("set-cookie"));
        assertEquals("99", result.headers().get("x-rate-limit"));
    }

    @Test
    void anHttp402IsReturnedAsIsWithoutAnyAutoPayment() {
        RecordingExecutor executor = new RecordingExecutor(null);
        executor.responses.add(
                response(
                        402,
                        "application/json",
                        new ByteArrayInputStream(
                                "{\"accepts\":[]}".getBytes(StandardCharsets.UTF_8))));

        HttpRequestResult result =
                executor.executeSafe(
                        HttpRequestSpec.get("https://" + HOST + "/paid"), "u1", readPolicy(), null);

        assertEquals(402, result.status());
        assertEquals(1, executor.sent.size(), "no retry with a payment header");
        assertNull(executor.sent.get(0).header("x-payment"));
    }
}
