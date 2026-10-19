package com.agenticai.core.documentgen.entity;

import com.agenticai.core.documentgen.model.DocumentGenerationStatus;
import com.agenticai.core.documentgen.model.DocumentType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One requested document-generation job (migration V177, {@code
 * db/migration/tenant}) - async because rendering (especially PDF/DOCX with
 * real layout) can take real wall-clock time, the same "enqueue, poll status,
 * download once COMPLETE" shape {@code VideoGenerationJob} already
 * established.
 *
 * <p><b>Tenant-schema entity with an explicit {@code tenant_id} column</b> -
 * same shape as {@code GuardrailRule}/{@code ModerationReviewItem}: it lives
 * in the calling tenant's own Postgres schema (resolved automatically by the
 * platform's Hibernate multi-tenancy resolver), so {@code tenant_id} is
 * redundant with the schema for isolation purposes, but is kept as an
 * explicit column so {@code DocumentGenerationJobRepository}'s {@code
 * findByTenantId*} finders can filter/paginate without relying on
 * schema-switching alone, and for cross-schema operator tooling/audit
 * correlation - exactly the rationale those classes' own javadoc documents.
 *
 * <p>{@link #contentJson} is NOT part of this module's originally scaffolded
 * field list, but is required for correctness: {@code
 * DocumentGenerationService#requestGeneration} saves this row and publishes
 * {@code DocumentGenerationRequestedEvent} inside one {@code @Transactional}
 * method, and the {@code @TransactionalEventListener(phase = AFTER_COMMIT)}
 * listener that actually renders the file runs afterward, in a different
 * (async) call - it has no other way to recover what to render. The event
 * itself also carries the request payload in-memory (same-JVM, same-process
 * delivery only - see that record's javadoc) as the primary path; this
 * column is the durable fallback so a REQUESTED row surviving a restart is
 * still ultimately recoverable, and so the original request is inspectable
 * for support/debugging without depending on the in-memory event having
 * fired. Stored as the serialized {@code DocumentContent} (Jackson JSON),
 * not the whole {@code DocumentGenerationRequest} - {@link #type} and {@link
 * #filename} already have their own columns.
 */
@Entity
@Table(name = "document_generation_job")
public class DocumentGenerationJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", length = 255)
    private String tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    private DocumentType type;

    @Column(name = "filename", nullable = false, length = 255)
    private String filename;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private DocumentGenerationStatus status;

    @Column(name = "storage_key", length = 512)
    private String storageKey;

    // Deliberately NOT @Lob. On PostgreSQL Hibernate maps `@Lob String` to
    // `oid` - a large-object *reference*, not inline text - while
    // V177__create_documentgen_jobs.sql declares this column as TEXT. That
    // mismatch is not caught by compilation or by any test that does not boot a
    // real Postgres: it surfaces as
    //   "Schema-validation: wrong column type encountered in column
    //    [content_json] ... found [text], but expecting [oid]"
    // which fails the EntityManagerFactory and takes the ENTIRE application
    // context down at startup - core never becomes healthy. It did exactly that
    // on staging (2026-08-05). TEXT is the correct storage for this payload, so
    // the annotation is what changes, not the migration; `errorMessage` below
    // already uses this same pattern.
    @Column(name = "content_json", nullable = false, columnDefinition = "TEXT")
    private String contentJson;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "requested_by", length = 255)
    private String requestedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected DocumentGenerationJob() {
        // JPA
    }

    public DocumentGenerationJob(
        String tenantId, DocumentType type, String filename, String contentJson, String requestedBy
    ) {
        this.tenantId = tenantId;
        this.type = type;
        this.filename = filename;
        this.contentJson = contentJson;
        this.requestedBy = requestedBy;
        this.status = DocumentGenerationStatus.REQUESTED;
        this.createdAt = Instant.now();
    }

    public void markProcessing() {
        this.status = DocumentGenerationStatus.PROCESSING;
    }

    public void markComplete(String storageKey) {
        this.status = DocumentGenerationStatus.COMPLETE;
        this.storageKey = storageKey;
        this.completedAt = Instant.now();
    }

    public void markFailed(String errorMessage) {
        this.status = DocumentGenerationStatus.FAILED;
        this.errorMessage = errorMessage;
        this.completedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public DocumentType getType() {
        return type;
    }

    public String getFilename() {
        return filename;
    }

    public DocumentGenerationStatus getStatus() {
        return status;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getContentJson() {
        return contentJson;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
