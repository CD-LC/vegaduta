package com.agenticai.core.eventbus.webhook;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A tenant's outbound webhook subscription (migration V241) - one HTTPS
 * endpoint plus the set of event-type patterns it wants POSTed to it,
 * HMAC-signed with the row's encrypted secret. See {@link
 * WebhookDispatchListener} for how events get matched and {@link
 * WebhookDeliveryRelay} for the delivery/retry/dead-letter mechanics.
 */
@Entity
@Table(name = "webhook_subscription")
public class WebhookSubscription {

    /** After this many consecutive delivery failures the subscription is switched off automatically - a dead endpoint must not burn a relay slot every poll forever. */
    public static final int AUTO_DISABLE_FAILURE_THRESHOLD = 25;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 2048)
    private String url;

    @Column(name = "secret_encrypted", nullable = false, columnDefinition = "TEXT")
    private String secretEncrypted;

    @Column(name = "event_types", nullable = false, columnDefinition = "TEXT")
    private String eventTypes;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;

    @Column(name = "auto_disabled_reason", length = 300)
    private String autoDisabledReason;

    @Column(name = "created_by", length = 200)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WebhookSubscription() {
        // JPA
    }

    public WebhookSubscription(String name, String url, String secretEncrypted, Set<String> eventTypes, String createdBy) {
        this.name = name;
        this.url = url;
        this.secretEncrypted = secretEncrypted;
        setEventTypeSet(eventTypes);
        this.enabled = true;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /**
     * The subscription's event-type patterns as a set. {@code "*"} matches every
     * event type; a pattern ending in {@code ".*"} is a prefix match
     * ({@code "workflow.*"} catches {@code workflow.run.completed} AND {@code
     * workflow.node.completed}); anything else is an exact match.
     */
    public Set<String> eventTypeSet() {
        if (eventTypes == null || eventTypes.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(eventTypes.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public void setEventTypeSet(Set<String> types) {
        this.eventTypes = types.stream()
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.joining(","));
    }

    /** The {@link #eventTypeSet} match rules, evaluated against one concrete event type. */
    public boolean matches(String eventType) {
        if (!enabled || eventType == null) {
            return false;
        }
        for (String pattern : eventTypeSet()) {
            if ("*".equals(pattern)) {
                return true;
            }
            if (pattern.endsWith(".*")) {
                if (eventType.startsWith(pattern.substring(0, pattern.length() - 1))) {
                    return true;
                }
            } else if (pattern.equalsIgnoreCase(eventType)) {
                return true;
            }
        }
        return false;
    }

    public void update(String name, String url, Set<String> eventTypes, boolean enabled) {
        this.name = name;
        this.url = url;
        setEventTypeSet(eventTypes);
        this.enabled = enabled;
        this.updatedAt = Instant.now();
    }

    public void rotateSecret(String newSecretEncrypted) {
        this.secretEncrypted = newSecretEncrypted;
        this.updatedAt = Instant.now();
    }

    /** Relay bookkeeping - one successful delivery clears the failure streak. */
    public void recordDeliverySuccess() {
        if (consecutiveFailures != 0 || autoDisabledReason != null) {
            this.consecutiveFailures = 0;
            this.autoDisabledReason = null;
        }
    }

    /**
     * Relay bookkeeping - one failed DELIVERY (the row may still retry; the
     * streak counts subscription-level health). Crossing {@link
     * #AUTO_DISABLE_FAILURE_THRESHOLD} disables the subscription and records
     * why, so the owner sees "disabled: endpoint failing" rather than silent
     * dead-lettering.
     */
    public void recordDeliveryFailure(String reason) {
        this.consecutiveFailures++;
        if (consecutiveFailures >= AUTO_DISABLE_FAILURE_THRESHOLD && enabled) {
            this.enabled = false;
            this.autoDisabledReason = truncate(
                "Auto-disabled after " + consecutiveFailures + " consecutive delivery failures. Last: " + reason);
        }
    }

    private static String truncate(String s) {
        return s != null && s.length() > 300 ? s.substring(0, 300) : s;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getUrl() { return url; }
    public String getSecretEncrypted() { return secretEncrypted; }
    public String getEventTypes() { return eventTypes; }
    public boolean isEnabled() { return enabled; }
    public int getConsecutiveFailures() { return consecutiveFailures; }
    public String getAutoDisabledReason() { return autoDisabledReason; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
