package com.agenticai.core.it;

import com.agenticai.core.agents.memory.spi.MemoryKind;
import com.agenticai.core.agents.memory.spi.MemoryProvenanceRecord;
import com.agenticai.core.agents.memory.spi.MemoryWriteProposal;
import com.agenticai.core.agents.memorystore.BasicMemoryContentScreen;
import com.agenticai.core.agents.memorystore.JdbcMemoryItemStorage;
import com.agenticai.core.agents.memorystore.MemoryEmbedder;
import com.agenticai.core.agents.memorystore.MemoryFlagOverride;
import com.agenticai.core.agents.memorystore.MemoryItemReviewService;
import com.agenticai.core.agents.memorystore.MemoryItemStore;
import com.agenticai.core.agents.memorystore.MemoryUnifiedGate;
import com.agenticai.core.agents.memorystore.MemoryUnifiedProperties;
import com.agenticai.core.agents.memorystore.StoreMemoryProposalSink;
import com.agenticai.core.security.TenantContext;
import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V681 / V682 (unified agent memory, ADR-0028) against a REAL Postgres with pgvector: the CHECK
 * constraints, the partial indexes, defaults, foreign keys and the append-only event table, and
 * then the store services over the real tables (proposal, approval, recall, tenant isolation).
 *
 * <p>No Spring context, no Docker: needs {@code -Dvegaduta.it.pg.url=...} (see {@link
 * ExternalPostgres}); skipped otherwise.
 */
@Tag("integration")
abstract class MemoryItemSchemaITBase {

    static final String DATABASE = "memory_item_it";
    static final String SCHEMA = "tenant_it_memory_items";
    static final String SCHEMA_B = "tenant_it_memory_items_b";

    static DataSource dataSource;
    static JdbcTemplate jdbc;
    static UUID agentId;

    final MemoryUnifiedProperties props = new MemoryUnifiedProperties();
    StoreMemoryProposalSink sink;
    MemoryItemReviewService review;
    MemoryItemStore store;

    @BeforeAll
    static void migrate() {
        ExternalPostgres.requireOrSkip();
        dataSource = ExternalPostgres.freshDatabase(DATABASE, SCHEMA);
        jdbc = new JdbcTemplate(dataSource);
        ExternalPostgres.tenantFlyway(dataSource, SCHEMA, null).migrate();
        ExternalPostgres.tenantFlyway(dataSource, SCHEMA_B, null).migrate();
        agentId = ExternalPostgres.insertAgent(jdbc, "memory-item-it");
    }

    @BeforeEach
    void wire() {
        TenantContext.set(TenantContext.of(SCHEMA, "it-user", Set.of()));
        jdbc.update("DELETE FROM memory_event");
        jdbc.update("DELETE FROM memory_item");
        props.setEnabled(true);
        props.setKinds(
                EnumSet.of(MemoryKind.EPISODIC, MemoryKind.PROCEDURAL, MemoryKind.SELF_MODEL));
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("jdbc", jdbc);
        beans.addBean("embedder", (MemoryEmbedder) MemoryItemSchemaIT::embedding);
        beans.addBean("allow", (MemoryFlagOverride) id -> true);
        JdbcMemoryItemStorage storage =
                new JdbcMemoryItemStorage(beans.getBeanProvider(JdbcTemplate.class));
        MemoryUnifiedGate gate =
                new MemoryUnifiedGate(props, beans.getBeanProvider(MemoryFlagOverride.class));
        var embedder = beans.getBeanProvider(MemoryEmbedder.class);
        List<com.agenticai.core.agents.memorystore.MemoryContentScreen> screens =
                List.of(new BasicMemoryContentScreen(props));
        var noMeters =
                new StaticListableBeanFactory()
                        .getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class);
        sink =
                new StoreMemoryProposalSink(
                        props, gate, storage, embedder, screens, noMeters, Clock.systemUTC());
        review = new MemoryItemReviewService(props, storage, embedder, screens, Clock.systemUTC());
        store = new MemoryItemStore(props, gate, storage, embedder, Clock.systemUTC());
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    static float[] embedding(String text) {
        float[] v = new float[MemoryEmbedder.DIMENSION];
        v[Math.floorMod(text.hashCode(), v.length)] = 1f;
        v[0] += 0.01f;
        return v;
    }

    static String vector(String text) {
        float[] v = embedding(text);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            sb.append(i == 0 ? "" : ",").append(v[i]);
        }
        return sb.append(']').toString();
    }

    void raw(
            String kind,
            String trust,
            String status,
            String taint,
            String approvedBy,
            int generation) {
        jdbc.update(
                "INSERT INTO memory_item (id, agent_id, kind, content, embedding, writer, trust, status, source_taint,"
                        + " approved_by, generation) VALUES (?, ?, ?, 'c', ?::public.vector, 'AGENT', ?, ?, ?, ?, ?)",
                UUID.randomUUID(),
                agentId,
                kind,
                vector("c"),
                trust,
                status,
                taint,
                approvedBy,
                generation);
    }

    // ------------------------------------------------------------------ CHECK constraints

    String plan(String sql) {
        return jdbc.execute(
                (java.sql.Connection c) -> {
                    try (var s = c.createStatement()) {
                        s.execute("SET enable_seqscan = off");
                        StringBuilder sb = new StringBuilder();
                        try (var rs = s.executeQuery("EXPLAIN " + sql)) {
                            while (rs.next()) {
                                sb.append(rs.getString(1)).append('\n');
                            }
                        }
                        s.execute("RESET enable_seqscan");
                        return sb.toString();
                    }
                });
    }

    // ------------------------------------------------------------------ services over the real
    // tables

    MemoryWriteProposal proposal(String text) {
        return new MemoryWriteProposal(
                agentId,
                "user-1",
                MemoryKind.EPISODIC,
                text,
                MemoryProvenanceRecord.TRUSTED,
                null,
                false);
    }
}
