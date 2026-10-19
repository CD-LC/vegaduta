package com.agenticai.core.omnichannel.meta;

import com.agenticai.core.resilience.CircuitBreakerRegistry;
import com.agenticai.core.resilience.DependencyUnavailableException;
import com.agenticai.core.resilience.ResilienceGuard;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * The one place the Meta Graph clients ({@link MetaGraphApiClient}, {@link
 * MetaMediaGraphApiClient}) pass a request through the {@code meta-graph} circuit breaker.
 *
 * <p>On by default; with {@code app.resilience.circuit-breaker.enabled=false} the registry gives a
 * NOOP guard (a null registry - hand-built clients in tests - is treated the same), and then this
 * is exactly {@code httpClient.send(request, ofString())}.
 *
 * <p>When the circuit is open the dependency is NOT called and an {@link IOException} is thrown.
 * That is deliberate: every call site already catches {@code Exception} around its send and turns
 * it into its existing "Could not reach the Meta Graph API: ..." {@code ChannelDeliveryException} /
 * {@code ChannelConfigurationException}, so the user-visible "unavailable" result is the one a
 * failed call already gives, never a 500.
 *
 * <p>What counts as Meta being unhealthy: connect failures, timeouts and other I/O errors, and 5xx
 * replies. Any 4xx (bad token, bad recipient, window closed) means Meta answered and is not an
 * outage; those are per-tenant and must not shed other tenants' sends.
 */
final class MetaGraphGuard {

    static final String DEPENDENCY = "meta-graph";

    private MetaGraphGuard() {}

    static HttpResponse<String> send(
            CircuitBreakerRegistry registry, HttpClient client, HttpRequest request)
            throws IOException, InterruptedException {
        ResilienceGuard guard =
                registry == null ? ResilienceGuard.NOOP : registry.guard(DEPENDENCY);
        ResilienceGuard.Call call;
        try {
            call = guard.begin();
        } catch (DependencyUnavailableException shed) {
            throw new IOException(
                    "Meta Graph API is temporarily unavailable (circuit open after repeated"
                            + " failures)",
                    shed);
        }
        try {
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 500) {
                call.failure(new IOException("HTTP " + response.statusCode()));
            } else {
                call.success();
            }
            return response;
        } catch (IOException e) {
            call.failure(e);
            throw e;
        } finally {
            call.close(); // neutral for an interrupt or any other unsettled exit
        }
    }
}
