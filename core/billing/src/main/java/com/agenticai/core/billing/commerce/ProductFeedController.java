package com.agenticai.core.billing.commerce;

import com.agenticai.core.billing.commerce.ProductCatalogService.CartItem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * PUBLIC (no-auth) ACP-shaped product feed - the surface a shopping agent
 * reads to discover what a tenant sells:
 * <ul>
 *   <li>{@code GET /api/commerce/feed/{tenantId}} - the tenant's published
 *       catalog, ACP-shaped ({@code seller} + {@code items[]}).</li>
 *   <li>{@code POST /api/commerce/feed/{tenantId}/checkout} - create a real
 *       checkout session from a cart of {@code {product_id, quantity}} lines.
 *       Prices come from the catalog rows server-side; the response carries
 *       the hosted payment {@code short_url} the buyer opens to pay.</li>
 * </ul>
 *
 * <p>Both paths are {@code permitAll()} in {@code SecurityConfig} (scoped to
 * {@code /api/commerce/feed/**}): the feed is meant to be crawlable by
 * external shopping agents that hold no member JWT. The checkout POST is the
 * one public writer - it creates Razorpay payment links, so it is bounded by
 * a per-tenant daily cap ({@code app.commerce.feed.max-checkouts-per-day},
 * default 100) enforced in {@link ProductCatalogService}; over the cap is a
 * 429. Prices can never be supplied by the caller - a cart line names a
 * product and a quantity, nothing more.
 */
@RestController
@Profile("security")
@RequestMapping("/api/commerce/feed")
public class ProductFeedController {

    public record CartLine(@NotNull UUID product_id, @Min(1) @Max(99) int quantity) {
    }

    public record FeedCheckoutRequest(
        @NotEmpty @Size(max = 50) List<@Valid CartLine> items,
        @Size(max = 32) String customer_phone,
        @Size(max = 200) String customer_name
    ) {
    }

    /** What the buyer's agent needs to hand the human a payable link - the session id + hosted URL. */
    public record FeedCheckoutResponse(
        UUID checkout_session_id,
        String status,
        long amount_paise,
        String currency,
        String description,
        String payment_url,
        java.time.Instant expires_at
    ) {
        static FeedCheckoutResponse from(CheckoutSession s) {
            return new FeedCheckoutResponse(
                s.id(), s.status().wireValue(), s.amountPaise(), s.currency(), s.description(),
                s.shortUrl(), s.expiresAt());
        }
    }

    private final ProductCatalogService service;

    public ProductFeedController(ProductCatalogService service) {
        this.service = service;
    }

    /** The published catalog as an ACP-shaped feed. Empty items[] for an unknown tenant - never 404-enumeration detail. */
    @GetMapping("/{tenantId}")
    public Object feed(@PathVariable String tenantId) {
        return service.feed(tenantId).toMap();
    }

    /**
     * Public cart checkout - server-side pricing. A line naming an unpublished,
     * out-of-stock, or foreign product 400s the whole request; the per-tenant
     * daily cap 429s.
     */
    @PostMapping("/{tenantId}/checkout")
    public ResponseEntity<FeedCheckoutResponse> checkout(
        @PathVariable String tenantId, @Valid @RequestBody FeedCheckoutRequest request
    ) {
        CheckoutSession session = service.checkout(
            tenantId,
            request.items().stream().map(l -> new CartItem(l.product_id(), l.quantity())).toList(),
            request.customer_phone(),
            request.customer_name()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(FeedCheckoutResponse.from(session));
    }
}
