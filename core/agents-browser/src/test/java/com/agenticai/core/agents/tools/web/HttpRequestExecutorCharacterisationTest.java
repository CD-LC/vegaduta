package com.agenticai.core.agents.tools.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.tools.x402.X402PaymentService;
import com.agenticai.core.secrets.SecretRedactor;
import com.agenticai.core.secrets.SecretReferenceResolver;
import com.agenticai.core.secrets.SecretVaultService;
import com.agenticai.core.security.TenantContext;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Characterisation of the legacy {@link HttpRequestExecutor#execute} pipeline (validate, resolve
 * secrets, host policy, send, redirect, read and redact). Written BEFORE the pipeline split and
 * kept green after it: every refusal path of {@code execute} is pinned here, complementing the
 * attack-shaped {@link HttpRequestExecutorSecurityTest} and the safe-mode suite.
 */
class HttpRequestExecutorCharacterisationTest {

    private static final String HOST = "203.0.113.10";
    private static final String OTHER = "203.0.113.11";
    private static final String SECRET = "sk-live-characterise-987654";

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    record Sent(URI uri, String method, Map<String, String> headers, String body) {
        String header(String name) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name)) {
                    return e.getValue();
                }
            }
            return null;
        }
    }

    static class Recording extends HttpRequestExecutor {
        final List<Sent> sent = new ArrayList<>();
        final Deque<HttpRequestExecutor.RawResponse> responses = new ArrayDeque<>();

        Recording(SecretReferenceResolver resolver, X402PaymentService x402) {
            super(
                    provider(resolver),
                    provider(new SecretRedactor()),
                    provider(null),
                    provider(x402));
        }

        @Override
        protected RawResponse send(
                URI uri,
                String method,
                Map<String, String> headers,
                String body,
                Duration timeout) {
            sent.add(new Sent(uri, method, new LinkedHashMap<>(headers), body));
            RawResponse next = responses.poll();
            if (next == null) {
                throw new AssertionError("unexpected outbound request: " + method + " " + uri);
            }
            return next;
        }
    }

    @SuppressWarnings("unchecked")
    static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    static HttpRequestExecutor.RawResponse raw(
            int status, String contentType, String body, String... headerPairs) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        if (contentType != null) {
            headers.put("content-type", List.of(contentType));
        }
        for (int i = 0; i + 1 < headerPairs.length; i += 2) {
            headers.put(headerPairs[i].toLowerCase(), List.of(headerPairs[i + 1]));
        }
        return new HttpRequestExecutor.RawResponse(
                status, headers, body.getBytes(StandardCharsets.UTF_8), false);
    }

    static HttpRequestExecutor.RawResponse redirect(int status, String location) {
        return raw(status, null, "", "Location", location);
    }

    static SecretReferenceResolver resolverWith(String name, String value) {
        SecretVaultService vault = mock(SecretVaultService.class);
        when(vault.resolveForRun(eq(name), any())).thenReturn(Optional.ofNullable(value));
        when(vault.resolveForDestination(eq(name), any(), any()))
                .thenReturn(Optional.ofNullable(value));
        return new SecretReferenceResolver(vault);
    }

    private static HttpRequestSpec spec(
            String method, String url, Map<String, String> headers, String body, String bodyType) {
        return new HttpRequestSpec(method, url, headers, Map.of(), body, bodyType, null, null);
    }

    // ------------------------------------------------------------ validate stage

    @Test
    void aNullSpecAndAnUnsupportedMethodAreRefused() {
        Recording ex = new Recording(null, null);
        WebPageFetchException none =
                assertThrows(WebPageFetchException.class, () -> ex.execute(null, "u"));
        assertEquals("no request was given", none.getMessage());
        WebPageFetchException bad =
                assertThrows(
                        WebPageFetchException.class,
                        () -> ex.execute(spec("TRACE", "https://" + HOST + "/", null, null, null), "u"));
        assertTrue(bad.getMessage().contains("'TRACE' is not supported"), bad.getMessage());
        assertTrue(ex.sent.isEmpty());
    }

    @Test
    void timeoutAndBodyCharClampsAreStable() {
        assertEquals(15, HttpRequestExecutor.clampTimeout(null));
        assertEquals(15, HttpRequestExecutor.clampTimeout(0));
        assertEquals(30, HttpRequestExecutor.clampTimeout(500));
        assertEquals(7, HttpRequestExecutor.clampTimeout(7));
        assertEquals(20_000, HttpRequestExecutor.clampMaxChars(null));
        assertEquals(200, HttpRequestExecutor.clampMaxChars(5));
        assertEquals(60_000, HttpRequestExecutor.clampMaxChars(999_999));
    }

    @Test
    void forbiddenAndMalformedHeadersAreRefusedBeforeAnythingIsSent() {
        Recording ex = new Recording(null, null);
        for (String name : List.of("Host", "content-length", "Transfer-Encoding", "Connection")) {
            WebPageFetchException e =
                    assertThrows(
                            WebPageFetchException.class,
                            () ->
                                    ex.execute(
                                            spec(
                                                    "GET",
                                                    "https://" + HOST + "/",
                                                    Map.of(name, "x"),
                                                    null,
                                                    null),
                                            "u"));
            assertTrue(e.getMessage().contains("cannot be set by a request"), e.getMessage());
        }
        for (Map<String, String> bad :
                List.of(
                        Map.of("X-A", "line1\r\nX-Injected: 1"),
                        Map.of("bad name", "v"),
                        Map.of("X-B", "nul\u0000"))) {
            WebPageFetchException e =
                    assertThrows(
                            WebPageFetchException.class,
                            () -> ex.execute(spec("GET", "https://" + HOST + "/", bad, null, null), "u"));
            assertEquals("the URL or one of the headers is not valid", e.getMessage());
        }
        assertTrue(ex.sent.isEmpty());
    }

    @Test
    void defaultHeadersAndBodyTypesAreApplied() {
        Recording ex = new Recording(null, null);
        ex.responses.add(raw(200, "text/plain", "ok"));
        ex.responses.add(raw(200, "text/plain", "ok"));
        ex.responses.add(raw(200, "text/plain", "ok"));
        ex.execute(spec("POST", "https://" + HOST + "/a", null, "{\"k\":\"v\"}", null), "u");
        ex.execute(spec("POST", "https://" + HOST + "/b", null, "{\"a\":\"1\",\"b\":2}", "form"), "u");
        ex.execute(spec("POST", "https://" + HOST + "/c", null, "hello", "text"), "u");

        assertEquals("application/json", ex.sent.get(0).header("content-type"));
        assertEquals(
                "VegaDuta-Agent/1.0 (+https://github.com/CD-LC/vegaduta)",
                ex.sent.get(0).header("user-agent"));
        assertEquals(
                "application/json, text/*;q=0.9, */*;q=0.5", ex.sent.get(0).header("accept"));
        assertEquals(
                "application/x-www-form-urlencoded; charset=utf-8",
                ex.sent.get(1).header("content-type"));
        assertEquals("a=1&b=2", ex.sent.get(1).body());
        assertEquals("text/plain; charset=utf-8", ex.sent.get(2).header("content-type"));
    }

    // ------------------------------------------------------------ host policy stage (SSRF)

    @Test
    void execute_refusesEverySpellingOfAnInternalAddressWithoutSending() {
        Recording ex = new Recording(null, null);
        List<String> vectors =
                List.of(
                        "https://127.0.0.1/",
                        "https://2130706433/",
                        "https://0x7f.0.0.1/",
                        "https://0177.0.0.1/",
                        "https://127.1/",
                        "https://[::1]/",
                        "https://[::ffff:127.0.0.1]/",
                        "https://[::ffff:a9fe:a9fe]/",
                        "https://[fd00::1]/",
                        "https://169.254.169.254/latest/meta-data/",
                        "https://100.64.0.1/",
                        "https://10.0.0.8/",
                        "https://192.168.1.1/",
                        "https://0.0.0.0/",
                        "https://localhost/",
                        "file:///etc/passwd",
                        "ftp://" + HOST + "/",
                        "https://user:pw@" + HOST + "/",
                        "",
                        "not a url");
        // The legacy path has no numeric-host refusal (that is a safe-mode rule): the JDK parses
        // "0177.0.0.1" as the PUBLIC decimal address 177.0.0.1, so it is the one vector allowed
        // through. Pinned so the split cannot silently change it in either direction.
        List<String> reachedTheNetwork = new ArrayList<>();
        for (String url : vectors) {
            ex.responses.clear();
            ex.responses.add(raw(200, "text/plain", "ok"));
            int before = ex.sent.size();
            try {
                ex.execute(spec("GET", url, null, null, null), "u");
            } catch (WebPageFetchException expected) {
                // refused
            }
            if (ex.sent.size() > before) {
                reachedTheNetwork.add(url);
            }
        }
        assertEquals(List.of("https://0177.0.0.1/"), reachedTheNetwork);
    }

    @Test
    void execute_refusesAnInternalHostIntroducedThroughTemplateOrQuery() {
        Recording ex = new Recording(null, null);
        assertThrows(
                WebPageFetchException.class,
                () ->
                        ex.execute(
                                new HttpRequestSpec(
                                        "GET",
                                        "https://{{host}}/",
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        Map.of("host", "169.254.169.254")),
                                "u"));
        assertTrue(ex.sent.isEmpty());
    }

    // ------------------------------------------------------------ secrets stage

    @Test
    void secretsAreBoundToTheDestinationAndEverySecretBearingHeaderIsRedactedFromTheResult() {
        Recording ex = new Recording(resolverWith("TOKEN", SECRET), null);
        ex.responses.add(
                raw(
                        200,
                        "application/json",
                        "{\"echo\":\"" + SECRET + "\"}",
                        "X-Echo",
                        SECRET));

        HttpRequestResult result =
                ex.execute(
                        new HttpRequestSpec(
                                "POST",
                                "https://" + HOST + "/p",
                                Map.of("Authorization", "Bearer {{secret.TOKEN}}"),
                                Map.of("k", "{{secret:token}}"),
                                "{\"t\":\"{{secret.TOKEN}}\"}",
                                null,
                                null,
                                null),
                        "u");

        Sent s = ex.sent.get(0);
        assertEquals("Bearer " + SECRET, s.header("authorization"));
        assertTrue(s.uri().toString().contains("k=" + SECRET));
        assertTrue(s.body().contains(SECRET));
        assertFalse(result.body().contains(SECRET), result.body());
        assertFalse(result.headers().get("x-echo").contains(SECRET));
        assertFalse(result.url().contains(SECRET), result.url());
    }

    @Test
    void anUnresolvableSecretFailsWithTheGenericMessageAndNothingIsSent() {
        Recording ex = new Recording(resolverWith("TOKEN", null), null);
        WebPageFetchException e =
                assertThrows(
                        WebPageFetchException.class,
                        () ->
                                ex.execute(
                                        spec(
                                                "GET",
                                                "https://" + HOST + "/",
                                                Map.of("A", "{{secret.TOKEN}}"),
                                                null,
                                                null),
                                        "u"));
        assertEquals(HttpRequestExecutor.SECRET_NOT_AVAILABLE, e.getMessage());
        assertTrue(ex.sent.isEmpty());
    }

    @Test
    void noVaultMeansAReferenceIsNeverSentUpstream() {
        Recording ex = new Recording(null, null);
        WebPageFetchException e =
                assertThrows(
                        WebPageFetchException.class,
                        () ->
                                ex.execute(
                                        spec(
                                                "GET",
                                                "https://" + HOST + "/",
                                                Map.of("A", "{{secret.TOKEN}}"),
                                                null,
                                                null),
                                        "u"));
        assertEquals("vault secrets are not available in this deployment", e.getMessage());
        assertTrue(ex.sent.isEmpty());
    }

    // ------------------------------------------------------------ redirect stage

    @Test
    void aRedirectToAPrivateOrMetadataHostIsRefusedOnTheHop() {
        for (String target :
                List.of(
                        "https://169.254.169.254/latest/",
                        "https://[::ffff:127.0.0.1]:8080/admin",
                        "http://10.0.0.5/",
                        "https://localhost/")) {
            Recording ex = new Recording(null, null);
            ex.responses.add(redirect(302, target));
            assertThrows(
                    WebPageFetchException.class,
                    () -> ex.execute(spec("GET", "https://" + HOST + "/", null, null, null), "u"),
                    target);
            assertEquals(1, ex.sent.size(), "the private target is never requested: " + target);
        }
    }

    @Test
    void redirectsAreCappedAtThreeAndALocationlessRedirectIsTheResponse() {
        Recording ex = new Recording(null, null);
        for (int i = 0; i < 5; i++) {
            ex.responses.add(redirect(302, "https://" + HOST + "/n" + i));
        }
        WebPageFetchException e =
                assertThrows(
                        WebPageFetchException.class,
                        () -> ex.execute(spec("GET", "https://" + HOST + "/", null, null, null), "u"));
        assertEquals("the server redirected more than 3 times", e.getMessage());
        assertEquals(4, ex.sent.size());

        Recording plain = new Recording(null, null);
        plain.responses.add(raw(302, "text/plain", "moved"));
        assertEquals(
                302,
                plain.execute(spec("GET", "https://" + HOST + "/", null, null, null), "u")
                        .status());
    }

    @Test
    void methodAndBodySemanticsOfRedirectsAreKept() {
        Recording ex = new Recording(null, null);
        ex.responses.add(redirect(307, "https://" + HOST + "/b"));
        ex.responses.add(redirect(302, "https://" + HOST + "/c"));
        ex.responses.add(raw(200, "text/plain", "ok"));
        ex.execute(spec("POST", "https://" + HOST + "/a", null, "{\"x\":1}", null), "u");
        assertEquals("POST", ex.sent.get(1).method());
        assertEquals("{\"x\":1}", ex.sent.get(1).body());
        assertEquals("GET", ex.sent.get(2).method());
        assertNull(ex.sent.get(2).body());

        Recording see = new Recording(null, null);
        see.responses.add(redirect(303, "https://" + HOST + "/b"));
        see.responses.add(raw(200, "text/plain", "ok"));
        see.execute(spec("PUT", "https://" + HOST + "/a", null, "{}", null), "u");
        assertEquals("GET", see.sent.get(1).method());

        Recording head = new Recording(null, null);
        head.responses.add(redirect(301, "https://" + HOST + "/b"));
        head.responses.add(raw(200, "text/plain", ""));
        head.execute(spec("HEAD", "https://" + HOST + "/a", null, null, null), "u");
        assertEquals("HEAD", head.sent.get(1).method());
    }

    @Test
    void aCrossOriginRedirectDropsCredentialHeadersEvenWithoutAVaultSecret() {
        Recording ex = new Recording(null, null);
        ex.responses.add(redirect(302, "https://" + OTHER + "/next"));
        ex.responses.add(raw(200, "text/plain", "ok"));
        ex.execute(
                spec(
                        "GET",
                        "https://" + HOST + "/",
                        Map.of(
                                "Authorization", "Bearer abc",
                                "Cookie", "s=1",
                                "Proxy-Authorization", "x",
                                "X-Payment", "p",
                                "X-Other", "keep"),
                        null,
                        null),
                "u");
        Sent first = ex.sent.get(0);
        Sent second = ex.sent.get(1);
        assertEquals("Bearer abc", first.header("authorization"));
        assertNull(second.header("authorization"));
        assertNull(second.header("cookie"));
        assertNull(second.header("proxy-authorization"));
        assertNull(second.header("x-payment"));
        assertEquals("keep", second.header("x-other"));
    }

    @Test
    void aSameOriginRedirectKeepsCredentialHeaders() {
        Recording ex = new Recording(null, null);
        ex.responses.add(redirect(302, "/next"));
        ex.responses.add(raw(200, "text/plain", "ok"));
        ex.execute(
                spec("GET", "https://" + HOST + "/", Map.of("Authorization", "Bearer abc"), null, null),
                "u");
        assertEquals("Bearer abc", ex.sent.get(1).header("authorization"));
    }

    @Test
    void aSecretBearingBodyIsNeverReplayedToAnotherOrigin() {
        Recording ex = new Recording(resolverWith("TOKEN", SECRET), null);
        ex.responses.add(redirect(307, "https://" + OTHER + "/steal"));
        WebPageFetchException e =
                assertThrows(
                        WebPageFetchException.class,
                        () ->
                                ex.execute(
                                        spec(
                                                "POST",
                                                "https://" + HOST + "/",
                                                null,
                                                "{\"t\":\"{{secret.TOKEN}}\"}",
                                                null),
                                        "u"));
        assertTrue(e.getMessage().contains("not followed"), e.getMessage());
        assertFalse(e.getMessage().contains(SECRET));
        assertEquals(1, ex.sent.size());
    }

    @Test
    void aRedirectFailureQuotingTheSecretIsScrubbed() {
        Recording ex = new Recording(resolverWith("TOKEN", SECRET), null);
        ex.responses.add(redirect(302, "https://" + HOST + "/" + SECRET));
        ex.responses.add(redirect(302, "ftp://x/" + SECRET));
        WebPageFetchException e =
                assertThrows(
                        WebPageFetchException.class,
                        () ->
                                ex.execute(
                                        spec(
                                                "GET",
                                                "https://" + HOST + "/",
                                                Map.of("A", "{{secret.TOKEN}}"),
                                                null,
                                                null),
                                        "u"));
        assertFalse(e.getMessage().contains(SECRET), e.getMessage());
    }

    // ------------------------------------------------------------ read and redact stage

    @Test
    void binaryResponsesAreSummarisedAndTextIsClippedWithTheMarker() {
        Recording ex = new Recording(null, null);
        ex.responses.add(raw(200, "image/png", "PNGDATA"));
        HttpRequestResult bin =
                ex.execute(spec("GET", "https://" + HOST + "/i", null, null, null), "u");
        assertEquals("[binary response: image/png - not decoded]", bin.body());
        assertFalse(bin.bodyTruncated());

        ex.responses.add(raw(200, "text/plain", "x".repeat(500)));
        HttpRequestResult clipped =
                ex.execute(
                        new HttpRequestSpec(
                                "GET",
                                "https://" + HOST + "/t",
                                null,
                                null,
                                null,
                                null,
                                null,
                                200),
                        "u");
        assertEquals(200 + HttpRequestExecutor.TRUNCATION_MARKER.length(), clipped.body().length());
        assertTrue(clipped.bodyTruncated());
    }

    @Test
    void theLegacyReaderKeepsSetCookieAndLowerCasesHeaderNames() {
        Recording ex = new Recording(null, null);
        ex.responses.add(raw(200, "application/json", "{}", "Set-Cookie", "a=b", "X-Id", "7"));
        HttpRequestResult r = ex.execute(spec("GET", "https://" + HOST + "/", null, null, null), "u");
        assertEquals("a=b", r.headers().get("set-cookie"));
        assertEquals("7", r.headers().get("x-id"));
        assertEquals("application/json", r.contentType());
        assertEquals("https://" + HOST + "/", r.url());
    }

    // ------------------------------------------------------------ x402

    @Test
    void anAffordable402IsPaidOnceAndRetriedWithTheXPaymentHeader_andTheWalletKeyIsRedacted() {
        X402PaymentService x402 = mock(X402PaymentService.class);
        when(x402.isEnabled()).thenReturn(true);
        when(x402.parseAccepts(anyString()))
                .thenReturn(Optional.of(new org.json.JSONArray().put(new org.json.JSONObject())));
        when(x402.buildPaymentHeader(any(), eq("wallet-key-xyz-123"), eq("tenant-a")))
                .thenReturn(Optional.of("PAYMENT-HEADER"));
        Recording ex = new Recording(resolverWith("X402_WALLET_PRIVATE_KEY", "wallet-key-xyz-123"), x402);
        TenantContext.set(TenantContext.of("tenant-a", "user-1", Set.of()));
        ex.responses.add(raw(402, "application/json", "{\"accepts\":[{}]}"));
        ex.responses.add(raw(200, "application/json", "{\"paid\":\"wallet-key-xyz-123\"}"));

        HttpRequestResult r = ex.execute(spec("GET", "https://" + HOST + "/paid", null, null, null), "user-1");

        assertEquals(200, r.status());
        assertEquals(2, ex.sent.size());
        assertNull(ex.sent.get(0).header("x-payment"));
        assertEquals("PAYMENT-HEADER", ex.sent.get(1).header("x-payment"));
        assertFalse(r.body().contains("wallet-key-xyz-123"), r.body());
    }

    @Test
    void aSecond402AfterPaymentIsReturnedNotPaidAgain() {
        X402PaymentService x402 = mock(X402PaymentService.class);
        when(x402.isEnabled()).thenReturn(true);
        when(x402.parseAccepts(anyString()))
                .thenReturn(Optional.of(new org.json.JSONArray().put(new org.json.JSONObject())));
        when(x402.buildPaymentHeader(any(), anyString(), anyString()))
                .thenReturn(Optional.of("PAYMENT-HEADER"));
        Recording ex = new Recording(resolverWith("X402_WALLET_PRIVATE_KEY", "wallet-key-xyz-123"), x402);
        TenantContext.set(TenantContext.of("tenant-a", "user-1", Set.of()));
        ex.responses.add(raw(402, "application/json", "{}"));
        ex.responses.add(raw(402, "application/json", "{}"));
        assertEquals(
                402,
                ex.execute(spec("GET", "https://" + HOST + "/paid", null, null, null), "user-1")
                        .status());
        assertEquals(2, ex.sent.size());
    }

    @Test
    void x402IsSkippedWithoutATenantOrWhenDisabled() {
        X402PaymentService x402 = mock(X402PaymentService.class);
        when(x402.isEnabled()).thenReturn(false);
        Recording ex = new Recording(resolverWith("X402_WALLET_PRIVATE_KEY", "k"), x402);
        ex.responses.add(raw(402, "application/json", "{}"));
        assertEquals(
                402,
                ex.execute(spec("GET", "https://" + HOST + "/paid", null, null, null), "user-1")
                        .status());
        assertEquals(1, ex.sent.size());
    }

    // ------------------------------------------------------------ pure helpers kept as seams

    @Test
    void stableHelperContracts() {
        assertEquals(
                "https://a/b?x=1%202&y=z",
                HttpRequestExecutor.appendQuery("https://a/b", orderedMap("x", "1 2", "y", "z")));
        assertEquals(
                "https://a/b?q=1&x=2",
                HttpRequestExecutor.appendQuery("https://a/b?q=1", Map.of("x", "2")));
        assertEquals(
                "{{secret.ABC}}",
                HttpRequestExecutor.normaliseColonReferences("{{secret:abc}}"));
        assertEquals(
                "a-{{secret.X}}",
                HttpRequestExecutor.renderForDisplay("{{a}}-{{secret.X}}", Map.of("a", "a")));
        assertTrue(HttpRequestExecutor.isUntrustedSubject("agent-share:1"));
        assertTrue(HttpRequestExecutor.isUntrustedSubject("system:foo-inbound"));
        assertFalse(HttpRequestExecutor.isUntrustedSubject("user-1"));
        assertFalse(HttpRequestExecutor.isUntrustedSubject(null));
    }

    private static Map<String, String> orderedMap(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }
}
