package com.agenticai.core.payments.commerce;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One append-only lifecycle event on a {@link CommerceMandate} (migration
 * V403) - created/authorized/captured/revoked/refunded transitions with actor
 * and detail. The dispute-evidence trail AP2 expects: who moved this mandate
 * where, when, and why.
 */
@Entity
@Table(name = "commerce_mandate_event")
public class CommerceMandateEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "mandate_id", nullable = false)
    private UUID mandateId;

    @Column(name = "event_type", nullable = false, length = 32)
    private String eventType;

    @Column(name = "actor", nullable = false, length = 128)
    private String actor;

    @Column(name = "detail", columnDefinition = "TEXT")
    private String detail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected CommerceMandateEvent() {
    }

    public static CommerceMandateEvent of(UUID mandateId, String eventType, String actor, String detail) {
        CommerceMandateEvent e = new CommerceMandateEvent();
        e.mandateId = mandateId;
        e.eventType = eventType;
        e.actor = actor;
        e.detail = detail;
        return e;
    }

    public Long getId() {
        return id;
    }

    public UUID getMandateId() {
        return mandateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getActor() {
        return actor;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
