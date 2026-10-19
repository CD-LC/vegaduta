package com.agenticai.core.sdlc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.agenticai.core.resilience.CircuitBreaker;
import com.agenticai.core.resilience.CircuitBreakerRegistry;
import com.agenticai.core.resilience.CircuitBreakerSettings;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** E2B sandbox creation behind the {@code e2b} breaker: 4xx/408/429 never trip it. */
class E2bSandboxClientCircuitBreakerTest {

    private static final String URL = "https://api.e2b.app/sandboxes";

    private static CircuitBreakerRegistry registry(boolean enabled) {
        CircuitBreakerSettings settings =
                new CircuitBreakerSettings(
                        2,
                        0,
                        10,
                        10,
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(30),
                        1,
                        Duration.ofSeconds(30));
        return new CircuitBreakerRegistry(
                enabled, f -> settings, f -> 0, Duration.ZERO, Clock.systemUTC(), null);
    }

    private static E2bSandboxClient client(
            MockRestServiceServer[] serverOut, CircuitBreakerRegistry registry) {
        RestClient.Builder builder = RestClient.builder();
        serverOut[0] = MockRestServiceServer.bindTo(builder).build();
        E2bSandboxClient client = new E2bSandboxClient(builder, mock(E2bCredentialService.class));
        if (registry != null) {
            client.setCircuitBreakerRegistry(registry);
        }
        return client;
    }

    private static void create(E2bSandboxClient client) {
        client.createSandbox("key", "tpl", 60, null, null);
    }

    @Test
    void openCircuitReturnsTheExistingUnreachableShapeWithoutCallingE2b() {
        MockRestServiceServer[] server = new MockRestServiceServer[1];
        CircuitBreakerRegistry registry = registry(true);
        E2bSandboxClient client = client(server, registry);
        server[0]
                .expect(ExpectedCount.times(2), requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> create(client))
                    .isInstanceOf(E2bApiException.class)
                    .satisfies(
                            e ->
                                    assertThat(((E2bApiException) e).getUpstreamStatus())
                                            .isEqualTo(500));
        }
        assertThat(registry.breaker("e2b").state()).isEqualTo(CircuitBreaker.State.OPEN);

        assertThatThrownBy(() -> create(client))
                .isInstanceOf(E2bApiException.class)
                .hasMessageContaining("temporarily unavailable")
                .satisfies(
                        e ->
                                assertThat(((E2bApiException) e).getUpstreamStatus())
                                        .isZero()); // "could not reach" shape
        server[0].verify(); // exactly the two real calls; the third was shed
    }

    @Test
    void flagOffEveryCallReachesE2b() {
        MockRestServiceServer[] server = new MockRestServiceServer[1];
        E2bSandboxClient client = client(server, registry(false));
        server[0].expect(ExpectedCount.times(5), requestTo(URL)).andRespond(withServerError());
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> create(client))
                    .isInstanceOf(E2bApiException.class)
                    .satisfies(
                            e ->
                                    assertThat(((E2bApiException) e).getUpstreamStatus())
                                            .isEqualTo(500));
        }
        server[0].verify();
    }

    @Test
    void tenantErrorsFromE2bDoNotTripThePlatformBreaker() {
        MockRestServiceServer[] server = new MockRestServiceServer[1];
        CircuitBreakerRegistry registry = registry(true);
        E2bSandboxClient client = client(server, registry);
        server[0]
                .expect(ExpectedCount.times(6), requestTo(URL))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        for (int i = 0; i < 6; i++) {
            assertThatThrownBy(() -> create(client))
                    .isInstanceOf(E2bApiException.class)
                    .satisfies(
                            e ->
                                    assertThat(((E2bApiException) e).getUpstreamStatus())
                                            .isEqualTo(401));
        }
        assertThat(registry.breaker("e2b").state()).isEqualTo(CircuitBreaker.State.CLOSED);
        server[0].verify();
    }

    @Test
    void tenantQuotaStatusesFromE2bDoNotTripThePlatformBreaker() {
        MockRestServiceServer[] server = new MockRestServiceServer[1];
        CircuitBreakerRegistry registry = registry(true);
        E2bSandboxClient client = client(server, registry);
        server[0]
                .expect(ExpectedCount.times(3), requestTo(URL))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server[0]
                .expect(ExpectedCount.times(3), requestTo(URL))
                .andRespond(withStatus(HttpStatus.REQUEST_TIMEOUT));
        for (int i = 0; i < 6; i++) {
            assertThatThrownBy(() -> create(client)).isInstanceOf(E2bApiException.class);
        }
        assertThat(registry.breaker("e2b").state()).isEqualTo(CircuitBreaker.State.CLOSED);
        server[0].verify();
    }

    @Test
    void successIsRecordedAndKeepsTheBreakerClosed() {
        MockRestServiceServer[] server = new MockRestServiceServer[1];
        CircuitBreakerRegistry registry = registry(true);
        E2bSandboxClient client = client(server, registry);
        server[0]
                .expect(requestTo(URL))
                .andRespond(
                        withSuccess(
                                "{\"sandboxID\":\"sb1\",\"templateID\":\"tpl\",\"envdVersion\":\"1\","
                                        + "\"trafficAccessToken\":\"tok\"}",
                                MediaType.APPLICATION_JSON));
        assertThat(client.createSandbox("key", "tpl", 60, null, null).sandboxId()).isEqualTo("sb1");
        assertThat(registry.breaker("e2b").state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
