package com.agenticai.core.knowledge.connector;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One configured live-sync source for a collection (migration V254).
 * {@code configJson} holds the provider-specific settings with secrets
 * AES-GCM-encrypted inside the JSON ({@code KnowledgeConnectorService}
 * encrypts on write, decrypts only to hand to the fetcher). The row itself
 * never exposes secrets through the API - {@link ConnectorResponse} redacts
 * them.
 */
@Entity
@Table(name = "knowledge_connector")
public class KnowledgeConnector {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "collection_id", nullable = false)
    private UUID collectionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ConnectorType type;

    @Column(nullable = false)
    private String name;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config_jsonb", nullable = false, columnDefinition = "jsonb")
    private String configJson = "{}";

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "sync_cursor", length = 1024)
    private String syncCursor;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public UUID getId() { return id; }
    public UUID getCollectionId() { return collectionId; }
    public void setCollectionId(UUID v) { collectionId = v; }
    public ConnectorType getType() { return type; }
    public void setType(ConnectorType v) { type = v; }
    public String getName() { return name; }
    public void setName(String v) { name = v; }
    public String getConfigJson() { return configJson; }
    public void setConfigJson(String v) { configJson = v; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { enabled = v; }
    public String getSyncCursor() { return syncCursor; }
    public void setSyncCursor(String v) { syncCursor = v; }
    public Instant getLastSyncedAt() { return lastSyncedAt; }
    public void setLastSyncedAt(Instant v) { lastSyncedAt = v; }
    public String getLastError() { return lastError; }
    public void setLastError(String v) { lastError = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { createdBy = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { updatedAt = v; }
}
