package com.agenticai.core.omnichannel.email;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A recipient that must never be emailed again (V335). {@code email} is stored lower-cased. */
@Entity
@Table(name = "email_suppression")
public class EmailSuppression {

    public static final String REASON_UNSUBSCRIBE = "UNSUBSCRIBE";
    public static final String REASON_BOUNCE = "BOUNCE";
    public static final String REASON_MANUAL = "MANUAL";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 320, unique = true)
    private String email;

    @Column(nullable = false, length = 32)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected EmailSuppression() {
        // JPA
    }

    public EmailSuppression(String email, String reason) {
        this.email = email == null ? null : email.trim().toLowerCase(java.util.Locale.ROOT);
        this.reason = reason;
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
