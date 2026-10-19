package com.agenticai.core.tables;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A tenant-scoped data table (migration V430): a name, an optional description and an ordered column schema
 * ({@code [{name, type}]}). Rows live in {@link DataTableRow}. Name uniqueness is per tenant and
 * case-insensitive (unique index on {@code lower(name)}).
 */
@Entity
@Table(name = "data_table")
public class DataTable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "columns", nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> columns = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DataTable() {
        // JPA
    }

    public DataTable(String name, String description, List<DataTableColumn> columns) {
        this.name = name;
        this.description = description;
        setColumnDefs(columns);
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setName(String name) {
        this.name = name;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    /** The typed column schema, in declaration order. */
    public List<DataTableColumn> columnDefs() {
        List<DataTableColumn> out = new ArrayList<>();
        if (columns != null) {
            for (Map<String, Object> m : columns) {
                out.add(DataTableColumn.fromMap(m));
            }
        }
        return out;
    }

    public void setColumnDefs(List<DataTableColumn> defs) {
        List<Map<String, Object>> next = new ArrayList<>();
        for (DataTableColumn c : defs) {
            next.add(c.toMap());
        }
        this.columns = next;
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }
}
