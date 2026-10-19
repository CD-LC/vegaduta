package com.agenticai.core.billing.commerce;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code control_plane.commerce_product} (migration V252) - a
 * tenant-managed sellable item, published to the ACP-shaped product feed for
 * shopping agents. A plain record, not a JPA entity, for exactly the reason
 * {@link CheckoutSession} is one: this module has no JPA entity scan and
 * control-plane tables are schema-fixed.
 *
 * @param tenantId    the tenant's schema name - same convention as {@link
 *                    CheckoutSession#tenantId()}, NOT the {@code
 *                    control_plane.tenant.id} UUID
 * @param pricePaise  minor units, never floating-point - the money rule
 *                    shared across core/billing
 * @param availability stock state the feed advertises
 *                     ({@code IN_STOCK}/{@code OUT_OF_STOCK}/{@code PREORDER})
 * @param published   feed-visibility flag, separate from availability:
 *                    IN_STOCK + unpublished = sellable internally (an agent
 *                    that already knows the product), not advertised
 */
public record CommerceProduct(
    UUID id,
    String tenantId,
    String sku,
    String name,
    String description,
    String category,
    long pricePaise,
    String currency,
    ProductAvailability availability,
    String imageUrl,
    String productUrl,
    boolean published,
    Instant createdAt,
    Instant updatedAt
) {

    /** Display-only major-unit price (e.g. {@code 499.00}) - never for arithmetic. */
    public BigDecimal priceMajor() {
        return BigDecimal.valueOf(pricePaise).movePointLeft(2).setScale(2);
    }

    /** What a shopping agent can actually buy right now. */
    public boolean purchasable() {
        return published && availability == ProductAvailability.IN_STOCK;
    }

    public enum ProductAvailability {
        IN_STOCK,
        OUT_OF_STOCK,
        PREORDER;

        /** ACP-style lowercase wire value ({@code in_stock}/{@code out_of_stock}/{@code preorder}). */
        public String wireValue() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }
}
