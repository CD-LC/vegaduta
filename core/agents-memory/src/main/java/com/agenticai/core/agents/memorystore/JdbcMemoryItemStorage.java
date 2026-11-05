package com.agenticai.core.agents.memorystore;

import com.agenticai.core.agents.memory.spi.MemoryKind;
import com.agenticai.core.agents.memory.spi.MemoryTrustLevel;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.TenantSchemaResolver;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Raw-JDBC implementation over the tenant tables of V681 (the vector type has no Hibernate mapping;
 * same reasoning as {@code agent_memory}). Table names are schema-qualified from the tenant of the
 * calling thread, exactly as the other raw-JDBC memory services do, and a thread without a tenant
 * fails closed instead of falling back to the shared schema.
 *
 * <p>Statements that must be atomic (a row change plus its event, an insert plus its lineage) run
 * in one {@link TransactionTemplate} on the JDBC data source. The INSERT here has no trust or
 * status parameter: it writes {@code QUARANTINED} and {@code PENDING} literally, so no caller can
 * create a promoted row through it.
 */
public class JdbcMemoryItemStorage implements MemoryItemStorage {

    static final ObjectMapper MAPPER = new ObjectMapper();
    static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};

    static final String COLUMNS =
            "id, agent_id, kind, content, trust, status, writer, source_type, source_ref, turn_id, source_taint,"
                    + " model_generated, generation, subject_keys, version, supersedes_id, created_at, expires_at,"
                    + " approved_by, approved_at, pinned";

    private final ObjectProvider<JdbcTemplate> jdbcProvider;
    private final MemoryTombstoneGuard tombstones;

    public JdbcMemoryItemStorage(ObjectProvider<JdbcTemplate> jdbcProvider) {
        this(jdbcProvider, MemoryTombstoneGuard.inactive());
    }

    /**
     * With the tombstone guard, so recall leaves out items the consolidation forgot (a no-op when
     * the guard is inactive).
     */
    public JdbcMemoryItemStorage(
            ObjectProvider<JdbcTemplate> jdbcProvider, MemoryTombstoneGuard tombstones) {
        this.jdbcProvider = jdbcProvider;
        this.tombstones = tombstones == null ? MemoryTombstoneGuard.inactive() : tombstones;
    }

    // ------------------------------------------------------------------ plumbing

    private JdbcTemplate jdbc() {
        JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        if (jdbc == null) {
            throw new IllegalStateException("no JDBC access for the memory store");
        }
        return jdbc;
    }

    /**
     * The tenant schema of this thread, quoted; throws without a tenant (never the shared schema).
     */
    static String schema() {
        String tenant = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        if (tenant == null || tenant.isBlank()) {
            throw new IllegalStateException("no tenant bound to this thread");
        }
        return "\"" + TenantSchemaResolver.currentSchemaName().replace("\"", "\"\"") + "\"";
    }

    private static String table(String name) {
        return schema() + "." + name;
    }

    private void inTransaction(JdbcTemplate jdbc, Runnable work) {
        new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()))
                .executeWithoutResult(status -> work.run());
    }

    private static void insertEvent(JdbcTemplate jdbc, MemoryEvent e) {
        jdbc.update(
                // codeql[java/sql-injection]
                "INSERT INTO "
                        + table("memory_event")
                        + " (id, item_id, agent_id, event, actor_type, actor_ref, from_status, to_status, reason, at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                e.id(),
                e.itemId(),
                e.agentId(),
                e.event().name(),
                e.actor().type().name(),
                e.actor().ref(),
                e.fromStatus() == null ? null : e.fromStatus().name(),
                e.toStatus() == null ? null : e.toStatus().name(),
                e.reason(),
                MemoryItemRows.ts(e.at()));
    }

    // ------------------------------------------------------------------ writes

    @Override
    public void insertPending(
            MemoryItem item,
            float[] embedding,
            List<MemoryItemSource> lineage,
            MemoryEvent created) {
        if (item.status() != MemoryItemStatus.PENDING
                || item.trust() != MemoryTrustLevel.QUARANTINED) {
            throw new IllegalArgumentException("only PENDING + QUARANTINED rows can be inserted");
        }
        JdbcTemplate jdbc = jdbc();
        String vector = MemoryItemRows.vector(embedding);
        String items = table("memory_item");
        String sources = table("memory_item_source");
        inTransaction(
                jdbc,
                () -> {
                    jdbc.update(
                            // codeql[java/sql-injection]
                            "INSERT INTO "
                                    + items
                                    + " (id, agent_id, kind, content, embedding, trust, status, writer, source_type,"
                                    + " source_ref, turn_id, source_taint, model_generated, generation, subject_keys,"
                                    + " version, supersedes_id, created_at, expires_at)"
                                    + " VALUES (?, ?, ?, ?, ?::public.vector, 'QUARANTINED', 'PENDING', ?, ?, ?, ?, ?,"
                                    + " ?, ?, ?, ?, ?, ?, ?)",
                            (PreparedStatement ps) -> {
                                int i = 1;
                                ps.setObject(i++, item.id());
                                ps.setObject(i++, item.agentId());
                                ps.setString(i++, item.kind().name());
                                ps.setString(i++, item.content());
                                ps.setString(i++, vector);
                                ps.setString(i++, item.writer().name());
                                ps.setString(i++, item.sourceType());
                                ps.setString(i++, item.sourceRef());
                                ps.setString(i++, item.turnId());
                                ps.setString(i++, MemoryItemRows.taintJson(item.sourceTaint()));
                                ps.setBoolean(i++, item.modelGenerated());
                                ps.setInt(i++, item.generation());
                                ps.setArray(
                                        i++,
                                        ps.getConnection()
                                                .createArrayOf(
                                                        "text", item.subjectKeys().toArray()));
                                ps.setInt(i++, item.version());
                                ps.setObject(i++, item.supersedesId());
                                ps.setTimestamp(i++, MemoryItemRows.ts(item.createdAt()));
                                ps.setTimestamp(i, MemoryItemRows.ts(item.expiresAt()));
                            });
                    for (MemoryItemSource s : lineage) {
                        jdbc.update(
                                // codeql[java/sql-injection]
                                "INSERT INTO "
                                        + sources
                                        + " (item_id, source_table, source_key, source_trust) VALUES (?, ?, ?, ?)"
                                        + " ON CONFLICT DO NOTHING",
                                item.id(),
                                s.sourceTable(),
                                s.sourceKey(),
                                s.sourceTrust().name());
                    }
                    insertEvent(jdbc, created);
                });
    }

    @Override
    public boolean activate(
            UUID agentId,
            UUID itemId,
            String approvedBy,
            Instant at,
            MemoryEvent approved,
            UUID supersedes,
            MemoryEvent predecessorEvent) {
        if (approvedBy == null || approvedBy.isBlank()) {
            throw new IllegalArgumentException("approvedBy is required");
        }
        JdbcTemplate jdbc = jdbc();
        String items = table("memory_item");
        boolean[] done = new boolean[1];
        inTransaction(
                jdbc,
                () -> {
                    int rows =
                            jdbc.update(
                                    // codeql[java/sql-injection]
                                    "UPDATE "
                                            + items
                                            + " SET trust = 'TRUSTED', status = 'ACTIVE', approved_by = ?, approved_at = ?"
                                            + " WHERE id = ? AND agent_id = ? AND status = 'PENDING' AND trust = 'QUARANTINED'",
                                    approvedBy,
                                    MemoryItemRows.ts(at),
                                    itemId,
                                    agentId);
                    if (rows != 1) {
                        return;
                    }
                    done[0] = true;
                    insertEvent(jdbc, approved);
                    if (supersedes != null
                            && jdbc.update(
                                            // codeql[java/sql-injection]
                                            "UPDATE "
                                                    + items
                                                    + " SET status = 'SUPERSEDED' WHERE id = ? AND agent_id = ?"
                                                    + " AND status = 'ACTIVE'",
                                            supersedes,
                                            agentId)
                                    == 1
                            && predecessorEvent != null) {
                        insertEvent(jdbc, predecessorEvent);
                    }
                });
        return done[0];
    }

    @Override
    public boolean transition(
            UUID agentId,
            UUID itemId,
            Set<MemoryItemStatus> from,
            MemoryItemStatus to,
            MemoryEvent event) {
        if (to == MemoryItemStatus.ACTIVE) {
            throw new IllegalArgumentException("ACTIVE is reachable only through activate()");
        }
        JdbcTemplate jdbc = jdbc();
        String items = table("memory_item");
        String inList =
                from.stream().map(s -> "'" + s.name() + "'").collect(Collectors.joining(","));
        boolean[] done = new boolean[1];
        inTransaction(
                jdbc,
                () -> {
                    int rows =
                            jdbc.update(
                                    // codeql[java/sql-injection]
                                    "UPDATE "
                                            + items
                                            + " SET status = ? WHERE id = ? AND agent_id = ? AND status IN ("
                                            + inList
                                            + ")",
                                    to.name(),
                                    itemId,
                                    agentId);
                    if (rows == 1) {
                        done[0] = true;
                        insertEvent(jdbc, event);
                    }
                });
        return done[0];
    }

    @Override
    public boolean setPinned(UUID agentId, UUID itemId, boolean pinned, MemoryEvent event) {
        JdbcTemplate jdbc = jdbc();
        String items = table("memory_item");
        boolean[] done = new boolean[1];
        inTransaction(
                jdbc,
                () -> {
                    int rows =
                            jdbc.update(
                                    // codeql[java/sql-injection]
                                    "UPDATE "
                                            + items
                                            + " SET pinned = ? WHERE id = ? AND agent_id = ? AND pinned <> ?",
                                    pinned,
                                    itemId,
                                    agentId,
                                    pinned);
                    if (rows == 1) {
                        done[0] = true;
                        insertEvent(jdbc, event);
                    }
                });
        return done[0];
    }

    @Override
    public boolean delete(UUID agentId, UUID itemId, MemoryEvent erased) {
        JdbcTemplate jdbc = jdbc();
        String items = table("memory_item");
        boolean[] done = new boolean[1];
        inTransaction(
                jdbc,
                () -> {
                    // codeql[java/sql-injection]
                    if (jdbc.update(
                                    // codeql[java/sql-injection]
                                    "DELETE FROM " + items + " WHERE id = ? AND agent_id = ?",
                                    itemId,
                                    agentId)
                            == 1) {
                        done[0] = true;
                        insertEvent(jdbc, erased);
                    }
                });
        return done[0];
    }

    @Override
    public List<UUID> eraseTree(UUID agentId, UUID itemId, MemoryActor actor, Instant at) {
        JdbcTemplate jdbc = jdbc();
        String items = table("memory_item");
        String sources = table("memory_item_source");
        String events = table("memory_event");
        List<UUID> erased = new ArrayList<>();
        inTransaction(
                jdbc,
                () -> {
                    Integer found =
                            jdbc.queryForObject(
                                    // codeql[java/sql-injection]
                                    "SELECT count(*) FROM "
                                            + items
                                            + " WHERE id = ? AND agent_id = ?",
                                    Integer.class,
                                    itemId,
                                    agentId);
                    if (found == null || found == 0) {
                        return;
                    }
                    // Descendants through lineage: rows that name the item (or a descendant) as
                    // their
                    // MEMORY_ITEM source. Restricted to this agent's rows below, so a lineage row
                    // pointing across agents can never reach another agent's data.
                    List<UUID> descendants =
                            jdbc.query(
                                    // codeql[java/sql-injection]
                                    "WITH RECURSIVE d(item_id) AS ("
                                            + " SELECT s.item_id FROM "
                                            + sources
                                            + " s WHERE s.source_table = 'memory_item' AND s.source_key = ?"
                                            + " UNION SELECT s.item_id FROM "
                                            + sources
                                            + " s JOIN d ON s.source_table = 'memory_item'"
                                            + " AND s.source_key = d.item_id::text"
                                            + ") SELECT item_id FROM d",
                                    (rs, n) -> rs.getObject(1, UUID.class),
                                    itemId.toString());
                    List<UUID> ids = new ArrayList<>();
                    ids.add(itemId);
                    ids.addAll(descendants);
                    String inList =
                            ids.stream().map(id -> "'" + id + "'").collect(Collectors.joining(","));
                    // (id, status) of the rows that will go, restricted to this agent.
                    Map<UUID, String> targets = new LinkedHashMap<>();
                    jdbc.query(
                            // codeql[java/sql-injection]
                            "SELECT id, status FROM "
                                    + items
                                    + " WHERE agent_id = ? AND id IN ("
                                    + inList
                                    + ")",
                            (org.springframework.jdbc.core.RowCallbackHandler)
                                    rs ->
                                            targets.put(
                                                    rs.getObject("id", UUID.class),
                                                    rs.getString("status")),
                            agentId);
                    if (targets.isEmpty()) {
                        return;
                    }
                    String targetList =
                            targets.keySet().stream()
                                    .map(id -> "'" + id + "'")
                                    .collect(Collectors.joining(","));
                    // History goes with the row; one ERASED tombstone per id is appended below.
                    // codeql[java/sql-injection]
                    jdbc.update("DELETE FROM " + events + " WHERE item_id IN (" + targetList + ")");
                    // codeql[java/sql-injection]
                    jdbc.update("DELETE FROM " + items + " WHERE id IN (" + targetList + ")");
                    for (Map.Entry<UUID, String> t : targets.entrySet()) {
                        insertEvent(
                                jdbc,
                                MemoryEvent.of(
                                        t.getKey(),
                                        agentId,
                                        MemoryEvent.Type.ERASED,
                                        actor,
                                        MemoryItemStatus.parse(t.getValue()),
                                        null,
                                        t.getKey().equals(itemId) ? "ERASED" : "ERASED_DERIVED",
                                        at));
                    }
                    erased.addAll(targets.keySet());
                });
        return erased;
    }

    @Override
    public void appendEvent(MemoryEvent event) {
        JdbcTemplate jdbc = jdbc();
        insertEvent(jdbc, event);
    }

    // ------------------------------------------------------------------ reads

    @Override
    public Optional<MemoryItem> find(UUID agentId, UUID itemId) {
        List<MemoryItem> rows =
                jdbc().query(
                                // codeql[java/sql-injection]
                                "SELECT "
                                        + COLUMNS
                                        + " FROM "
                                        + table("memory_item")
                                        + " WHERE id = ? AND agent_id = ?",
                                (rs, n) -> MemoryItemRows.map(rs),
                                itemId,
                                agentId);
        return rows.stream().findFirst();
    }

    @Override
    public List<MemoryItem> listByStatus(UUID agentId, MemoryItemStatus status, int limit) {
        return jdbc().query(
                        // codeql[java/sql-injection]
                        "SELECT "
                                + COLUMNS
                                + " FROM "
                                + table("memory_item")
                                + " WHERE agent_id = ? AND status = ? ORDER BY created_at DESC LIMIT ?",
                        (rs, n) -> MemoryItemRows.map(rs),
                        agentId,
                        status.name(),
                        Math.max(1, limit));
    }

    @Override
    public List<MemoryItem> listByAgent(
            UUID agentId, MemoryItemStatus status, MemoryKind kind, int limit) {
        StringBuilder sql =
                new StringBuilder("SELECT ")
                        .append(COLUMNS)
                        .append(" FROM ")
                        .append(table("memory_item"))
                        .append(" WHERE agent_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(agentId);
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status.name());
        }
        if (kind != null) {
            sql.append(" AND kind = ?");
            args.add(kind.name());
        }
        sql.append(" ORDER BY created_at DESC LIMIT ?");
        args.add(Math.max(1, limit));
        // codeql[java/sql-injection]
        return jdbc().query(sql.toString(), (rs, n) -> MemoryItemRows.map(rs), args.toArray());
    }

    @Override
    public List<MemoryItem> listForSubject(String subjectKey, int limit) {
        return jdbc().query(
                        // codeql[java/sql-injection]
                        "SELECT "
                                + COLUMNS
                                + " FROM "
                                + table("memory_item")
                                + " WHERE ? = ANY(subject_keys) ORDER BY created_at DESC LIMIT ?",
                        (rs, n) -> MemoryItemRows.map(rs),
                        subjectKey,
                        Math.max(1, limit));
    }

    @Override
    public List<MemoryItemSource> lineage(UUID itemId) {
        return jdbc().query(
                        // codeql[java/sql-injection]
                        "SELECT source_table, source_key, source_trust FROM "
                                + table("memory_item_source")
                                + " WHERE item_id = ? ORDER BY source_table, source_key",
                        (rs, n) ->
                                new MemoryItemSource(
                                        rs.getString("source_table"),
                                        rs.getString("source_key"),
                                        MemoryTrustLevel.parse(rs.getString("source_trust"))),
                        itemId);
    }

    @Override
    public List<MemoryEvent> events(UUID itemId) {
        return jdbc().query(
                        // codeql[java/sql-injection]
                        "SELECT id, item_id, agent_id, event, actor_type, actor_ref, from_status, to_status, reason, at FROM "
                                + table("memory_event")
                                + " WHERE item_id = ? ORDER BY at, id",
                        (rs, n) ->
                                new MemoryEvent(
                                        rs.getObject("id", UUID.class),
                                        rs.getObject("item_id", UUID.class),
                                        rs.getObject("agent_id", UUID.class),
                                        MemoryEvent.Type.valueOf(rs.getString("event")),
                                        new MemoryActor(
                                                MemoryActor.Type.valueOf(
                                                        rs.getString("actor_type")),
                                                rs.getString("actor_ref")),
                                        MemoryItemRows.nullableStatus(rs.getString("from_status")),
                                        MemoryItemRows.nullableStatus(rs.getString("to_status")),
                                        rs.getString("reason"),
                                        MemoryItemRows.instant(rs, "at")),
                        itemId);
    }

    @Override
    public int countByStatus(UUID agentId, MemoryItemStatus status) {
        Integer n =
                jdbc().queryForObject(
                                // codeql[java/sql-injection]
                                "SELECT count(*) FROM "
                                        + table("memory_item")
                                        + " WHERE agent_id = ? AND status = ?",
                                Integer.class,
                                agentId,
                                status.name());
        return n == null ? 0 : n;
    }

    @Override
    public int countLive(UUID agentId) {
        Integer n =
                jdbc().queryForObject(
                                // codeql[java/sql-injection]
                                "SELECT count(*) FROM "
                                        + table("memory_item")
                                        + " WHERE agent_id = ? AND status IN ('PENDING', 'ACTIVE')",
                                Integer.class,
                                agentId);
        return n == null ? 0 : n;
    }

    @Override
    public int countCreatedSince(UUID agentId, Instant since) {
        Integer n =
                jdbc().queryForObject(
                                // codeql[java/sql-injection]
                                "SELECT count(*) FROM "
                                        + table("memory_event")
                                        + " WHERE agent_id = ? AND event = 'CREATED' AND at >= ?",
                                Integer.class,
                                agentId,
                                MemoryItemRows.ts(since));
        return n == null ? 0 : n;
    }

    @Override
    public List<Recalled> recall(
            UUID agentId,
            MemoryKind kind,
            String subjectKey,
            float[] embedding,
            int limit,
            Instant now) {
        String vector = MemoryItemRows.vector(embedding);
        boolean bySubject = subjectKey != null;
        List<Object> args = new ArrayList<>();
        args.add(vector);
        args.add(agentId);
        args.add(kind.name());
        args.add(MemoryItemRows.ts(now));
        if (bySubject) {
            args.add(subjectKey);
        }
        args.add(vector);
        args.add(Math.max(1, limit));
        // The operator is schema-qualified like the legacy fact store's: on a tenant connection
        // (search_path is the tenant
        // schema only, e.g. inside a transaction) an unqualified <=> is not found and the recall
        // silently degrades.
        // The ACTIVE + TRUSTED predicate is repeated here on purpose: the partial index is a
        // speed-up,
        // not the guarantee.
        String sql =
                "SELECT "
                        + COLUMNS
                        + ", importance, (embedding OPERATOR(public.<=>) ?::public.vector) AS distance FROM "
                        + table("memory_item")
                        + " WHERE agent_id = ? AND kind = ? AND status = 'ACTIVE' AND trust = 'TRUSTED'"
                        + " AND (expires_at IS NULL OR expires_at > ?)"
                        + (bySubject ? " AND ? = ANY(subject_keys)" : "")
                        + tombstones.excludeItems(table("memory_item"))
                        + " ORDER BY (1 - (embedding OPERATOR(public.<=>) ?::public.vector)) * (0.6 + 0.4 * importance) DESC,"
                        + " created_at DESC LIMIT ?";
        return jdbc().query(
                        // codeql[java/sql-injection]
                        sql,
                        (rs, n) ->
                                new Recalled(
                                        MemoryItemRows.map(rs),
                                        rs.getDouble("distance"),
                                        rs.getDouble("importance")),
                        args.toArray());
    }
}
