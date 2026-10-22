package com.agenticai.core.billing.commerce;

import com.agenticai.core.billing.commerce.CheckoutSessionService.CreatePaymentLinkCommand;
import com.agenticai.core.billing.commerce.CommerceProduct.ProductAvailability;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The tenant product catalog and its two read surfaces - see this package's {@code package-info}
 * for where this sits next to checkout sessions.
 *
 * <p>Three entry points, three trust levels:
 *
 * <ul>
 *   <li><b>Catalog management</b> ({@link #create}, {@link #update}, {@link #delete}, {@link
 *       #listForTenant}) - tenant members only; every call takes the caller's tenant id from {@code
 *       TenantContext}, never a request field.
 *   <li><b>{@link #feed}</b> - the PUBLIC, unauthenticated ACP-shaped product feed ({@code GET
 *       /api/commerce/feed/{tenantId}}). Returns {@code published} rows only and carries no
 *       tenant-internal fields.
 *   <li><b>{@link #checkout}</b> - public cart checkout: a shopping agent posts {@code
 *       [{product_id, quantity}]} and gets back a real checkout session + hosted payment URL.
 *       Prices are taken from the catalog rows server-side - the request can choose WHAT to buy,
 *       never the amount - and the cart is refused (400) on any unpublished, out-of-stock, or
 *       foreign product. A per-tenant daily cap ({@code app.commerce.feed.max-checkouts-per-day})
 *       bounds unauthenticated link creation.
 * </ul>
 */
@Service
@ConditionalOnEditionFeature(EditionFeature.BILLING_PAYMENTS)
public class ProductCatalogService {

    /**
     * Channel stamped on feed-checkout sessions - how {@link #checkout}'s abuse cap counts them.
     */
    static final String CHANNEL_ACP = "acp";

    private final CommerceProductRepository products;
    private final CheckoutSessionRepository checkoutSessions;
    private final CheckoutSessionService checkoutSessionService;
    private final Clock clock;
    private final long maxCheckoutsPerTenantPerDay;

    @Autowired
    public ProductCatalogService(
            CommerceProductRepository products,
            CheckoutSessionRepository checkoutSessions,
            CheckoutSessionService checkoutSessionService,
            @Value("${app.commerce.feed.max-checkouts-per-day:100}")
                    long maxCheckoutsPerTenantPerDay) {
        this(
                products,
                checkoutSessions,
                checkoutSessionService,
                Clock.systemUTC(),
                maxCheckoutsPerTenantPerDay);
    }

    /** Test seam - injectable clock. */
    ProductCatalogService(
            CommerceProductRepository products,
            CheckoutSessionRepository checkoutSessions,
            CheckoutSessionService checkoutSessionService,
            Clock clock,
            long maxCheckoutsPerTenantPerDay) {
        this.products = products;
        this.checkoutSessions = checkoutSessions;
        this.checkoutSessionService = checkoutSessionService;
        this.clock = clock;
        this.maxCheckoutsPerTenantPerDay = maxCheckoutsPerTenantPerDay;
    }

    // ============================ tenant catalog ============================

    public CommerceProduct create(String tenantId, ProductUpsert upsert) {
        validate(tenantId, upsert);
        Instant now = clock.instant();
        CommerceProduct product =
                new CommerceProduct(
                        UUID.randomUUID(),
                        tenantId,
                        nullIfBlank(upsert.sku()),
                        upsert.name().trim(),
                        nullIfBlank(upsert.description()),
                        nullIfBlank(upsert.category()),
                        upsert.pricePaise(),
                        nullIfBlank(upsert.currency()) == null
                                ? "INR"
                                : upsert.currency().trim().toUpperCase(java.util.Locale.ROOT),
                        upsert.availability() == null
                                ? ProductAvailability.IN_STOCK
                                : upsert.availability(),
                        nullIfBlank(upsert.imageUrl()),
                        nullIfBlank(upsert.productUrl()),
                        upsert.published(),
                        now,
                        now);
        products.insert(product);
        return product;
    }

    /**
     * Tenant-scoped update - a product id belonging to another tenant is indistinguishable from a
     * nonexistent one (empty Optional, caller 404s).
     */
    public Optional<CommerceProduct> update(String tenantId, UUID id, ProductUpsert upsert) {
        validate(tenantId, upsert);
        Optional<CommerceProduct> found = products.findByIdAndTenantId(id, tenantId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        CommerceProduct updated =
                new CommerceProduct(
                        id,
                        tenantId,
                        nullIfBlank(upsert.sku()),
                        upsert.name().trim(),
                        nullIfBlank(upsert.description()),
                        nullIfBlank(upsert.category()),
                        upsert.pricePaise(),
                        nullIfBlank(upsert.currency()) == null
                                ? "INR"
                                : upsert.currency().trim().toUpperCase(java.util.Locale.ROOT),
                        upsert.availability() == null
                                ? ProductAvailability.IN_STOCK
                                : upsert.availability(),
                        nullIfBlank(upsert.imageUrl()),
                        nullIfBlank(upsert.productUrl()),
                        upsert.published(),
                        found.get().createdAt(),
                        clock.instant());
        products.update(updated);
        return Optional.of(updated);
    }

    public boolean delete(String tenantId, UUID id) {
        return products.deleteByIdAndTenantId(id, tenantId) > 0;
    }

    public List<CommerceProduct> listForTenant(String tenantId, int limit) {
        return products.listByTenantId(tenantId, Math.max(1, Math.min(limit, 500)));
    }

    // ============================== public feed =============================

    /**
     * The ACP-shaped product feed for one tenant - {@code published} rows only. The shape
     * deliberately mirrors what shopping-agent consumers expect (OpenAI-style product feed fields:
     * {@code id}/{@code title}/{@code description}/{@code price}/{@code availability}/{@code
     * image_url}/{@code link}) while carrying this platform's checkout entry point so a buyer knows
     * where the cart POST goes. Prices are minor units - the same money rule as
     * checkout_session.amount_paise.
     */
    public JSONObject feed(String tenantId) {
        JSONArray items = new JSONArray();
        for (CommerceProduct p : products.listPublishedByTenantId(tenantId)) {
            JSONObject item = new JSONObject();
            item.put("id", p.id().toString());
            if (p.sku() != null) {
                item.put("sku", p.sku());
            }
            item.put("title", p.name());
            if (p.description() != null) {
                item.put("description", p.description());
            }
            if (p.category() != null) {
                item.put("category", p.category());
            }
            item.put(
                    "price",
                    new JSONObject().put("amount", p.pricePaise()).put("currency", p.currency()));
            item.put("availability", p.availability().wireValue());
            item.put("purchasable", p.purchasable());
            if (p.imageUrl() != null) {
                item.put("image_url", p.imageUrl());
            }
            if (p.productUrl() != null) {
                item.put("link", p.productUrl());
            }
            items.put(item);
        }
        return new JSONObject()
                .put(
                        "seller",
                        new JSONObject()
                                .put("tenant_id", tenantId)
                                .put(
                                        "checkout_endpoint",
                                        "/api/commerce/feed/" + tenantId + "/checkout")
                                // Honest naming: the wire shape is ACP-shaped, not an ACP
                                // conformance claim - see this package's package-info.
                                .put("protocol", "acp-shaped"))
                .put("items", items);
    }

    /**
     * Public cart checkout. Server-side pricing is the whole point: the caller supplies product ids
     * + quantities and this method takes the amounts from the catalog rows themselves. A cart line
     * pointing at an unpublished, out-of-stock, or foreign-tenant product fails the whole request
     * (400) - silently repricing or dropping lines would hand a buyer a receipt that doesn't match
     * what they saw.
     *
     * @param customerPhone optional - pre-fills the hosted payment page
     */
    public CheckoutSession checkout(
            String tenantId, List<CartItem> items, String customerPhone, String customerName) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException(
                    "items is required - at least one {product_id, quantity}");
        }
        if (items.size() > 50) {
            throw new IllegalArgumentException("A cart may hold at most 50 line items");
        }
        enforceCheckoutCap(tenantId);

        JSONArray lineItems = new JSONArray();
        long total = 0;
        List<String> names = new ArrayList<>();
        for (CartItem item : items) {
            if (item.quantity() < 1 || item.quantity() > 99) {
                throw new IllegalArgumentException("quantity must be between 1 and 99");
            }
            CommerceProduct product =
                    products.findByIdAndTenantId(item.productId(), tenantId)
                            .filter(CommerceProduct::purchasable)
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "Product "
                                                            + item.productId()
                                                            + " is not purchasable"));
            long lineTotal = Math.multiplyExact(product.pricePaise(), item.quantity());
            total = Math.addExact(total, lineTotal);
            names.add(product.name());
            lineItems.put(
                    new JSONObject()
                            .put("id", product.id().toString())
                            .put("name", product.name())
                            .put("quantity", item.quantity())
                            .put("unit_amount", product.pricePaise())
                            .put("total", lineTotal));
        }

        String description = names.size() == 1 ? names.get(0) : names.size() + " items";
        return checkoutSessionService.createPaymentLink(
                new CreatePaymentLinkCommand(
                        tenantId,
                        null,
                        null,
                        CHANNEL_ACP,
                        total,
                        description,
                        customerPhone,
                        customerName),
                lineItems.toString());
    }

    /**
     * Unauthenticated link creation needs a bound - per-tenant sessions/day, same posture as the
     * template submission cap.
     */
    private void enforceCheckoutCap(String tenantId) {
        long recent =
                checkoutSessions.countByTenantIdAndChannelAndCreatedAtAfter(
                        tenantId, CHANNEL_ACP, clock.instant().minus(Duration.ofDays(1)));
        if (recent >= maxCheckoutsPerTenantPerDay) {
            throw new FeedCheckoutLimitException(maxCheckoutsPerTenantPerDay);
        }
    }

    private static void validate(String tenantId, ProductUpsert upsert) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (upsert.name() == null || upsert.name().isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (upsert.pricePaise() < 0) {
            throw new IllegalArgumentException("pricePaise must not be negative");
        }
    }

    /** Create/update payload for a catalog row. */
    public record ProductUpsert(
            String sku,
            String name,
            String description,
            String category,
            long pricePaise,
            String currency,
            ProductAvailability availability,
            String imageUrl,
            String productUrl,
            boolean published) {}

    /** One cart line the shopping agent posts to the feed checkout. */
    public record CartItem(UUID productId, int quantity) {}

    /** 429 - the per-tenant-per-day public checkout cap. */
    public static class FeedCheckoutLimitException extends RuntimeException {
        public FeedCheckoutLimitException(long cap) {
            super(
                    "This seller has reached today's checkout-session limit ("
                            + cap
                            + "). Try again tomorrow.");
        }
    }

    private static String nullIfBlank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
