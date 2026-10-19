package com.agenticai.core.brief;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A rung proposal or change with its evidence ({@code brief_trust_event}). Written by the trust
 * engine (a later layer). kind: PROPOSED | PROMOTED | DEMOTED | REJECTED.
 */
@Entity
@Table(name = "brief_trust_event")
public class BriefTrustEventEntity {

    @Id
    private UUID id;

    @Column(name = "brief_id", nullable = false)
    private UUID briefId;

    @Column(name = "from_rung", length = 16)
    private String fromRung;

    @Column(name = "to_rung", length = 16)
    private String toRung;

    @Column(nullable = false, length = 16)
    private String kind;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_json", columnDefinition = "jsonb")
    private String evidenceJson;

    private Integer version;

    @Column(length = 255)
    private String actor;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected BriefTrustEventEntity() {
        // JPA
    }

    public BriefTrustEventEntity(
        UUID briefId, String fromRung, String toRung, String kind, String evidenceJson, Integer version, String actor
    ) {
        this.id = UUID.randomUUID();
        this.briefId = briefId;
        this.fromRung = fromRung;
        this.toRung = toRung;
        this.kind = kind;
        this.evidenceJson = evidenceJson;
        this.version = version;
        this.actor = actor;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getBriefId() {
        return briefId;
    }

    public String getFromRung() {
        return fromRung;
    }

    public String getToRung() {
        return toRung;
    }

    public String getKind() {
        return kind;
    }

    public String getEvidenceJson() {
        return evidenceJson;
    }

    public Integer getVersion() {
        return version;
    }

    public String getActor() {
        return actor;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
