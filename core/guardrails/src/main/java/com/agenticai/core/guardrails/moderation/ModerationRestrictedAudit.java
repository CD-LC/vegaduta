package com.agenticai.core.guardrails.moderation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Phase P6 - the RESTRICTED audit record for a highest-stakes
 * ({@link ExternalSafetyClassifier}) match. Kept in a SEPARATE table from the
 * general {@code audit_log_entry} and written by a SEPARATE service ({@link
 * ModerationRestrictedAuditService}) precisely so a CSAM / credible-violence
 * signal NEVER lands in the general audit log the tenant-admin UI browses,
 * and never in application log files.
 *
 * <p><b>Deliberately minimal.</b> It stores a coarse category, a confidence,
 * an opaque vendor reference, and a one-way SHA-256 hash of the content - NOT
 * the raw content and NOT a description. This is intentional: what a real
 * deployment must retain (raw evidence, chain-of-custody, report identifiers)
 * for a mandated report is a decision for legal counsel and the chosen
 * vendor, not something this scaffold should presume. The hash lets a later,
 * counsel-approved process correlate/deduplicate without this table itself
 * becoming a store of the most sensitive possible content.
 *
 * <p><b>REQUIRES LEGAL/VENDOR SIGN-OFF</b> on retention, access control, and
 * reporting workflow before a real classifier populates this. See {@link
 * ExternalSafetyClassifier}.
 *
 * <p>Lives in the tenant schema (no {@code schema=} qualifier) - same
 * isolation boundary as the review queue. No JPA read path or controller ever
 * exposes it; it is write-mostly by design. A production deployment with
 * counsel may relocate this to a dedicated, even-more-restricted store.
 */
@Entity
@Table(name = "moderation_restricted_audit")
public class ModerationRestrictedAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", length = 255)
    private String tenantId;

    @Column(name = "agent_id")
    private UUID agentId;

    @Column(name = "session_id", length = 255)
    private String sessionId;

    /** Coarse category from the classifier (e.g. "csam", "credible_violence"). */
    @Column(name = "category", length = 64)
    private String category;

    @Column(name = "confidence", nullable = false)
    private int confidence;

    /** Vendor-side correlation id for follow-up/reporting - NOT the content. */
    @Column(name = "classifier_reference", length = 255)
    private String classifierReference;

    /** SHA-256 hex of the content - a correlation handle, never the content itself. */
    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ModerationRestrictedAudit() {
        // JPA
    }

    public ModerationRestrictedAudit(
        String tenantId,
        UUID agentId,
        String sessionId,
        String category,
        int confidence,
        String classifierReference,
        String contentHash
    ) {
        this.tenantId = tenantId;
        this.agentId = agentId;
        this.sessionId = sessionId;
        this.category = category;
        this.confidence = confidence;
        this.classifierReference = classifierReference;
        this.contentHash = contentHash;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getCategory() {
        return category;
    }

    public int getConfidence() {
        return confidence;
    }

    public String getClassifierReference() {
        return classifierReference;
    }

    public String getContentHash() {
        return contentHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
