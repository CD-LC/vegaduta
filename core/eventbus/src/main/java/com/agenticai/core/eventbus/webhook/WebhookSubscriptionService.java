package com.agenticai.core.eventbus.webhook;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.mcp.McpRemoteUrlGuard;
import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CRUD + secret lifecycle for {@link WebhookSubscription}. URL validation is delegated to {@link
 * McpRemoteUrlGuard} - the exact SSRF rules the platform already enforces for remote MCP servers
 * apply verbatim to a URL this platform is about to POST tenant data to.
 *
 * <p>Secret handling mirrors the platform's other stored secrets: the plaintext {@code whsec_...}
 * is generated here, encrypted via {@link EncryptionService} before it ever touches a row, and
 * returned to the caller exactly once (create/rotate responses) - list/detail responses only ever
 * expose a masked tail.
 */
@Service
public class WebhookSubscriptionService {

    /** Matches SkillService's per-tenant bound on self-service resources. */
    public static final int MAX_SUBSCRIPTIONS_PER_TENANT = 25;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> RESERVED_SYSTEM_PATTERNS = Set.of();

    private final WebhookSubscriptionRepository subscriptionRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final EncryptionService encryptionService;
    private final McpRemoteUrlGuard urlGuard;
    private final ObjectMapper objectMapper;

    public WebhookSubscriptionService(
            WebhookSubscriptionRepository subscriptionRepository,
            WebhookDeliveryRepository deliveryRepository,
            EncryptionService encryptionService,
            McpRemoteUrlGuard urlGuard,
            ObjectMapper objectMapper) {
        this.subscriptionRepository = subscriptionRepository;
        this.deliveryRepository = deliveryRepository;
        this.encryptionService = encryptionService;
        this.urlGuard = urlGuard;
        this.objectMapper = objectMapper;
    }

    public record SubscriptionView(
            UUID id,
            String name,
            String url,
            Set<String> eventTypes,
            boolean enabled,
            int consecutiveFailures,
            String autoDisabledReason,
            String createdBy,
            java.time.Instant createdAt,
            java.time.Instant updatedAt,
            /** Present ONLY on create/rotate responses - the plaintext secret, shown once. */
            String secret) {}

    @Transactional(readOnly = true)
    public List<SubscriptionView> list() {
        return subscriptionRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(s -> toView(s, null))
                .toList();
    }

    @Transactional(readOnly = true)
    public SubscriptionView get(UUID id) {
        return toView(require(id), null);
    }

    @Transactional
    public SubscriptionView create(String name, String url, Set<String> eventTypes) {
        String cleanName = requireName(name);
        Set<String> types = requireEventTypes(eventTypes);
        urlGuard.requireAllowed(url);
        if (subscriptionRepository.count() >= MAX_SUBSCRIPTIONS_PER_TENANT) {
            throw new IllegalArgumentException(
                    "This workspace already has the maximum of "
                            + MAX_SUBSCRIPTIONS_PER_TENANT
                            + " webhook subscriptions.");
        }
        String secret = generateSecret();
        WebhookSubscription saved =
                subscriptionRepository.save(
                        new WebhookSubscription(
                                cleanName,
                                url.trim(),
                                encryptionService.encrypt(secret),
                                types,
                                currentSubject()));
        return toView(saved, secret);
    }

    @Transactional
    public SubscriptionView update(
            UUID id, String name, String url, Set<String> eventTypes, Boolean enabled) {
        WebhookSubscription sub = require(id);
        if (url != null && !url.isBlank()) {
            urlGuard.requireAllowed(url);
        }
        sub.update(
                name != null && !name.isBlank() ? requireName(name) : sub.getName(),
                url != null && !url.isBlank() ? url.trim() : sub.getUrl(),
                eventTypes != null ? requireEventTypes(eventTypes) : sub.eventTypeSet(),
                enabled != null ? enabled : sub.isEnabled());
        return toView(subscriptionRepository.save(sub), null);
    }

    @Transactional
    public void delete(UUID id) {
        subscriptionRepository.delete(require(id));
    }

    /**
     * Rotates the signing secret. The old secret stops working the moment this returns - pending
     * deliveries sign with the new one.
     */
    @Transactional
    public SubscriptionView rotateSecret(UUID id) {
        WebhookSubscription sub = require(id);
        String secret = generateSecret();
        sub.rotateSecret(encryptionService.encrypt(secret));
        return toView(subscriptionRepository.save(sub), secret);
    }

    /**
     * Enqueues a synthetic {@code webhook.test} delivery for this subscription, so an owner can
     * verify signing/reachability without waiting for a real event. Goes through the normal relay
     * path (same signature headers).
     */
    @Transactional
    public UUID enqueueTestDelivery(UUID subscriptionId) {
        WebhookSubscription sub = require(subscriptionId);
        try {
            String payload =
                    objectMapper.writeValueAsString(
                            Map.of(
                                    "eventType", "webhook.test",
                                    "tenantId",
                                            TenantContext.current()
                                                    .map(TenantContext::getTenantId)
                                                    .orElse("unknown"),
                                    "timestamp", java.time.Instant.now().toString(),
                                    "payload",
                                            Map.of(
                                                    "subscriptionId",
                                                    subscriptionId.toString(),
                                                    "message",
                                                    "Test delivery from VegaDuta - verify the X-VegaDuta-Signature header with your endpoint secret.")));
            WebhookDelivery delivery =
                    deliveryRepository.save(
                            new WebhookDelivery(sub.getId(), "webhook.test", null, payload));
            return delivery.getId();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Could not build test payload", e);
        }
    }

    public static String generateSecret() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return "whsec_" + HexFormat.of().formatHex(bytes);
    }

    private WebhookSubscription require(UUID id) {
        return subscriptionRepository
                .findById(id)
                .orElseThrow(() -> new WebhookSubscriptionNotFoundException(id.toString()));
    }

    private SubscriptionView toView(WebhookSubscription s, String plaintextSecret) {
        return new SubscriptionView(
                s.getId(),
                s.getName(),
                s.getUrl(),
                s.eventTypeSet(),
                s.isEnabled(),
                s.getConsecutiveFailures(),
                s.getAutoDisabledReason(),
                s.getCreatedBy(),
                s.getCreatedAt(),
                s.getUpdatedAt(),
                plaintextSecret);
    }

    private static String requireName(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || n.length() > 120) {
            throw new IllegalArgumentException(
                    "A webhook subscription needs a name (1-120 characters).");
        }
        return n;
    }

    private static Set<String> requireEventTypes(Set<String> eventTypes) {
        if (eventTypes == null || eventTypes.isEmpty()) {
            throw new IllegalArgumentException(
                    "Subscribe to at least one event type (or \"*\" for all). See GET /api/webhooks/event-types.");
        }
        Set<String> cleaned = new LinkedHashSet<>();
        for (String t : eventTypes) {
            String c = t == null ? "" : t.trim();
            if (c.isEmpty() || c.length() > 200) {
                continue;
            }
            cleaned.add(c);
        }
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("No usable event types supplied.");
        }
        return cleaned;
    }

    private static String currentSubject() {
        return TenantContext.current().map(TenantContext::getSubject).orElse(null);
    }
}
