package com.agenticai.core.eventbus.webhook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.mcp.McpRemoteUrlGuard;
import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link WebhookSubscriptionService}'s contract: names and event-type sets are validated before
 * anything is persisted, the per-tenant cap is enforced, the plaintext {@code whsec_...} secret is
 * returned to the caller exactly once (create/rotate only - list/get/update views carry {@code
 * null}), and every stored secret goes through {@link EncryptionService} first.
 */
class WebhookSubscriptionServiceTest {

    private final WebhookSubscriptionRepository subscriptions =
            mock(WebhookSubscriptionRepository.class);
    private final WebhookDeliveryRepository deliveries = mock(WebhookDeliveryRepository.class);
    private final EncryptionService encryption = mock(EncryptionService.class);
    private final McpRemoteUrlGuard urlGuard = mock(McpRemoteUrlGuard.class);
    private final WebhookSubscriptionService service =
            new WebhookSubscriptionService(
                    subscriptions, deliveries, encryption, urlGuard, new ObjectMapper());

    @BeforeEach
    void setUp() {
        when(encryption.encrypt(anyString())).thenAnswer(inv -> "enc(" + inv.getArgument(0) + ")");
        when(subscriptions.save(any(WebhookSubscription.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        TenantContext.set(TenantContext.of("tenant_a", "alice@example.com", Set.of()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void generateSecretProducesTheDocumentedWhsecShape() {
        String secret = WebhookSubscriptionService.generateSecret();

        assertTrue(secret.startsWith("whsec_"));
        assertEquals("whsec_".length() + 48, secret.length(), "24 random bytes, hex encoded");
        assertNotEquals(secret, WebhookSubscriptionService.generateSecret());
    }

    @Test
    void createEncryptsTheSecretReturnsItOnceAndStampsTheCaller() {
        var view = service.create(" My Hook ", "https://example.invalid/h", Set.of("workflow.*"));

        assertEquals("My Hook", view.name());
        assertEquals("https://example.invalid/h", view.url());
        assertEquals(Set.of("workflow.*"), view.eventTypes());
        assertTrue(view.enabled());
        assertEquals("alice@example.com", view.createdBy());
        assertNotNull(view.secret());
        assertTrue(view.secret().startsWith("whsec_"));

        org.mockito.ArgumentCaptor<WebhookSubscription> saved =
                org.mockito.ArgumentCaptor.forClass(WebhookSubscription.class);
        verify(subscriptions).save(saved.capture());
        assertEquals("enc(" + view.secret() + ")", saved.getValue().getSecretEncrypted());
        verify(urlGuard).requireAllowed("https://example.invalid/h");
    }

    @Test
    void createRejectsBadNamesAndEmptyOrJunkTypeSetsBeforeAnySave() {
        assertThrows(
                IllegalArgumentException.class,
                () -> service.create(" ", "https://x.invalid", Set.of("*")));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.create("n".repeat(121), "https://x.invalid", Set.of("*")));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.create("ok", "https://x.invalid", null));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.create("ok", "https://x.invalid", Set.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.create("ok", "https://x.invalid", Set.of(" ", "t".repeat(201))));

        verify(subscriptions, never()).save(any());
    }

    @Test
    void createEnforcesThePerTenantCap() {
        when(subscriptions.count())
                .thenReturn((long) WebhookSubscriptionService.MAX_SUBSCRIPTIONS_PER_TENANT);

        assertThrows(
                IllegalArgumentException.class,
                () -> service.create("ok", "https://x.invalid", Set.of("*")));
        verify(subscriptions, never()).save(any());
    }

    @Test
    void createPropagatesTheUrlGuardRefusal() {
        doThrow(new IllegalArgumentException("not a public URL"))
                .when(urlGuard)
                .requireAllowed("http://169.254.169.254/");

        assertThrows(
                IllegalArgumentException.class,
                () -> service.create("ok", "http://169.254.169.254/", Set.of("*")));
        verify(subscriptions, never()).save(any());
    }

    @Test
    void getAndListNeverExposeASecretAndUnknownIdsThrowNotFound() {
        UUID id = UUID.randomUUID();
        WebhookSubscription sub =
                new WebhookSubscription("h", "https://x.invalid", "enc", Set.of("*"), "it");
        ReflectionTestUtils.setField(sub, "id", id);
        when(subscriptions.findById(id)).thenReturn(Optional.of(sub));
        when(subscriptions.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(sub));

        assertNull(service.get(id).secret());
        assertEquals(1, service.list().size());
        assertNull(service.list().get(0).secret());

        UUID missing = UUID.randomUUID();
        when(subscriptions.findById(missing)).thenReturn(Optional.empty());
        WebhookSubscriptionNotFoundException ex =
                assertThrows(
                        WebhookSubscriptionNotFoundException.class, () -> service.get(missing));
        assertTrue(ex.getMessage().contains(missing.toString()));
        assertThrows(WebhookSubscriptionNotFoundException.class, () -> service.delete(missing));
    }

    @Test
    void updateKeepsFieldsLeftNullAndAppliesTheOnesGiven() {
        UUID id = UUID.randomUUID();
        WebhookSubscription sub =
                new WebhookSubscription("old", "https://x.invalid/a", "enc", Set.of("a"), "it");
        ReflectionTestUtils.setField(sub, "id", id);
        when(subscriptions.findById(id)).thenReturn(Optional.of(sub));

        var view = service.update(id, null, " ", null, null);

        assertEquals("old", view.name());
        assertEquals("https://x.invalid/a", view.url());
        assertEquals(Set.of("a"), view.eventTypes());
        assertTrue(view.enabled());
        assertNull(view.secret(), "update must never echo the secret back");
        verify(urlGuard, never()).requireAllowed(anyString());

        var updated = service.update(id, "new", "https://x.invalid/b", Set.of("b"), false);
        assertEquals("new", updated.name());
        assertEquals("https://x.invalid/b", updated.url());
        assertEquals(Set.of("b"), updated.eventTypes());
        assertEquals(false, updated.enabled());
        verify(urlGuard).requireAllowed("https://x.invalid/b");
    }

    @Test
    void rotateSecretStoresTheEncryptedReplacementAndReturnsItOnce() {
        UUID id = UUID.randomUUID();
        WebhookSubscription sub =
                new WebhookSubscription("h", "https://x.invalid", "enc(old)", Set.of("*"), "it");
        ReflectionTestUtils.setField(sub, "id", id);
        when(subscriptions.findById(id)).thenReturn(Optional.of(sub));

        var view = service.rotateSecret(id);

        assertNotNull(view.secret());
        assertEquals("enc(" + view.secret() + ")", sub.getSecretEncrypted());
    }

    @Test
    void enqueueTestDeliveryPersistsASyntheticWebhookTestRow() {
        UUID id = UUID.randomUUID();
        WebhookSubscription sub =
                new WebhookSubscription("h", "https://x.invalid", "enc", Set.of("*"), "it");
        ReflectionTestUtils.setField(sub, "id", id);
        when(subscriptions.findById(id)).thenReturn(Optional.of(sub));
        when(deliveries.save(any(WebhookDelivery.class)))
                .thenAnswer(
                        inv -> {
                            WebhookDelivery d = inv.getArgument(0);
                            ReflectionTestUtils.setField(d, "id", UUID.randomUUID());
                            return d;
                        });

        UUID deliveryId = service.enqueueTestDelivery(id);

        assertNotNull(deliveryId);
        org.mockito.ArgumentCaptor<WebhookDelivery> saved =
                org.mockito.ArgumentCaptor.forClass(WebhookDelivery.class);
        verify(deliveries).save(saved.capture());
        WebhookDelivery row = saved.getValue();
        assertEquals(id, row.getSubscriptionId());
        assertEquals("webhook.test", row.getEventType());
        assertTrue(row.getPayloadJson().contains("tenant_a"));
        assertTrue(row.getPayloadJson().contains("webhook.test"));
    }

    @Test
    void deleteRemovesTheTenantsRow() {
        UUID id = UUID.randomUUID();
        WebhookSubscription sub =
                new WebhookSubscription("h", "https://x.invalid", "enc", Set.of("*"), "it");
        ReflectionTestUtils.setField(sub, "id", id);
        when(subscriptions.findById(id)).thenReturn(Optional.of(sub));

        service.delete(id);

        verify(subscriptions).delete(sub);
    }
}
