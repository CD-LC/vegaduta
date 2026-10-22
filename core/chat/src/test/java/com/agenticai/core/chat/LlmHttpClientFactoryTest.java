package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Guards the two halves of the 2026-07-29 "no HTTP timeouts anywhere" fix.
 *
 * <p>Neither test makes a network call - the point is not that a timeout fires, it is that the
 * configuration is actually wired to something. That is the failure mode this whole change exists
 * to prevent: a plausible-looking {@code spring.http.clients.*} block that binds to nothing (a
 * renamed property, a Boot upgrade that moves it) looks exactly like a fix while leaving every
 * client unbounded, and nothing else in the build would notice.
 */
class LlmHttpClientFactoryTest {

    // Idle-connection bound deliberately ZERO in these tests so they do not mutate the JVM-wide
    // jdk.httpclient.keepalive.timeout system property and leak that into whatever test runs next.
    private static LlmHttpClientFactory factory(boolean streamTimeouts) {
        return new LlmHttpClientFactory(
                Duration.ofSeconds(10),
                Duration.ofMinutes(10),
                streamTimeouts,
                Duration.ZERO,
                200,
                400);
    }

    /**
     * The global block in core/app's application.yml is only worth anything if Spring Boot still
     * binds those exact property names into the settings the auto-configured {@code
     * RestClient.Builder} is built from. Asserted against the real {@code
     * HttpClientAutoConfiguration} rather than trusted.
     */
    @Test
    void springHttpClientPropertiesBindIntoTheAutoConfiguredRequestFactorySettings() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class))
                .withPropertyValues(
                        "spring.http.clients.connect-timeout=10s",
                        "spring.http.clients.read-timeout=120s")
                .run(
                        context -> {
                            HttpClientSettings settings = context.getBean(HttpClientSettings.class);
                            assertEquals(Duration.ofSeconds(10), settings.connectTimeout());
                            assertEquals(Duration.ofSeconds(120), settings.readTimeout());
                        });
    }

    /**
     * The LLM transport takes its own clock, not the app-wide one: a 120s read timeout is a sane
     * backstop for a control-plane REST call and is short enough to cut off a legitimate long
     * completion. The overall call deadline is off so a long token stream is never truncated;
     * silence is bounded by the read timeout.
     */
    @Test
    void llmTransportHasBoundedConnectAndReadClocksButNoOverallDeadline() {
        LlmHttpClientFactory factory = factory(true);
        OkHttpClient client = factory.baseBuilder().build();

        assertEquals(10_000, client.connectTimeoutMillis());
        assertEquals(600_000, client.readTimeoutMillis());
        assertEquals(
                0,
                client.callTimeoutMillis(),
                "an overall deadline would cut off a legitimate long stream");
        assertEquals(Duration.ofMinutes(10), factory.readTimeout());
        assertTrue(
                client.readTimeoutMillis() > 120_000,
                "the LLM read timeout must be longer than the app-wide one, or long completions break");
    }

    /**
     * The 2026-09-28 pool fix: an LLM call holds its connection for the whole generation, so the
     * dispatcher and pool are sized for that (per-route 200, total 400), not for OkHttp's defaults
     * (5 idle / 64 requests).
     */
    @Test
    void poolAndDispatcherAreSizedForLongLivedLlmCalls() {
        OkHttpClient client = factory(true).baseBuilder().build();

        assertEquals(200, client.dispatcher().getMaxRequestsPerHost());
        assertEquals(400, client.dispatcher().getMaxRequests());
        // A total below the per-route cap would silently become the real cap.
        OkHttpClient small =
                new LlmHttpClientFactory(
                                Duration.ofSeconds(10),
                                Duration.ofMinutes(10),
                                true,
                                Duration.ZERO,
                                50,
                                10)
                        .baseBuilder()
                        .build();
        assertEquals(50, small.dispatcher().getMaxRequestsPerHost());
        assertEquals(50, small.dispatcher().getMaxRequests());
    }

    @Test
    void theGuardedClientSharesTheClocksButNotTheRedirectOrProxyPolicy() {
        LlmHttpClientFactory factory = factory(true);
        OkHttpClient plain = factory.baseBuilder().build();
        OkHttpClient guarded = factory.guardedClient();

        assertEquals(plain.connectTimeoutMillis(), guarded.connectTimeoutMillis());
        assertEquals(plain.readTimeoutMillis(), guarded.readTimeoutMillis());
        assertTrue(plain.followRedirects());
        assertFalse(
                guarded.followRedirects(),
                "a redirect could carry the tenant's key to another host");
        assertFalse(guarded.followSslRedirects());
        assertEquals(java.net.Proxy.NO_PROXY, guarded.proxy());
        assertNotSame(plain, guarded);
        // Built once and reused: the guarded pool must not be re-created per turn.
        org.junit.jupiter.api.Assertions.assertSame(guarded, factory.guardedClient());
    }
}
