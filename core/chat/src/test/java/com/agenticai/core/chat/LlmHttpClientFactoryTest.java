package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.client.HttpClientAutoConfiguration;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings.Redirects;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Guards the two halves of the 2026-07-29 "no HTTP timeouts anywhere" fix.
 *
 * <p>Neither test makes a network call - the point is not that a timeout fires, it is that the
 * configuration is actually wired to something. That is the failure mode this whole change exists
 * to prevent: a plausible-looking {@code spring.http.client.*} block that binds to nothing (a
 * renamed property, a Boot upgrade that moves it) looks exactly like a fix while leaving every
 * client unbounded, and nothing else in the build would notice.
 */
class LlmHttpClientFactoryTest {

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
                        "spring.http.client.connect-timeout=10s",
                        "spring.http.client.read-timeout=120s")
                .run(
                        context -> {
                            ClientHttpRequestFactorySettings settings =
                                    context.getBean(ClientHttpRequestFactorySettings.class);
                            assertEquals(Duration.ofSeconds(10), settings.connectTimeout());
                            assertEquals(Duration.ofSeconds(120), settings.readTimeout());
                        });
    }

    /**
     * The LLM clients take the app's transport configuration but NOT its clock: a 120s read timeout
     * is a sane backstop for a control-plane REST call and is short enough to cut off a legitimate
     * long completion, so the chat path overrides it - and only it.
     */
    @Test
    void llmSettingsOverrideOnlyTheTimeoutsAndKeepTheRestOfTheTransportConfig() {
        ClientHttpRequestFactorySettings global =
                ClientHttpRequestFactorySettings.defaults()
                        .withRedirects(Redirects.DONT_FOLLOW)
                        .withConnectTimeout(Duration.ofSeconds(10))
                        .withReadTimeout(Duration.ofSeconds(120));

        ClientHttpRequestFactorySettings llm =
                LlmHttpClientFactory.llmSettings(
                        global, Duration.ofSeconds(10), Duration.ofMinutes(10));

        assertEquals(Duration.ofSeconds(10), llm.connectTimeout());
        assertEquals(Duration.ofMinutes(10), llm.readTimeout());
        assertTrue(
                llm.readTimeout().compareTo(global.readTimeout()) > 0,
                "the LLM read timeout must be longer than the app-wide one, or long completions break");
        // Redirect handling (and any SSL bundle) is inherited, not reset - the
        // LLM path differs in how long it waits, not in how it talks.
        assertEquals(Redirects.DONT_FOLLOW, llm.redirects());
    }

    /**
     * The provider factories call this once per chat turn and Spring AI's {@code *Api} builders
     * mutate whatever builder they are handed (default headers, status handlers), so handing out
     * one shared instance would let one provider's configuration leak into another's.
     */
    @Test
    void eachCallGetsItsOwnBuilderRatherThanASharedMutableOne() {
        @SuppressWarnings("unchecked")
        ObjectProvider<ClientHttpRequestFactorySettings> noSettingsBean =
                mock(ObjectProvider.class);
        when(noSettingsBean.getIfAvailable(any()))
                .thenReturn(ClientHttpRequestFactorySettings.defaults());

        LlmHttpClientFactory factory =
                new LlmHttpClientFactory(
                        RestClient.builder(),
                        noSettingsBean,
                        Duration.ofSeconds(10),
                        Duration.ofMinutes(10),
                        // Streaming connector deliberately off: this test is about builder
                        // identity, and leaving it on would spin up a real JDK HttpClient.
                        false,
                        // Idle-connection bound deliberately ZERO so this test does not
                        // mutate the JVM-wide jdk.httpclient.keepalive.timeout system
                        // property and leak that into whatever test runs next.
                        Duration.ZERO,
                        200,
                        400);

        assertNotSame(factory.restClientBuilder(), factory.restClientBuilder());
        assertNotSame(factory.webClientBuilder(), factory.webClientBuilder());
        assertEquals(Duration.ofMinutes(10), factory.readTimeout());
    }

    /**
     * The 2026-09-28 pool fix: HttpClient 5's own defaults are 5 connections per route / 25 total,
     * shared by every provider, and an LLM call holds a connection for the whole generation.
     * Asserted on a real connection manager so a customizer that stops being applied is caught.
     */
    @Test
    void connectionPoolIsSizedForLongLivedLlmCallsNotHttpClientDefaults() throws Exception {
        try (PoolingHttpClientConnectionManager pool =
                LlmHttpClientFactory.configurePool(
                                PoolingHttpClientConnectionManagerBuilder.create(), 200, 400)
                        .build()) {
            assertEquals(200, pool.getDefaultMaxPerRoute());
            assertEquals(400, pool.getMaxTotal());
        }
        // A total below the per-route cap would silently become the real cap.
        try (PoolingHttpClientConnectionManager pool =
                LlmHttpClientFactory.configurePool(
                                PoolingHttpClientConnectionManagerBuilder.create(), 50, 10)
                        .build()) {
            assertEquals(50, pool.getDefaultMaxPerRoute());
            assertEquals(50, pool.getMaxTotal());
        }
    }

    /** The blocking path is Apache HttpClient 5 explicitly, not whatever detect() finds. */
    @Test
    void blockingPathUsesApacheHttpClientWithTheLlmSettings() throws Exception {
        ClientHttpRequestFactory factory =
                LlmHttpClientFactory.buildRequestFactory(
                        ClientHttpRequestFactorySettings.defaults()
                                .withConnectTimeout(Duration.ofSeconds(10))
                                .withReadTimeout(Duration.ofMinutes(10)),
                        200,
                        400,
                        Duration.ofSeconds(120));
        try {
            assertInstanceOf(HttpComponentsClientHttpRequestFactory.class, factory);
        } finally {
            ((HttpComponentsClientHttpRequestFactory) factory).destroy();
        }
    }
}
