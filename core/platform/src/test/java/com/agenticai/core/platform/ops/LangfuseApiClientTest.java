package com.agenticai.core.platform.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.platform.ops.LangfuseApiClient.LangfuseUnavailableException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class LangfuseApiClientTest {

    private static final String AUTH = "Basic cGstbGYtc2VjcmV0OnNrLWxmLXNlY3JldA==";

    @Test
    void baseUrlIsDerivedFromTheOtlpEndpointOrTakenFromTheExplicitProperty() {
        assertEquals("http://langfuse-web:3000",
            LangfuseEndpoints.baseUrl("", "http://langfuse-web:3000/api/public/otel/v1/traces"));
        assertEquals("https://lf.example.com",
            LangfuseEndpoints.baseUrl("https://lf.example.com/", "http://langfuse-web:3000/api/public/otel/v1/traces"));
        // An OTLP endpoint that is not Langfuse's has no Langfuse API behind it.
        assertNull(LangfuseEndpoints.baseUrl(null, "http://otel-collector:4318/v1/traces"));
        assertNull(LangfuseEndpoints.baseUrl(null, ""));
        assertNull(LangfuseEndpoints.baseUrl("ftp://nope", null));
    }

    @Test
    void notConfiguredWithoutCredentialOrUrl() {
        LangfuseApiClient noAuth = new LangfuseApiClient("http://lf:3000", "", (u, a) -> null, Clock.systemUTC());
        assertFalse(noAuth.isConfigured());
        assertTrue(noAuth.notConfiguredReason().contains("credential"));
        LangfuseUnavailableException e = assertThrows(LangfuseUnavailableException.class,
            () -> noAuth.get("/api/public/traces", Map.of()));
        assertEquals(0, e.status());

        LangfuseApiClient noUrl = new LangfuseApiClient(null, AUTH, (u, a) -> null, Clock.systemUTC());
        assertFalse(noUrl.isConfigured());
        assertTrue(noUrl.notConfiguredReason().contains("URL"));
    }

    @Test
    void sendsTheAuthHeaderBuildsTheQueryAndCachesFor30Seconds() {
        List<URI> calls = new ArrayList<>();
        AtomicReference<String> sentAuth = new AtomicReference<>();
        MutableClock clock = new MutableClock();
        LangfuseApiClient client = new LangfuseApiClient("http://lf:3000", AUTH, (uri, auth) -> {
            calls.add(uri);
            sentAuth.set(auth);
            return new LangfuseApiClient.Response(200, "{\"data\":[]}");
        }, clock);
        Map<String, String> q = new LinkedHashMap<>();
        q.put("name", "chat turn");
        q.put("userId", null);
        q.put("page", "2");

        client.get("/api/public/traces", q);
        client.get("/api/public/traces", q);

        assertEquals(1, calls.size());
        assertEquals("http://lf:3000/api/public/traces?name=chat+turn&page=2", calls.get(0).toString());
        assertEquals(AUTH, sentAuth.get());

        clock.advance(Duration.ofSeconds(31));
        client.get("/api/public/traces", q);
        assertEquals(2, calls.size());
    }

    @Test
    void failuresBecomeSecretFreeReasons() {
        LangfuseApiClient unauthorized = new LangfuseApiClient("http://lf:3000", AUTH,
            (u, a) -> new LangfuseApiClient.Response(401, "{\"message\":\"Invalid credentials " + AUTH + "\"}"),
            Clock.systemUTC());
        LangfuseUnavailableException e = assertThrows(LangfuseUnavailableException.class,
            () -> unauthorized.get("/api/public/traces", Map.of()));
        assertEquals(401, e.status());
        assertFalse(e.getMessage().contains("Basic"));
        assertFalse(e.getMessage().contains("lf:3000"));

        LangfuseApiClient timeout = new LangfuseApiClient("http://lf:3000", AUTH, (u, a) -> {
            throw new HttpTimeoutException("timed out talking to http://lf:3000");
        }, Clock.systemUTC());
        assertTrue(assertThrows(LangfuseUnavailableException.class, () -> timeout.get("/x", Map.of()))
            .getMessage().contains("did not answer"));

        LangfuseApiClient refused = new LangfuseApiClient("http://lf:3000", AUTH, (u, a) -> {
            throw new ConnectException("refused");
        }, Clock.systemUTC());
        assertTrue(assertThrows(LangfuseUnavailableException.class, () -> refused.get("/x", Map.of()))
            .getMessage().contains("connection refused"));

        LangfuseApiClient notJson = new LangfuseApiClient("http://lf:3000", AUTH,
            (u, a) -> new LangfuseApiClient.Response(200, "<html>"), Clock.systemUTC());
        assertThrows(LangfuseUnavailableException.class, () -> notJson.get("/x", Map.of()));
    }

    @Test
    void pathSegmentCannotAddPathComponents() {
        assertEquals("a%2F..%2Fb", LangfuseApiClient.pathSegment("a/../b"));
    }

    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-09T10:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
