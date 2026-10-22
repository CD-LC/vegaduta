package com.agenticai.core.billing.commerce;

import com.agenticai.core.billing.commerce.CommerceProduct.ProductAvailability;
import com.agenticai.core.billing.commerce.ProductCatalogService.ProductUpsert;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Tenant member surface for the product catalog ({@code
 * /api/tenant/commerce/products}) - the rows the public feed ({@link
 * ProductFeedController}) publishes when {@code published} is set. Falls
 * through to {@code SecurityConfig}'s {@code anyRequest().authenticated()};
 * tenant scoping comes exclusively from {@link TenantContext}, never a
 * request field - same posture as {@code CheckoutSessionController}.
 */
@RestController
@Profile("security")
@RequestMapping("/api/tenant/commerce/products")
@ConditionalOnEditionFeature(EditionFeature.BILLING_PAYMENTS)
public class ProductCatalogController {

    /** Wire shape - paise plus a display-only major amount, same convention as CheckoutSessionResponse. */
    public record ProductResponse(
        UUID id,
        String sku,
        String name,
        String description,
        String category,
        long pricePaise,
        BigDecimal price,
        String currency,
        String availability,
        String imageUrl,
        String productUrl,
        boolean published,
        Instant createdAt,
        Instant updatedAt
    ) {
        static ProductResponse from(CommerceProduct p) {
            return new ProductResponse(
                p.id(), p.sku(), p.name(), p.description(), p.category(), p.pricePaise(), p.priceMajor(),
                p.currency(), p.availability().wireValue(), p.imageUrl(), p.productUrl(), p.published(),
                p.createdAt(), p.updatedAt()
            );
        }
    }

    public record ProductRequest(
        @Size(max = 128) String sku,
        @NotBlank @Size(max = 200) String name,
        @Size(max = 4000) String description,
        @Size(max = 64) String category,
        @Min(0) long pricePaise,
        @Size(max = 3) String currency,
        ProductAvailability availability,
        @Size(max = 1024) String imageUrl,
        @Size(max = 1024) String productUrl,
        boolean published
    ) {
        ProductUpsert toUpsert() {
            return new ProductUpsert(
                sku, name, description, category, pricePaise, currency, availability, imageUrl, productUrl, published);
        }
    }

    private final ProductCatalogService service;

    public ProductCatalogController(ProductCatalogService service) {
        this.service = service;
    }

    @PostMapping
    public ProductResponse create(@Valid @RequestBody ProductRequest request) {
        return ProductResponse.from(service.create(currentTenantId(), request.toUpsert()));
    }

    @PutMapping("/{id}")
    public ProductResponse update(@PathVariable UUID id, @Valid @RequestBody ProductRequest request) {
        return service.update(currentTenantId(), id, request.toUpsert())
            .map(ProductResponse::from)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such product for this tenant"));
    }

    @DeleteMapping("/{id}")
    public org.springframework.http.ResponseEntity<Void> delete(@PathVariable UUID id) {
        if (!service.delete(currentTenantId(), id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such product for this tenant");
        }
        return org.springframework.http.ResponseEntity.noContent().build();
    }

    @GetMapping
    public List<ProductResponse> list(@RequestParam(name = "limit", defaultValue = "200") int limit) {
        return service.listForTenant(currentTenantId(), limit).stream().map(ProductResponse::from).toList();
    }

    private static String currentTenantId() {
        return TenantContext.current().map(TenantContext::getTenantId)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.UNAUTHORIZED, "No tenant context - authentication required"));
    }
}
