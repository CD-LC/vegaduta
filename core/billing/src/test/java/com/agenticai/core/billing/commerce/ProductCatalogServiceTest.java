package com.agenticai.core.billing.commerce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.billing.commerce.CheckoutSessionService.CreatePaymentLinkCommand;
import com.agenticai.core.billing.commerce.CommerceProduct.ProductAvailability;
import com.agenticai.core.billing.commerce.ProductCatalogService.CartItem;
import com.agenticai.core.billing.commerce.ProductCatalogService.FeedCheckoutLimitException;
import com.agenticai.core.billing.commerce.ProductCatalogService.ProductUpsert;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Catalog CRUD scoping, public-feed shape, and server-side-priced cart
 * checkout for {@link ProductCatalogService}. The checkout tests pin the two
 * properties that make a PUBLIC payment-link writer safe: the buyer picks
 * product+quantity and the server takes the amount from the catalog row, and
 * the per-tenant daily cap actually trips.
 */
class ProductCatalogServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-24T10:00:00Z");
    private static final String TENANT = "tenant_acme";

    private CommerceProductRepository products;
    private CheckoutSessionRepository checkoutSessions;
    private CheckoutSessionService checkoutSessionService;
    private ProductCatalogService service;

    @BeforeEach
    void setUp() {
        products = mock(CommerceProductRepository.class);
        checkoutSessions = mock(CheckoutSessionRepository.class);
        checkoutSessionService = mock(CheckoutSessionService.class);
        service = new ProductCatalogService(
            products, checkoutSessions, checkoutSessionService, Clock.fixed(NOW, ZoneOffset.UTC), 100);
    }

    private static CommerceProduct product(UUID id, String tenantId, boolean published, ProductAvailability availability) {
        return new CommerceProduct(
            id, tenantId, "SKU-1", "Masala dosa", "crisp", "food", 12_000L, "INR", availability,
            null, null, published, NOW, NOW);
    }

    private static ProductUpsert upsert(String name, long price) {
        return new ProductUpsert("SKU-1", name, "desc", "food", price, "INR",
            ProductAvailability.IN_STOCK, null, null, true);
    }

    // ------------------------------ catalog ---------------------------------

    @Test
    void create_persistsATenantScopedProduct() {
        CommerceProduct created = service.create(TENANT, upsert("Masala dosa", 12_000L));

        ArgumentCaptor<CommerceProduct> saved = ArgumentCaptor.forClass(CommerceProduct.class);
        verify(products).insert(saved.capture());
        assertEquals(TENANT, saved.getValue().tenantId());
        assertTrue(saved.getValue().published());
        assertEquals("Masala dosa", created.name());
    }

    @Test
    void update_onAnotherTenantsId_isEmpty_notAnUpdate() {
        UUID id = UUID.randomUUID();
        when(products.findByIdAndTenantId(id, TENANT)).thenReturn(Optional.empty());

        assertTrue(service.update(TENANT, id, upsert("x", 1)).isEmpty());
        verify(products, never()).update(any());
    }

    // -------------------------------- feed -----------------------------------

    @Test
    void feed_listsPublishedProductsInTheAcpShapedShape() {
        UUID id = UUID.randomUUID();
        when(products.listPublishedByTenantId(TENANT))
            .thenReturn(List.of(product(id, TENANT, true, ProductAvailability.IN_STOCK)));

        JSONObject feed = service.feed(TENANT);

        assertEquals(TENANT, feed.getJSONObject("seller").getString("tenant_id"));
        assertEquals("acp-shaped", feed.getJSONObject("seller").getString("protocol"));
        JSONArray items = feed.getJSONArray("items");
        assertEquals(1, items.length());
        JSONObject item = items.getJSONObject(0);
        assertEquals(id.toString(), item.getString("id"));
        assertEquals("Masala dosa", item.getString("title"));
        assertEquals(12_000L, item.getJSONObject("price").getLong("amount"));
        assertEquals("in_stock", item.getString("availability"));
        assertTrue(item.getBoolean("purchasable"));
    }

    @Test
    void feed_marksAnUnpublishedOrOutOfStockRowNotPurchasable() {
        UUID id = UUID.randomUUID();
        when(products.listPublishedByTenantId(TENANT))
            .thenReturn(List.of(product(id, TENANT, true, ProductAvailability.OUT_OF_STOCK)));

        JSONObject item = service.feed(TENANT).getJSONArray("items").getJSONObject(0);
        assertEquals("out_of_stock", item.getString("availability"));
        assertFalse(item.getBoolean("purchasable"));
    }

    // ------------------------------ checkout ---------------------------------

    @Test
    void checkout_pricesTheCartServerSide_andPassesRealLineItems() {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        when(products.findByIdAndTenantId(p1, TENANT))
            .thenReturn(Optional.of(product(p1, TENANT, true, ProductAvailability.IN_STOCK)));
        when(products.findByIdAndTenantId(p2, TENANT))
            .thenReturn(Optional.of(new CommerceProduct(
                p2, TENANT, "SKU-2", "Filter coffee", "hot", "food", 6_000L, "INR",
                ProductAvailability.IN_STOCK, null, null, true, NOW, NOW)));
        CheckoutSession session = mock(CheckoutSession.class);
        when(checkoutSessionService.createPaymentLink(any(), anyString())).thenReturn(session);

        service.checkout(TENANT, List.of(new CartItem(p1, 2), new CartItem(p2, 1)), "+919876543210", null);

        ArgumentCaptor<CreatePaymentLinkCommand> cmd = ArgumentCaptor.forClass(CreatePaymentLinkCommand.class);
        ArgumentCaptor<String> lineItems = ArgumentCaptor.forClass(String.class);
        verify(checkoutSessionService).createPaymentLink(cmd.capture(), lineItems.capture());

        // 2x12000 + 1x6000 = 30000 - computed from the catalog, not the request.
        assertEquals(30_000L, cmd.getValue().amountPaise());
        assertEquals("acp", cmd.getValue().channel());
        assertEquals(TENANT, cmd.getValue().tenantId());
        JSONArray lines = new JSONArray(lineItems.getValue());
        assertEquals(2, lines.length());
        assertEquals(2, lines.getJSONObject(0).getInt("quantity"));
        assertEquals(24_000L, lines.getJSONObject(0).getLong("total"));
    }

    @Test
    void checkout_anUnpublishedProduct_failsTheWholeCart() {
        UUID id = UUID.randomUUID();
        when(products.findByIdAndTenantId(id, TENANT))
            .thenReturn(Optional.of(product(id, TENANT, false, ProductAvailability.IN_STOCK)));

        assertThrows(IllegalArgumentException.class,
            () -> service.checkout(TENANT, List.of(new CartItem(id, 1)), null, null));
        verify(checkoutSessionService, never()).createPaymentLink(any(), anyString());
    }

    @Test
    void checkout_anOutOfStockProduct_failsTheWholeCart() {
        UUID id = UUID.randomUUID();
        when(products.findByIdAndTenantId(id, TENANT))
            .thenReturn(Optional.of(product(id, TENANT, true, ProductAvailability.OUT_OF_STOCK)));

        assertThrows(IllegalArgumentException.class,
            () -> service.checkout(TENANT, List.of(new CartItem(id, 1)), null, null));
        verify(checkoutSessionService, never()).createPaymentLink(any(), anyString());
    }

    @Test
    void checkout_overTheDailyCap_is429() {
        when(checkoutSessions.countByTenantIdAndChannelAndCreatedAtAfter(
            eq(TENANT), eq("acp"), any())).thenReturn(100L);

        assertThrows(FeedCheckoutLimitException.class,
            () -> service.checkout(TENANT, List.of(new CartItem(UUID.randomUUID(), 1)), null, null));
        verify(products, never()).findByIdAndTenantId(any(), anyString());
    }

    @Test
    void checkout_anEmptyCart_isRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> service.checkout(TENANT, List.of(), null, null));
    }
}
