package com.agenticai.core.billing.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Covers the network-calling half of {@link PhonePeGatewayClient} that {@link
 * PhonePeGatewayClientTest} deliberately left out (see that class's javadoc) - {@link
 * PhonePeGatewayClient#obtainAccessToken()}'s OAuth call, the actual HTTP call inside {@link
 * PhonePeGatewayClient#createSubscriptionWithTrial}, and the in-memory access-token cache
 * (60s-early-refresh window) sitting between them. Zero coverage existed for any of this before -
 * a caching bug (never refreshing, or refreshing on every call and hammering PhonePe's OAuth
 * endpoint) or a broken error-to-exception mapping (a raw {@code RestClientException} leaking
 * past this class's public API instead of the documented {@link PaymentGatewayException}) would
 * have gone unnoticed until a real signup attempt against PhonePe.
 *
 * <p>Uses a real local {@code com.sun.net.httpserver.HttpServer} (JDK-provided, no extra test
 * dependency) bound to 127.0.0.1 on an ephemeral port, rather than Mockito, because {@link
 * PhonePeGatewayClient} builds its own internal {@code RestClient} instances in its constructor -
 * there is no seam to inject a mock HTTP layer from outside, so an actual (if fake and
 * entirely local) HTTP server is the only way to exercise this code without changing production
 * code. {@code oauth-base-url} and {@code api-base-url} both point at this same local server,
 * same as how {@link PhonePeGatewayClientTest} already sets both constructor args to the same
 * value.
 */
class PhonePeGatewayClientNetworkTest {

    private static final String CLIENT_ID = "dummy_client_id";
    private static final String CLIENT_SECRET = "dummy_client_secret";
    private static final String CLIENT_VERSION = "1";
    private static final String MERCHANT_ID = "dummy_merchant_id";
    private static final String WEBHOOK_USERNAME = "dummy_webhook_username";
    private static final String WEBHOOK_PASSWORD = "dummy_webhook_password";
    // INR 500 in paise - comfortably under the NPCI UPI Autopay ceiling so the ceiling check
    // (already covered by PhonePeGatewayClientTest) doesn't short-circuit before the HTTP calls
    // this class exists to exercise.
    private static final String PLAN_CONFIG = "50000:MONTHLY";

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static PhonePeGatewayClient clientFor(HttpServer server) {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        return new PhonePeGatewayClient(
            org.springframework.web.client.RestClient.builder(),
            baseUrl,
            baseUrl,
            CLIENT_ID,
            CLIENT_SECRET,
            CLIENT_VERSION,
            MERCHANT_ID,
            WEBHOOK_USERNAME,
            WEBHOOK_PASSWORD,
            PLAN_CONFIG,
            PLAN_CONFIG
        );
    }

    private static HttpServer startServer(HttpHandler oauthHandler, HttpHandler subscriptionHandler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/oauth/token", oauthHandler);
        server.createContext("/v2/subscriptions/setup", subscriptionHandler);
        server.start();
        return server;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static HttpHandler fixedJsonResponse(int status, String body) {
        return exchange -> {
            try {
                respond(exchange, status, body);
            } finally {
                exchange.close();
            }
        };
    }

    private static String oauthSuccessBody(String token) {
        long expiresAt = Instant.now().plusSeconds(3600).getEpochSecond();
        return "{\"access_token\":\"" + token + "\",\"expires_at\":" + expiresAt + ",\"token_type\":\"O-Bearer\"}";
    }

    // --- happy path: OAuth token fetch + subscription setup call, wired together -----------

    @Test
    void createSubscriptionWithTrialReturnsTheParsedSubscriptionOnSuccess() throws Exception {
        AtomicInteger oauthHits = new AtomicInteger();
        AtomicInteger subscriptionHits = new AtomicInteger();
        String[] capturedAuthHeader = new String[1];

        HttpHandler oauthHandler = exchange -> {
            try {
                oauthHits.incrementAndGet();
                respond(exchange, 200, oauthSuccessBody("tok_abc"));
            } finally {
                exchange.close();
            }
        };
        HttpHandler subscriptionHandler = exchange -> {
            try {
                subscriptionHits.incrementAndGet();
                capturedAuthHeader[0] = exchange.getRequestHeaders().getFirst("Authorization");
                respond(exchange, 200,
                    "{\"subscriptionId\":\"pp_sub_123\",\"redirectUrl\":\"https://mercury.phonepe.com/transact/abcd\",\"state\":\"PENDING\"}");
            } finally {
                exchange.close();
            }
        };

        server = startServer(oauthHandler, subscriptionHandler);
        PhonePeGatewayClient client = clientFor(server);

        GatewaySubscription subscription = client.createSubscriptionWithTrial(
            "tenant-1", "premium", 0, "a@example.com", "A Customer"
        );

        assertEquals("pp_sub_123", subscription.id());
        assertEquals("https://mercury.phonepe.com/transact/abcd", subscription.checkoutUrl());
        assertEquals("PENDING", subscription.status());
        assertEquals(1, oauthHits.get());
        assertEquals(1, subscriptionHits.get());
        // The token obtained from the OAuth call must actually be the one sent on to the
        // subscription-setup call, prefixed exactly as PhonePe documents ("O-Bearer <token>",
        // not the more common "Bearer <token>").
        assertEquals("O-Bearer tok_abc", capturedAuthHeader[0]);
    }

    // --- access-token caching ----------------------------------------------------------------

    @Test
    void aSecondCallWithinTheTokenValidityWindowReusesTheCachedTokenInsteadOfRefetchingIt() throws Exception {
        AtomicInteger oauthHits = new AtomicInteger();
        AtomicInteger subscriptionHits = new AtomicInteger();

        HttpHandler oauthHandler = exchange -> {
            try {
                oauthHits.incrementAndGet();
                respond(exchange, 200, oauthSuccessBody("tok_cached"));
            } finally {
                exchange.close();
            }
        };
        HttpHandler subscriptionHandler = exchange -> {
            try {
                subscriptionHits.incrementAndGet();
                respond(exchange, 200,
                    "{\"subscriptionId\":\"pp_sub_1\",\"redirectUrl\":\"https://mercury.phonepe.com/x\",\"state\":\"PENDING\"}");
            } finally {
                exchange.close();
            }
        };

        server = startServer(oauthHandler, subscriptionHandler);
        PhonePeGatewayClient client = clientFor(server);

        client.createSubscriptionWithTrial("tenant-1", "premium", 0, "a@example.com", "A");
        client.createSubscriptionWithTrial("tenant-2", "premium", 0, "b@example.com", "B");

        assertEquals(1, oauthHits.get(), "the cached access token should be reused, not refetched, on the second call");
        assertEquals(2, subscriptionHits.get());
    }

    // --- error mapping: every failure surfaces as PaymentGatewayException, never a raw --------
    // --- RestClientException leaking past this class's documented contract -------------------

    @Test
    void anOAuthResponseMissingAccessTokenIsWrappedAsAPaymentGatewayException() throws Exception {
        server = startServer(
            fixedJsonResponse(200, "{\"token_type\":\"O-Bearer\"}"), // 200 OK but no access_token field
            fixedJsonResponse(200, "{}")
        );
        PhonePeGatewayClient client = clientFor(server);

        PaymentGatewayException ex = assertThrows(PaymentGatewayException.class, () ->
            client.createSubscriptionWithTrial("tenant-1", "premium", 0, "a@example.com", "A")
        );
        assertTrue(ex.getMessage().contains("access_token"));
    }

    @Test
    void anOauthServerErrorIsWrappedAsAPaymentGatewayExceptionNotLeakedAsRestClientException() throws Exception {
        server = startServer(
            fixedJsonResponse(500, "{\"error\":\"internal\"}"),
            fixedJsonResponse(200, "{}")
        );
        PhonePeGatewayClient client = clientFor(server);

        assertThrows(PaymentGatewayException.class, () ->
            client.createSubscriptionWithTrial("tenant-1", "premium", 0, "a@example.com", "A")
        );
    }

    @Test
    void aSubscriptionSetupServerErrorIsWrappedAsAPaymentGatewayException() throws Exception {
        server = startServer(
            fixedJsonResponse(200, oauthSuccessBody("tok_x")),
            fixedJsonResponse(500, "{\"error\":\"internal\"}")
        );
        PhonePeGatewayClient client = clientFor(server);

        assertThrows(PaymentGatewayException.class, () ->
            client.createSubscriptionWithTrial("tenant-1", "premium", 0, "a@example.com", "A")
        );
    }
}
