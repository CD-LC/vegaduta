package com.agenticai.core.agents.tools.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link HttpRequestExecutor} safe mode: SSRF vectors. */
class HttpRequestExecutorSafeModeSsrfTest extends HttpRequestExecutorSafeModeTestSupport {

    // ------------------------------------------------------------------ SSRF vectors

    @Test
    void refusesEveryAlternativeSpellingOfAnInternalAddressWithoutSendingAnything() {
        RecordingExecutor executor = new RecordingExecutor(null);
        // Whatever the spelling, the JDK resolves it and the guard classifies the ADDRESS, not the
        // text. A
        // spelling the JDK cannot parse as a literal either has no URI host or fails to resolve -
        // also a
        // refusal. None of them may ever reach send().
        List<String> vectors =
                List.of(
                        "https://127.0.0.1/",
                        "https://2130706433/", // decimal dword for 127.0.0.1
                        "https://0x7f.0.0.1/", // hex octet
                        "https://0177.0.0.1/", // octal octet
                        "https://127.1/", // BSD shorthand
                        "https://[::1]/",
                        "https://[::ffff:127.0.0.1]/", // IPv4-mapped IPv6 loopback
                        "https://[::ffff:a9fe:a9fe]/", // IPv4-mapped 169.254.169.254
                        "https://[64:ff9b::7f00:1]/", // NAT64 onto 127.0.0.1
                        "https://[fd00::1]/", // unique-local
                        "https://169.254.169.254/latest/meta-data/",
                        "https://168.63.129.16/", // Azure WireServer
                        "https://100.64.0.1/", // CGNAT
                        "https://10.0.0.8/",
                        "https://192.168.1.1/",
                        "https://0.0.0.0/",
                        "https://localhost/");
        for (String url : vectors) {
            assertThrows(
                    WebPageFetchException.class,
                    () -> executor.executeSafe(HttpRequestSpec.get(url), "u1", readPolicy(), null),
                    "must be refused: " + url);
        }
        assertTrue(executor.sent.isEmpty(), "no refused URL may reach the network seam");
    }

    @Test
    void refusesEmbeddedCredentialsInTheUrl() {
        RecordingExecutor executor = new RecordingExecutor(null);
        assertThrows(
                WebPageFetchException.class,
                () ->
                        executor.executeSafe(
                                HttpRequestSpec.get("https://user:pw@" + HOST + "/"),
                                "u1",
                                readPolicy(),
                                null));
        assertTrue(executor.sent.isEmpty());
    }

    @Test
    void aRedirectToTheMetadataServiceIsRefusedOnTheHop() {
        RecordingExecutor executor = new RecordingExecutor(null);
        executor.responses.add(redirect(302, "https://169.254.169.254/latest/meta-data/iam/"));

        WebPageFetchException e =
                assertThrows(
                        WebPageFetchException.class,
                        () ->
                                executor.executeSafe(
                                        HttpRequestSpec.get("https://" + HOST + "/start"),
                                        "u1",
                                        readPolicy(),
                                        null));

        // OSS wording: the shared SsrfGuard's message (upstream's WebPageFetcher said "platform's
        // own network").
        assertTrue(e.getMessage().contains("private or internal network"), e.getMessage());
        assertEquals(1, executor.sent.size(), "the metadata URL itself is never requested");
    }

    @Test
    void aRedirectToAnIpv4MappedIpv6LoopbackIsRefusedOnTheHop() {
        RecordingExecutor executor = new RecordingExecutor(null);
        executor.responses.add(redirect(307, "https://[::ffff:127.0.0.1]:8080/admin"));

        assertThrows(
                WebPageFetchException.class,
                () ->
                        executor.executeSafe(
                                HttpRequestSpec.get("https://" + HOST + "/"),
                                "u1",
                                readPolicy(),
                                null));
        assertEquals(1, executor.sent.size());
    }

    @Test
    void aRedirectDowngradingToPlainHttpIsRefusedEvenToAPublicHost() {
        RecordingExecutor executor = new RecordingExecutor(null);
        executor.responses.add(redirect(301, "http://" + OTHER_HOST + "/"));

        WebPageFetchException e =
                assertThrows(
                        WebPageFetchException.class,
                        () ->
                                executor.executeSafe(
                                        HttpRequestSpec.get("https://" + HOST + "/"),
                                        "u1",
                                        readPolicy(),
                                        null));

        assertTrue(e.getMessage().contains("https"), e.getMessage());
        assertEquals(1, executor.sent.size());
    }

    @Test
    void redirectsAreCappedAtThree() {
        RecordingExecutor executor = new RecordingExecutor(null);
        for (int i = 0; i < 6; i++) {
            executor.responses.add(redirect(302, "/hop" + i));
        }

        WebPageFetchException e =
                assertThrows(
                        WebPageFetchException.class,
                        () ->
                                executor.executeSafe(
                                        HttpRequestSpec.get("https://" + HOST + "/"),
                                        "u1",
                                        readPolicy(),
                                        null));

        assertTrue(e.getMessage().contains("more than 3 times"), e.getMessage());
        assertEquals(
                4,
                executor.sent.size(),
                "the first request plus three followed redirects, then it stops");
    }
}
