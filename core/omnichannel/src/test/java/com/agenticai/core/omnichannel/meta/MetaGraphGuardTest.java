package com.agenticai.core.omnichannel.meta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.resilience.CircuitBreaker;
import com.agenticai.core.resilience.CircuitBreakerRegistry;
import com.agenticai.core.resilience.CircuitBreakerSettings;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Meta Graph breaker: open = IOException (which every call site already maps to "could not reach Meta"). */
@SuppressWarnings({"unchecked", "rawtypes"})
class MetaGraphGuardTest {

    private final HttpClient client = mock(HttpClient.class);
    private final HttpRequest request = HttpRequest.newBuilder(URI.create("https://graph.facebook.com/x")).build();

    private static CircuitBreakerRegistry registry(boolean enabled) {
        CircuitBreakerSettings settings = new CircuitBreakerSettings(
            2, 0, 10, 10, Duration.ofSeconds(60), Duration.ofSeconds(30), 1, Duration.ofSeconds(30));
        return new CircuitBreakerRegistry(enabled, f -> settings, f -> 0, Duration.ZERO, Clock.systemUTC(), null);
    }

    private HttpResponse<String> respondWith(int status) throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        doReturn(response).when(client).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        return response;
    }

    @Test
    void nullOrDisabledRegistryIsAPassThrough() throws Exception {
        HttpResponse<String> response = respondWith(200);
        assertThat(MetaGraphGuard.send(null, client, request)).isSameAs(response);
        assertThat(MetaGraphGuard.send(registry(false), client, request)).isSameAs(response);
        verify(client, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void ioFailuresOpenTheCircuitAndThenTheCallIsShedWithoutSending() throws Exception {
        CircuitBreakerRegistry registry = registry(true);
        doThrow(new IOException("connect timed out")).when(client)
            .send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> MetaGraphGuard.send(registry, client, request))
                .isInstanceOf(IOException.class).hasMessageContaining("connect timed out");
        }
        assertThat(registry.breaker("meta-graph").state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThatThrownBy(() -> MetaGraphGuard.send(registry, client, request))
            .isInstanceOf(IOException.class).hasMessageContaining("temporarily unavailable");
        verify(client, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void fiveHundredsCountButFourHundredsDoNot() throws Exception {
        CircuitBreakerRegistry registry = registry(true);
        respondWith(400);
        for (int i = 0; i < 6; i++) {
            MetaGraphGuard.send(registry, client, request); // a tenant's bad token or recipient: Meta itself is fine
        }
        assertThat(registry.breaker("meta-graph").state()).isEqualTo(CircuitBreaker.State.CLOSED);
        respondWith(503);
        MetaGraphGuard.send(registry, client, request);
        MetaGraphGuard.send(registry, client, request);
        assertThat(registry.breaker("meta-graph").state()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void anInterruptIsNeutralAndRethrown() throws Exception {
        CircuitBreakerRegistry registry = registry(true);
        doThrow(new InterruptedException()).when(client)
            .send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> MetaGraphGuard.send(registry, client, request))
                .isInstanceOf(InterruptedException.class);
        }
        assertThat(registry.breaker("meta-graph").state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
