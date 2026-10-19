package com.agenticai.core.eventbus.webhook;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.http.ResponseEntity;
import com.agenticai.core.tenancy.ListPaging;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tenant-facing webhook subscription API ({@code /api/webhooks/**}) - the outbound-event half of
 * the developer-extensibility surface. AuthZ is a {@code SecurityConfig} URL matcher ({@code
 * PERM_MANAGE_INTEGRATIONS}), same posture as the MCP registry endpoints.
 *
 * <p>The signing secret is returned in plaintext ONLY in the create and rotate responses; every
 * other view omits it. Receivers verify {@code X-VegaDuta-Signature:
 * t=<unix>,v1=<hmac_sha256(secret, "<t>.<body>")>}.
 */
@RestController
@RequestMapping("/api/webhooks")
public class WebhookSubscriptionController {

    private final WebhookSubscriptionService service;
    private final WebhookDeliveryRepository deliveryRepository;

    public WebhookSubscriptionController(
            WebhookSubscriptionService service, WebhookDeliveryRepository deliveryRepository) {
        this.service = service;
        this.deliveryRepository = deliveryRepository;
    }

    public record CreateRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 2048) String url,
            @NotNull Set<String> eventTypes) {}

    public record UpdateRequest(
            @Size(max = 120) String name,
            @Size(max = 2048) String url,
            Set<String> eventTypes,
            Boolean enabled) {}

    public record DeliveryView(
            UUID id,
            String eventType,
            String runId,
            String status,
            int attemptCount,
            Integer responseCode,
            String lastError,
            java.time.Instant createdAt,
            java.time.Instant deliveredAt,
            Integer durationMs) {}

    @GetMapping
    public ResponseEntity<List<WebhookSubscriptionService.SubscriptionView>> list(@RequestParam(required = false) Integer limit, @RequestParam(required = false) Long offset) {
        return ListPaging.window(service.list(), limit, offset, 50, 200);
    }

    /** Event-type catalog for the subscription form's picker. */
    @GetMapping("/event-types")
    public ResponseEntity<List<WebhookEventCatalog.Entry>> eventTypes(@RequestParam(required = false) Integer limit, @RequestParam(required = false) Long offset) {
        return ListPaging.window(WebhookEventCatalog.ENTRIES, limit, offset, 100, 500);
    }

    @GetMapping("/{id}")
    public WebhookSubscriptionService.SubscriptionView get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    public WebhookSubscriptionService.SubscriptionView create(
            @Valid @RequestBody CreateRequest request) {
        return service.create(request.name(), request.url(), request.eventTypes());
    }

    @PatchMapping("/{id}")
    public WebhookSubscriptionService.SubscriptionView update(
            @PathVariable UUID id, @Valid @RequestBody UpdateRequest request) {
        return service.update(
                id, request.name(), request.url(), request.eventTypes(), request.enabled());
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable UUID id) {
        service.delete(id);
        return Map.of("deleted", true);
    }

    /** Rotates the signing secret; the new plaintext is returned once in {@code secret}. */
    @PostMapping("/{id}/rotate-secret")
    public WebhookSubscriptionService.SubscriptionView rotateSecret(@PathVariable UUID id) {
        return service.rotateSecret(id);
    }

    /** Enqueues a synthetic {@code webhook.test} delivery through the normal signed relay path. */
    @PostMapping("/{id}/test")
    public Map<String, Object> test(@PathVariable UUID id) {
        return Map.of("deliveryId", service.enqueueTestDelivery(id));
    }

    /** Delivery history for one subscription - newest first, capped at 100. */
    @GetMapping("/{id}/deliveries")
    public ResponseEntity<List<DeliveryView>> deliveries(@PathVariable UUID id, @RequestParam(required = false) Integer limit, @RequestParam(required = false) Long offset) {
        service.get(id); // 404 if the subscription isn't this tenant's / doesn't exist
        List<DeliveryView> all =
                deliveryRepository
                .findBySubscriptionIdOrderByCreatedAtDesc(id, PageRequest.of(0, 100))
                .stream()
                .map(
                        d ->
                                new DeliveryView(
                                        d.getId(),
                                        d.getEventType(),
                                        d.getRunId(),
                                        d.getStatus(),
                                        d.getAttemptCount(),
                                        d.getResponseCode(),
                                        d.getLastError(),
                                        d.getCreatedAt(),
                                        d.getDeliveredAt(),
                                        d.getDurationMs()))
                .toList();
        return ListPaging.window(all, limit, offset, 50, 100);
    }
}
