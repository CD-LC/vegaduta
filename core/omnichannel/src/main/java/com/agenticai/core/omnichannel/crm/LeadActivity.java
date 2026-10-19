package com.agenticai.core.omnichannel.crm;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Append-only timeline entry on a {@link Lead} (migration V337). */
@Entity
@Table(name = "lead_activity")
public class LeadActivity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "lead_id", nullable = false)
    private UUID leadId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    private LeadActivityType type;

    @Column(name = "body", columnDefinition = "text")
    private String body;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "meta", columnDefinition = "jsonb")
    private Map<String, Object> meta;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LeadActivity() {
        // JPA
    }

    public LeadActivity(UUID leadId, LeadActivityType type, String body, Map<String, Object> meta) {
        this.leadId = leadId;
        this.type = type;
        this.body = body;
        this.meta = meta;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getLeadId() { return leadId; }
    public LeadActivityType getType() { return type; }
    public String getBody() { return body; }
    public Map<String, Object> getMeta() { return meta; }
    public Instant getCreatedAt() { return createdAt; }
}
