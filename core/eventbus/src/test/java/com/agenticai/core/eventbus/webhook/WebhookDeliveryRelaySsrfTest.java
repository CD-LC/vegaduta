package com.agenticai.core.eventbus.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.mcp.McpRemoteUrlGuard;
import com.agenticai.core.security.net.SsrfGuard;
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * SEC-027 on the webhook relay: the delivery-time re-check alone left a DNS rebinding window before
 * the socket opened. The POST now goes through the guard-pinned client, so even when that re-check
 * passes (simulated here by a URL guard that says "fine"), a host whose DNS now points inside is
 * never connected to.
 */
class WebhookDeliveryRelaySsrfTest {

    private HttpServer server;
    private int port;
    private final AtomicInteger hits = new AtomicInteger();

    private WebhookDeliveryRepository deliveries;
    private WebhookSubscriptionRepository subscriptions;
    private McpRemoteUrlGuard urlGuard;
    private TenantSchemaIterator schemas;
    private PgAdvisoryJobLock lock;
    private EncryptionService encryption;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(
                "/hook",
                exchange -> {
                    hits.incrementAndGet();
                    exchange.getRequestBody().readAllBytes();
                    exchange.sendResponseHeaders(204, -1);
                    exchange.close();
                });
        server.createContext(
                "/bounce",
                exchange -> {
                    hits.incrementAndGet();
                    exchange.getResponseHeaders()
                            .add("Location", "http://hooks.example.com:" + port + "/hook");
                    exchange.sendResponseHeaders(307, -1);
                    exchange.close();
                });
        server.start();
        port = server.getAddress().getPort();

        deliveries = mock(WebhookDeliveryRepository.class);
        subscriptions = mock(WebhookSubscriptionRepository.class);
        urlGuard = mock(McpRemoteUrlGuard.class);
        when(urlGuard.checkResolved(anyString()))
                .thenReturn(Optional.empty()); // "was public when checked"
        schemas = mock(TenantSchemaIterator.class);
        doAnswer(
                        inv -> {
                            ((Consumer<String>) inv.getArgument(0)).accept("tenant_a");
                            return null;
                        })
                .when(schemas)
                .forEachActiveTenantSchema(any());
        // The relay now iterates with the idle-aware variant (a no-op skip when the feature is
        // off).
        doAnswer(
                        inv -> {
                            ((Predicate<String>) inv.getArgument(1)).test("tenant_a");
                            return null;
                        })
                .when(schemas)
                .forEachActiveTenantSchemaSkippingIdle(anyString(), any());
        lock = mock(PgAdvisoryJobLock.class);
        when(lock.tryRunExclusive(anyString(), any()))
                .thenAnswer(
                        inv -> {
                            ((Runnable) inv.getArgument(1)).run();
                            return true;
                        });
        encryption = mock(EncryptionService.class);
        when(encryption.decrypt(any())).thenReturn("whsec");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private WebhookDelivery pendingDeliveryTo(String url) {
        WebhookSubscription sub = new WebhookSubscription("s", url, "enc", Set.of("*"), "tester");
        WebhookDelivery delivery =
                new WebhookDelivery(UUID.randomUUID(), "workflow.completed", "run-1", "{\"a\":1}");
        ReflectionTestUtils.setField(delivery, "id", UUID.randomUUID());
        when(deliveries.findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
                        any(), any(), any()))
                .thenReturn(List.of(delivery));
        when(subscriptions.findById(any())).thenReturn(Optional.of(sub));
        return delivery;
    }

    private WebhookDeliveryRelay relay(SsrfGuard guard) {
        return new WebhookDeliveryRelay(
                schemas, deliveries, subscriptions, encryption, urlGuard, lock, guard);
    }

    private static SsrfGuard guard(Map<String, String> dns, Set<String> allow) {
        return SsrfGuard.of(
                allow,
                host -> {
                    String ip = dns.get(host);
                    return ip != null
                            ? new InetAddress[] {InetAddress.getByName(ip)}
                            : InetAddress.getAllByName(host);
                });
    }

    @Test
    void hostThatRebindsToLoopbackAfterTheCheckIsNeverConnectedTo() throws Exception {
        WebhookDelivery delivery = pendingDeliveryTo("http://hooks.example.com:" + port + "/hook");
        WebhookDeliveryRelay relay =
                relay(guard(Map.of("hooks.example.com", "127.0.0.1"), Set.of()));
        try {
            relay.deliverPending();
        } finally {
            relay.close();
        }
        assertThat(hits.get()).isZero();
        assertThat(delivery.getStatus()).isNotEqualTo(WebhookDelivery.STATUS_DELIVERED);
        assertThat(delivery.getAttemptCount()).isEqualTo(1);
        assertThat(delivery.getLastError()).contains("refused").doesNotContain("127.0.0.1");
    }

    @Test
    void redirectsAreNotFollowed() throws Exception {
        WebhookDelivery delivery =
                pendingDeliveryTo("http://hooks.example.com:" + port + "/bounce");
        // Allow-listed so only the redirect policy is under test.
        WebhookDeliveryRelay relay =
                relay(guard(Map.of("hooks.example.com", "127.0.0.1"), Set.of("hooks.example.com")));
        try {
            relay.deliverPending();
        } finally {
            relay.close();
        }
        assertThat(hits.get()).isEqualTo(1); // the /bounce hop only
        assertThat(delivery.getLastError()).isEqualTo("HTTP 307");
    }

    @Test
    void allowedHostIsDelivered() throws Exception {
        WebhookDelivery delivery = pendingDeliveryTo("http://hooks.example.com:" + port + "/hook");
        WebhookDeliveryRelay relay =
                relay(guard(Map.of("hooks.example.com", "127.0.0.1"), Set.of("hooks.example.com")));
        try {
            relay.deliverPending();
        } finally {
            relay.close();
        }
        assertThat(hits.get()).isEqualTo(1);
        assertThat(delivery.getStatus()).isEqualTo(WebhookDelivery.STATUS_DELIVERED);
    }
}
