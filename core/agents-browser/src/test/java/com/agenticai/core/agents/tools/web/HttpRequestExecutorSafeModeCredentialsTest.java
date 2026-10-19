package com.agenticai.core.agents.tools.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link HttpRequestExecutor} safe mode: secret references and credential handling. */
class HttpRequestExecutorSafeModeCredentialsTest extends HttpRequestExecutorSafeModeTestSupport {

    // ------------------------------------------------------------------ credentials

    @Test
    void inlineVaultReferencesAreRefusedEverywhereAndNothingIsSent() {
        RecordingExecutor executor = new RecordingExecutor(resolverWith("STRIPE_KEY", SECRET));
        String ref = "{{secret.STRIPE_KEY}}";
        List<HttpRequestSpec> specs =
                List.of(
                        spec("GET", "https://" + HOST + "/?k=" + ref, Map.of(), null),
                        spec(
                                "GET",
                                "https://" + HOST + "/",
                                Map.of("X-Custom", "Bearer " + ref),
                                null),
                        new HttpRequestSpec(
                                "GET",
                                "https://" + HOST + "/",
                                Map.of(),
                                Map.of("k", ref),
                                null,
                                null,
                                null,
                                null),
                        spec("POST", "https://" + HOST + "/", Map.of(), "{\"k\":\"" + ref + "\"}"),
                        spec(
                                "GET",
                                "https://" + HOST + "/",
                                Map.of("X-Custom", "{{ secret:stripe_key }}"),
                                null));
        for (HttpRequestSpec s : specs) {
            HttpRequestPolicy policy = "POST".equals(s.method()) ? writePolicy() : readPolicy();
            WebPageFetchException e =
                    assertThrows(
                            WebPageFetchException.class,
                            () -> executor.executeSafe(s, "u1", policy, null));
            assertTrue(e.getMessage().contains("'auth'"), e.getMessage());
            assertFalse(e.getMessage().contains(SECRET));
        }
        assertTrue(executor.sent.isEmpty());
    }

    @Test
    void aCredentialIsRefusedWhenNoAllowListIsConfigured() {
        RecordingExecutor executor = new RecordingExecutor(resolverWith("STRIPE_KEY", SECRET));
        HttpRequestCredential credential =
                new HttpRequestCredential("STRIPE_KEY", HttpRequestCredential.Type.BEARER, null);

        WebPageFetchException e =
                assertThrows(
                        WebPageFetchException.class,
                        () ->
                                executor.executeSafe(
                                        HttpRequestSpec.get("https://" + HOST + "/"),
                                        "u1",
                                        readPolicy(),
                                        credential));

        assertTrue(e.getMessage().contains("allow-list"), e.getMessage());
        assertFalse(e.getMessage().contains(SECRET));
        assertTrue(executor.sent.isEmpty());
    }

    @Test
    void aBearerCredentialIsInjectedAndNeverComesBackInTheResultOrAnError() throws Exception {
        RecordingExecutor executor = new RecordingExecutor(resolverWith("STRIPE_KEY", SECRET));
        HttpRequestPolicy listed =
                policy(HttpRequestSafetyConfig.READ_METHODS, false, List.of(), List.of(HOST));
        HttpRequestCredential credential =
                new HttpRequestCredential("stripe_key", HttpRequestCredential.Type.BEARER, null);
        // An upstream that echoes the request it received - the classic way a key reaches a
        // transcript.
        executor.responses.add(
                response(
                        200,
                        "application/json",
                        new ByteArrayInputStream(
                                ("{\"echo\":\"Authorization: Bearer " + SECRET + "\"}")
                                        .getBytes(StandardCharsets.UTF_8)),
                        "X-Echo-Auth",
                        "Bearer " + SECRET));

        HttpRequestResult result =
                executor.executeSafe(
                        HttpRequestSpec.get("https://" + HOST + "/me"), "u1", listed, credential);

        assertEquals("Bearer " + SECRET, executor.sent.get(0).header("authorization"));
        String everything = MAPPER.writeValueAsString(result.toJson(MAPPER));
        assertFalse(
                everything.contains(SECRET),
                "the secret must be redacted out of body, headers and url: " + everything);
        assertTrue(everything.contains("[redacted:secret]"));
    }

    @Test
    void aMissingCredentialNeverEchoesTheSuppliedNameBack() {
        // A pasted alphanumeric API key passes the credential-name pattern; the not-found message
        // must not repeat it.
        RecordingExecutor executor = new RecordingExecutor(resolverWith("STRIPE_KEY", SECRET));
        HttpRequestPolicy listed =
                policy(HttpRequestSafetyConfig.READ_METHODS, false, List.of(), List.of(HOST));
        HttpRequestCredential credential =
                new HttpRequestCredential(SECRET, HttpRequestCredential.Type.BEARER, null);

        WebPageFetchException e =
                assertThrows(
                        WebPageFetchException.class,
                        () ->
                                executor.executeSafe(
                                        HttpRequestSpec.get("https://" + HOST + "/"),
                                        "u1",
                                        listed,
                                        credential));

        assertTrue(e.getMessage().contains("could not be found"), e.getMessage());
        assertFalse(e.getMessage().toLowerCase(Locale.ROOT).contains(SECRET), e.getMessage());
        assertTrue(executor.sent.isEmpty());
    }

    @Test
    void aBasicCredentialIsBase64EncodedAndItsEncodedFormIsRedactedToo() throws Exception {
        String userPass = "svc:" + SECRET;
        RecordingExecutor executor = new RecordingExecutor(resolverWith("SVC_LOGIN", userPass));
        HttpRequestPolicy listed =
                policy(HttpRequestSafetyConfig.READ_METHODS, false, List.of(), List.of(HOST));
        String encoded =
                Base64.getEncoder().encodeToString(userPass.getBytes(StandardCharsets.UTF_8));
        executor.responses.add(json("{\"seen\":\"Basic " + encoded + "\"}"));

        HttpRequestResult result =
                executor.executeSafe(
                        HttpRequestSpec.get("https://" + HOST + "/"),
                        "u1",
                        listed,
                        new HttpRequestCredential(
                                "SVC_LOGIN", HttpRequestCredential.Type.BASIC, null));

        assertEquals("Basic " + encoded, executor.sent.get(0).header("authorization"));
        String everything = MAPPER.writeValueAsString(result.toJson(MAPPER));
        assertFalse(everything.contains(encoded));
        assertFalse(everything.contains(SECRET));
    }

    @Test
    void aHeaderCredentialUsesTheNamedHeader() {
        RecordingExecutor executor = new RecordingExecutor(resolverWith("PARTNER_KEY", SECRET));
        HttpRequestPolicy listed =
                policy(HttpRequestSafetyConfig.READ_METHODS, false, List.of(), List.of(HOST));
        executor.responses.add(json("{}"));

        executor.executeSafe(
                HttpRequestSpec.get("https://" + HOST + "/"),
                "u1",
                listed,
                new HttpRequestCredential(
                        "PARTNER_KEY", HttpRequestCredential.Type.HEADER, "X-Partner-Key"));

        assertEquals(SECRET, executor.sent.get(0).header("x-partner-key"));
        assertNull(executor.sent.get(0).header("authorization"));
    }

    @Test
    void aMissingVaultSecretFailsWithItsNameAndNothingIsSent() {
        RecordingExecutor executor = new RecordingExecutor(resolverWith("NOPE", null));
        HttpRequestPolicy listed =
                policy(HttpRequestSafetyConfig.READ_METHODS, false, List.of(), List.of(HOST));

        WebPageFetchException e =
                assertThrows(
                        WebPageFetchException.class,
                        () ->
                                executor.executeSafe(
                                        HttpRequestSpec.get("https://" + HOST + "/"),
                                        "u1",
                                        listed,
                                        new HttpRequestCredential(
                                                "NOPE", HttpRequestCredential.Type.BEARER, null)));

        assertFalse(
                e.getMessage().contains("NOPE"),
                "the supplied name is never echoed: " + e.getMessage());
        assertTrue(executor.sent.isEmpty());
    }

    @Test
    void noVaultAtAllFailsClosedForACredential() {
        RecordingExecutor executor = new RecordingExecutor(null);
        HttpRequestPolicy listed =
                policy(HttpRequestSafetyConfig.READ_METHODS, false, List.of(), List.of(HOST));
        assertThrows(
                WebPageFetchException.class,
                () ->
                        executor.executeSafe(
                                HttpRequestSpec.get("https://" + HOST + "/"),
                                "u1",
                                listed,
                                new HttpRequestCredential(
                                        "K", HttpRequestCredential.Type.BEARER, null)));
        assertTrue(executor.sent.isEmpty());
    }

    @Test
    void theCredentialIsDroppedOnACrossOriginRedirectAndKeptOnASameOriginOne() {
        RecordingExecutor executor = new RecordingExecutor(resolverWith("STRIPE_KEY", SECRET));
        // Both hosts allow-listed: the allow-list alone must not be enough to forward the key.
        HttpRequestPolicy listed =
                policy(
                        HttpRequestSafetyConfig.READ_METHODS,
                        false,
                        List.of(),
                        List.of(HOST, OTHER_HOST));
        HttpRequestCredential credential =
                new HttpRequestCredential("STRIPE_KEY", HttpRequestCredential.Type.BEARER, null);
        executor.responses.add(redirect(302, "/same-origin"));
        executor.responses.add(redirect(302, "https://" + OTHER_HOST + "/other-origin"));
        executor.responses.add(json("{}"));

        executor.executeSafe(
                HttpRequestSpec.get("https://" + HOST + "/start"), "u1", listed, credential);

        assertEquals(3, executor.sent.size());
        assertEquals("Bearer " + SECRET, executor.sent.get(0).header("authorization"));
        assertEquals(
                "Bearer " + SECRET,
                executor.sent.get(1).header("authorization"),
                "same origin keeps it");
        assertNull(
                executor.sent.get(2).header("authorization"),
                "a different host never receives the credential");
    }

    @Test
    void aSchemeOrPortChangeCountsAsAnotherOrigin() {
        assertTrue(
                HttpRequestExecutor.sameOrigin(
                        URI.create("https://a.example/x"), URI.create("https://A.example/y")));
        assertTrue(
                HttpRequestExecutor.sameOrigin(
                        URI.create("https://a.example/x"), URI.create("https://a.example:443/y")));
        assertFalse(
                HttpRequestExecutor.sameOrigin(
                        URI.create("https://a.example/x"), URI.create("https://a.example:8443/y")));
        assertFalse(
                HttpRequestExecutor.sameOrigin(
                        URI.create("https://a.example/x"), URI.create("http://a.example/y")));
        assertFalse(
                HttpRequestExecutor.sameOrigin(
                        URI.create("https://a.example/x"), URI.create("https://b.example/y")));
    }

    @Test
    void aFailureReasonThatContainsTheSecretIsRedacted() {
        RecordingExecutor executor =
                new RecordingExecutor(resolverWith("STRIPE_KEY", SECRET)) {
                    @Override
                    protected RawResponse send(
                            URI uri,
                            String method,
                            Map<String, String> headers,
                            String body,
                            Duration timeout) {
                        throw new WebPageFetchException("upstream rejected key " + SECRET);
                    }
                };
        HttpRequestPolicy listed =
                policy(HttpRequestSafetyConfig.READ_METHODS, false, List.of(), List.of(HOST));

        WebPageFetchException e =
                assertThrows(
                        WebPageFetchException.class,
                        () ->
                                executor.executeSafe(
                                        HttpRequestSpec.get("https://" + HOST + "/"),
                                        "u1",
                                        listed,
                                        new HttpRequestCredential(
                                                "STRIPE_KEY",
                                                HttpRequestCredential.Type.BEARER,
                                                null)));

        assertFalse(e.getMessage().contains(SECRET), e.getMessage());
        assertTrue(e.getMessage().contains("[redacted:secret]"), e.getMessage());
    }
}
