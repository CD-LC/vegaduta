package com.agenticai.core.imagegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.agenticai.core.providers.PlatformHostedLlmProviderService;
import com.agenticai.core.providers.VertexAccessTokenProvider;
import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Locks in the carrier-pinning fix: all three imagegen HTTP clients must
 * build their {@link RestClient} on {@link JdkClientHttpRequestFactory}
 * (java.net.http.HttpClient), never {@link SimpleClientHttpRequestFactory}
 * (java.net.HttpURLConnection) - the latter's {@code getInputStream()} is
 * {@code synchronized} and pins the carrier thread for the whole call under
 * {@code spring.threads.virtual.enabled}, invisible on local JDK 24 (JEP 491
 * removed that pinning) but real on production's {@code eclipse-temurin:21}.
 * Also asserts every constructor's connect/read timeout survived the
 * factory swap unchanged.
 *
 * <p>Reaches into private fields the same way {@code SidecarClientTest} and
 * {@code S3FileStorageServiceTest} already do for these self-building-
 * RestClient classes - none of the three clients expose their {@link
 * RestClient} or its factory publicly, and {@link JdkClientHttpRequestFactory}
 * has no timeout getters (only {@code setReadTimeout}), so reflection is the
 * only way to verify the configured values without a live network call.
 */
class HttpClientTimeoutConfigTest {

    private static final Duration EXPECTED_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    @Test
    void veoVideoClientUsesJdkFactoryWithConnectTenReadThirtySeconds() throws Exception {
        VertexVeoVideoGenerationClient client =
            new VertexVeoVideoGenerationClient(mock(VertexAccessTokenProvider.class));
        assertTimeouts(client, "restClient", EXPECTED_CONNECT_TIMEOUT, Duration.ofSeconds(30));
    }

    @Test
    void vertexImageClientUsesJdkFactoryWithConnectTenReadFortySeconds() throws Exception {
        VertexImageGenerationClient client =
            new VertexImageGenerationClient(mock(VertexAccessTokenProvider.class));
        assertTimeouts(client, "restClient", EXPECTED_CONNECT_TIMEOUT, Duration.ofSeconds(40));
    }

    @Test
    void geminiImageClientUsesJdkFactoryWithConnectTenReadFortySeconds() throws Exception {
        GeminiImageGenerationClient client =
            new GeminiImageGenerationClient(mock(PlatformHostedLlmProviderService.class));
        assertTimeouts(client, "restClient", EXPECTED_CONNECT_TIMEOUT, Duration.ofSeconds(40));
    }

    private static void assertTimeouts(
        Object client, String restClientFieldName, Duration expectedConnectTimeout, Duration expectedReadTimeout
    ) throws Exception {
        RestClient restClient = (RestClient) readField(client, restClientFieldName);
        ClientHttpRequestFactory factory = (ClientHttpRequestFactory) readField(restClient, "clientRequestFactory");

        assertInstanceOf(JdkClientHttpRequestFactory.class, factory,
            "must use JdkClientHttpRequestFactory (non-pinning) - SimpleClientHttpRequestFactory pins the carrier thread");

        Duration actualReadTimeout = (Duration) readField(factory, "readTimeout");
        assertEquals(expectedReadTimeout, actualReadTimeout, "read timeout must be preserved exactly");

        HttpClient httpClient = (HttpClient) readField(factory, "httpClient");
        Optional<Duration> actualConnectTimeout = httpClient.connectTimeout();
        assertTrue(actualConnectTimeout.isPresent(), "connect timeout must be set on the underlying HttpClient");
        assertEquals(expectedConnectTimeout, actualConnectTimeout.get(), "connect timeout must be preserved exactly");
    }

    private static Object readField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }
}
