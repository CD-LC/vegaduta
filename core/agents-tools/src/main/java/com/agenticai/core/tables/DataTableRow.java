package com.agenticai.core.tables;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One row of a {@link DataTable} (migration V431): a JSON object keyed by column name. */
@Entity
@Table(name = "data_table_row")
public class DataTableRow {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "table_id", nullable = false, updatable = false)
    private UUID tableId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> data = new LinkedHashMap<>();

    // TRUSTED (readable) or QUARANTINED (written by an agent on a turn that handled untrusted content, hidden
    // from every read until a person approves it); see V678.
    @Column(name = "trust", nullable = false, length = 16)
    private String trust = "TRUSTED";

    @Column(name = "source_taint", columnDefinition = "TEXT")
    private String sourceTaint;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DataTableRow() {
        // JPA
    }

    public DataTableRow(UUID tableId, Map<String, Object> data) {
        this(tableId, data, RowTrust.TRUSTED);
    }

    public DataTableRow(UUID tableId, Map<String, Object> data, RowTrust rowTrust) {
        RowTrust t = rowTrust == null ? RowTrust.TRUSTED : rowTrust;
        this.trust = t.quarantined() ? "QUARANTINED" : "TRUSTED";
        this.sourceTaint = t.sourceTaintJson();
        this.tableId = tableId;
        this.data = data == null ? new LinkedHashMap<>() : new LinkedHashMap<>(data);
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public UUID getId() { return id; }
    public UUID getTableId() { return tableId; }
    public Map<String, Object> getData() { return data; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    /** True while the row is held for review; anything but TRUSTED counts as held (fail closed). */
    public boolean isQuarantined() { return !"TRUSTED".equals(trust); }
    public String getSourceTaint() { return sourceTaint; }

    public void setData(Map<String, Object> data) {
        this.data = data == null ? new LinkedHashMap<>() : new LinkedHashMap<>(data);
        this.updatedAt = Instant.now();
    }
}
