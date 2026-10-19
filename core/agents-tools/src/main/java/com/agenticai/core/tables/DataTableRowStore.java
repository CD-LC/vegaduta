package com.agenticai.core.tables;

import com.agenticai.core.tables.DataTableQueryBuilder.Where;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The SQL layer under {@link DataTableService}: row reads and bulk writes over {@code data_table_row} as native
 * statements through the tenant-aware {@link EntityManager} (so the tenant schema is applied exactly as for every
 * JPA repository). Filter and sort fragments come from {@link DataTableQueryBuilder}; this class only adds the
 * table scope and the statement shell. Deliberately logic-free - every rule lives in the pure helpers and the
 * service, which is why this is the one class here that needs a real Postgres to exercise.
 */
@Component
public class DataTableRowStore {

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<DataTableRow> select(UUID tableId, Where where, String orderBy, int limit, int offset) {
        String sql = "SELECT r.* FROM data_table_row r WHERE " + scope(where) + orderBy + " LIMIT :lim OFFSET :off";
        Query q = entityManager.createNativeQuery(sql, DataTableRow.class);
        bind(q, tableId, where);
        q.setParameter("lim", limit);
        q.setParameter("off", offset);
        return (List<DataTableRow>) q.getResultList();
    }

    @Transactional(readOnly = true)
    public long count(UUID tableId, Where where) {
        Query q = entityManager.createNativeQuery("SELECT count(*) FROM data_table_row r WHERE " + scope(where));
        bind(q, tableId, where);
        return ((Number) q.getSingleResult()).longValue();
    }

    /** Row counts for every table that has rows (absent = zero). */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Map<UUID, Long> countsByTable() {
        List<Object[]> rows = entityManager
            .createNativeQuery("SELECT CAST(table_id AS text), count(*) FROM data_table_row GROUP BY table_id")
            .getResultList();
        Map<UUID, Long> out = new HashMap<>();
        for (Object[] r : rows) {
            out.put(UUID.fromString(String.valueOf(r[0])), ((Number) r[1]).longValue());
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Optional<DataTableRow> find(UUID tableId, UUID rowId) {
        DataTableRow row = entityManager.find(DataTableRow.class, rowId);
        return row != null && tableId.equals(row.getTableId()) ? Optional.of(row) : Optional.empty();
    }

    @Transactional
    public List<DataTableRow> insertAll(UUID tableId, List<Map<String, Object>> rows) {
        List<DataTableRow> saved = new ArrayList<>(rows.size());
        int n = 0;
        for (Map<String, Object> data : rows) {
            DataTableRow row = new DataTableRow(tableId, data);
            entityManager.persist(row);
            saved.add(row);
            if (++n % 200 == 0) {
                entityManager.flush();
            }
        }
        entityManager.flush();
        return saved;
    }

    @Transactional
    public DataTableRow save(DataTableRow row) {
        return entityManager.merge(row);
    }

    /**
     * Merges {@code patchJson} into the data of every matching row and removes every key in {@code clearedKeys}
     * first (a cleared cell is an absent key, never a stored JSON null); returns how many rows changed.
     */
    @Transactional
    public int update(UUID tableId, Where where, String patchJson, List<String> clearedKeys) {
        StringBuilder data = new StringBuilder("r.data");
        for (int i = 0; i < clearedKeys.size(); i++) {
            data.append(" - CAST(:clr").append(i).append(" AS text)");
        }
        Query q = entityManager.createNativeQuery(
            "UPDATE data_table_row AS r SET data = (" + data + ") || CAST(:patch AS jsonb), updated_at = :now WHERE " + scope(where));
        bind(q, tableId, where);
        for (int i = 0; i < clearedKeys.size(); i++) {
            q.setParameter("clr" + i, clearedKeys.get(i));
        }
        q.setParameter("patch", patchJson);
        q.setParameter("now", Timestamp.from(Instant.now()));
        return q.executeUpdate();
    }

    @Transactional
    public int delete(UUID tableId, Where where) {
        Query q = entityManager.createNativeQuery("DELETE FROM data_table_row AS r WHERE " + scope(where));
        bind(q, tableId, where);
        return q.executeUpdate();
    }

    /** Rows of the table whose {@code column} holds a value (used to refuse a type change that would orphan data). */
    @Transactional(readOnly = true)
    public long countWithValue(UUID tableId, String column) {
        Query q = entityManager.createNativeQuery(
            "SELECT count(*) FROM data_table_row r WHERE r.table_id = CAST(:tableId AS uuid) "
                + "AND (r.data ->> CAST(:col AS text)) IS NOT NULL");
        q.setParameter("tableId", tableId.toString());
        q.setParameter("col", column);
        return ((Number) q.getSingleResult()).longValue();
    }

    @Transactional
    public int renameColumn(UUID tableId, String from, String to) {
        Query q = entityManager.createNativeQuery(
            "UPDATE data_table_row AS r SET data = (r.data - CAST(:from AS text)) "
                + "|| jsonb_build_object(CAST(:to AS text), r.data -> CAST(:from AS text)) "
                + "WHERE r.table_id = CAST(:tableId AS uuid) AND jsonb_exists(r.data, CAST(:from AS text))");
        q.setParameter("tableId", tableId.toString());
        q.setParameter("from", from);
        q.setParameter("to", to);
        return q.executeUpdate();
    }

    @Transactional
    public int dropColumn(UUID tableId, String column) {
        Query q = entityManager.createNativeQuery(
            "UPDATE data_table_row AS r SET data = r.data - CAST(:col AS text) "
                + "WHERE r.table_id = CAST(:tableId AS uuid) AND jsonb_exists(r.data, CAST(:col AS text))");
        q.setParameter("tableId", tableId.toString());
        q.setParameter("col", column);
        return q.executeUpdate();
    }

    // ------------------------------------------------------------------

    private static String scope(Where where) {
        return "r.table_id = CAST(:tableId AS uuid)" + (where.isEmpty() ? "" : " AND (" + where.sql() + ")");
    }

    private static void bind(Query q, UUID tableId, Where where) {
        q.setParameter("tableId", tableId.toString());
        for (Map.Entry<String, Object> e : where.params().entrySet()) {
            q.setParameter(e.getKey(), e.getValue());
        }
    }
}
