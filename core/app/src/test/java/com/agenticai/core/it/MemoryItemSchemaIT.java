package com.agenticai.core.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.agents.memory.spi.MemoryKind;
import com.agenticai.core.agents.memory.spi.MemoryProposalReceipt;
import com.agenticai.core.agents.memory.spi.MemoryProposalStatus;
import com.agenticai.core.agents.memory.spi.MemoryProvenanceRecord;
import com.agenticai.core.agents.memory.spi.MemoryRecallRequest;
import com.agenticai.core.agents.memory.spi.MemorySourceRef;
import com.agenticai.core.agents.memory.spi.MemoryTrustLevel;
import com.agenticai.core.agents.memory.spi.MemoryWriteProposal;
import com.agenticai.core.agents.memorystore.BasicMemoryContentScreen;
import com.agenticai.core.agents.memorystore.JdbcMemoryItemStorage;
import com.agenticai.core.agents.memorystore.MemoryActor;
import com.agenticai.core.agents.memorystore.MemoryEmbedder;
import com.agenticai.core.agents.memorystore.MemoryEvent;
import com.agenticai.core.agents.memorystore.MemoryFlagOverride;
import com.agenticai.core.agents.memorystore.MemoryItemReviewService;
import com.agenticai.core.agents.memorystore.MemoryItemStatus;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.dao.DataIntegrityViolationException;
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
class MemoryItemSchemaIT {

    private static final String DATABASE = "memory_item_it";
    private static final String SCHEMA = "tenant_it_memory_items";
    private static final String SCHEMA_B = "tenant_it_memory_items_b";

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static UUID agentId;

    private final MemoryUnifiedProperties props = new MemoryUnifiedProperties();
    private StoreMemoryProposalSink sink;
    private MemoryItemReviewService review;
    private MemoryItemStore store;

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

    private static float[] embedding(String text) {
        float[] v = new float[MemoryEmbedder.DIMENSION];
        v[Math.floorMod(text.hashCode(), v.length)] = 1f;
        v[0] += 0.01f;
        return v;
    }

    private static String vector(String text) {
        float[] v = embedding(text);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            sb.append(i == 0 ? "" : ",").append(v[i]);
        }
        return sb.append(']').toString();
    }

    private void raw(
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

    @Test
    void defaultsAreQuarantinedPendingAndModelGenerated() {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO memory_item (id, agent_id, kind, content, embedding, writer) VALUES (?, ?, 'EPISODIC',"
                        + " 'c', ?::public.vector, 'AGENT')",
                id,
                agentId,
                vector("c"));
        assertThat(
                        jdbc.queryForMap(
                                "SELECT trust, status, model_generated, generation, version, pinned FROM memory_item WHERE id = ?",
                                id))
                .containsEntry("trust", "QUARANTINED")
                .containsEntry("status", "PENDING")
                .containsEntry("model_generated", true)
                .containsEntry("generation", 1)
                .containsEntry("version", 1)
                .containsEntry("pinned", false);
    }

    @Test
    void activeRequiresTrusted() {
        assertThatThrownBy(() -> raw("EPISODIC", "QUARANTINED", "ACTIVE", null, "alice", 1))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("memory_item_active_trusted_chk");
        raw("EPISODIC", "TRUSTED", "ACTIVE", null, null, 1);
    }

    @Test
    void trustedWithTaintRequiresAnApprover() {
        assertThatThrownBy(() -> raw("EPISODIC", "TRUSTED", "PENDING", "[\"web\"]", null, 1))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("memory_item_taint_approval_chk");
        raw("EPISODIC", "TRUSTED", "PENDING", "[\"web\"]", "alice", 1);
        raw("EPISODIC", "TRUSTED", "PENDING", "[]", null, 1);
        raw("EPISODIC", "TRUSTED", "PENDING", null, null, 1);
    }

    @Test
    void anActiveProceduralItemRequiresAnApprover() {
        assertThatThrownBy(() -> raw("PROCEDURAL", "TRUSTED", "ACTIVE", null, null, 1))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("memory_item_procedural_chk");
        raw("PROCEDURAL", "TRUSTED", "ACTIVE", null, "alice", 1);
        raw("PROCEDURAL", "QUARANTINED", "PENDING", null, null, 1);
    }

    @Test
    void generationIsBoundedAndVocabulariesAreClosed() {
        assertThatThrownBy(() -> raw("EPISODIC", "QUARANTINED", "PENDING", null, null, 9))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("memory_item_generation_chk");
        raw("EPISODIC", "QUARANTINED", "PENDING", null, null, 8);
        raw("EPISODIC", "QUARANTINED", "PENDING", null, null, 0);
        assertThatThrownBy(() -> raw("SEMANTIC", "QUARANTINED", "PENDING", null, null, 1))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("memory_item_kind_chk");
        assertThatThrownBy(() -> raw("EPISODIC", "MAYBE", "PENDING", null, null, 1))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("memory_item_trust_chk");
        assertThatThrownBy(() -> raw("EPISODIC", "QUARANTINED", "DONE", null, null, 1))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("memory_item_status_chk");
    }

    @Test
    void aPromotionUpdateThatSkipsTheApproverIsRejectedForProceduralAndTaintedRows() {
        UUID proc =
                sink.propose(
                                new MemoryWriteProposal(
                                        agentId,
                                        "u",
                                        MemoryKind.PROCEDURAL,
                                        "steps",
                                        MemoryProvenanceRecord.TRUSTED,
                                        null,
                                        false))
                        .orElseThrow()
                        .id();
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE memory_item SET trust = 'TRUSTED', status = 'ACTIVE' WHERE id = ?",
                                        proc))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("memory_item_procedural_chk");
        UUID tainted =
                sink.propose(
                                new MemoryWriteProposal(
                                        agentId,
                                        "u",
                                        MemoryKind.EPISODIC,
                                        "web thing",
                                        new MemoryProvenanceRecord(
                                                MemoryTrustLevel.TRUSTED, List.of("web"), null),
                                        null,
                                        false))
                        .orElseThrow()
                        .id();
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE memory_item SET trust = 'TRUSTED', status = 'ACTIVE' WHERE id = ?",
                                        tainted))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("memory_item_taint_approval_chk");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM memory_item WHERE id = ?",
                                String.class,
                                tainted))
                .isEqualTo("PENDING");
    }

    // ------------------------------------------------------------------ keys, events, competence

    @Test
    void eventsHaveNoForeignKeyAndSurviveTheirItem() {
        UUID item = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO memory_event (id, item_id, agent_id, event, actor_type) VALUES (?, ?, ?, 'CREATED', 'AGENT')",
                UUID.randomUUID(),
                item,
                agentId);
        UUID real = sink.propose(proposal("x")).orElseThrow().id();
        review.delete(agentId, real, MemoryActor.human("alice"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM memory_event WHERE item_id = ?",
                                Integer.class,
                                real))
                .isEqualTo(2); // CREATED + ERASED
    }

    @Test
    void lineageCascadesWithTheItemAndAgentDeletionCascadesItems() {
        UUID other = ExternalPostgres.insertAgent(jdbc, "memory-item-it-cascade");
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO memory_item (id, agent_id, kind, content, embedding, writer) VALUES (?, ?, 'EPISODIC', 'c',"
                        + " ?::public.vector, 'AGENT')",
                id,
                other,
                vector("c"));
        jdbc.update(
                "INSERT INTO memory_item_source (item_id, source_table, source_key, source_trust) VALUES (?, 'turn', 't1', 'TRUSTED')",
                id);
        jdbc.update("DELETE FROM agent WHERE id = ?", other);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM memory_item WHERE id = ?", Integer.class, id))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM memory_item_source WHERE item_id = ?",
                                Integer.class,
                                id))
                .isZero();
    }

    @Test
    void competenceCountersHaveAKeyDefaultsAndAnAgentForeignKey() {
        jdbc.update(
                "INSERT INTO memory_competence (agent_id, task_class) VALUES (?, 'refund_dispute')",
                agentId);
        assertThat(
                        jdbc.queryForMap(
                                "SELECT attempts, verified_success, unknown_outcome FROM memory_competence WHERE agent_id = ?",
                                agentId))
                .containsEntry("attempts", 0)
                .containsEntry("verified_success", 0)
                .containsEntry("unknown_outcome", 0);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO memory_competence (agent_id, task_class) VALUES (?, 'refund_dispute')",
                                        agentId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO memory_competence (agent_id, task_class) VALUES (?, 'x')",
                                        UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("DELETE FROM memory_competence");
    }

    @Test
    void competenceWriterIsAtomicUnderConcurrencyAndCapsTaskClasses() throws Exception {
        jdbc.update("DELETE FROM memory_competence");
        StaticListableBeanFactory b = new StaticListableBeanFactory();
        b.addBean("jdbc", jdbc);
        com.agenticai.core.agents.memorystore.CompetenceProperties cp =
                new com.agenticai.core.agents.memorystore.CompetenceProperties();
        cp.setEnabled(true);
        cp.setMaxTaskClassesPerAgent(3);
        var tracker =
                new com.agenticai.core.agents.memorystore.CompetenceTracker(
                        cp,
                        new com.agenticai.core.agents.memorystore.CompetenceGate(
                                cp,
                                b.getBeanProvider(
                                        com.agenticai.core.settings.SettingsResolver.class)),
                        new com.agenticai.core.agents.memorystore.JdbcCompetenceStorage(
                                b.getBeanProvider(JdbcTemplate.class)),
                        b.getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class),
                        Clock.systemUTC());
        int threads = 16;
        int per = 40;
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.List<java.util.concurrent.Future<?>> fs = new java.util.ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int tt = t;
            fs.add(
                    pool.submit(
                            () -> {
                                TenantContext.set(TenantContext.of(SCHEMA, "it-user", Set.of()));
                                for (int i = 0; i < per; i++) {
                                    // 8 distinct classes contend for 3 slots; plus one hot class
                                    String cls = i % 2 == 0 ? "hot" : "cls_" + ((tt + i) % 8);
                                    tracker.record(
                                            new com.agenticai.core.agents.memory.spi
                                                    .VerifiedOutcome(
                                                    agentId,
                                                    cls,
                                                    i % 4 == 0
                                                            ? com.agenticai.core.agents.memory.spi
                                                                    .VerifiedOutcome.Signal
                                                                    .TOOL_ERROR
                                                            : com.agenticai.core.agents.memory.spi
                                                                    .VerifiedOutcome.Signal
                                                                    .TOOL_SUCCESS));
                                }
                                TenantContext.clear();
                            }));
        }
        for (var f : fs) {
            f.get(60, java.util.concurrent.TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT sum(attempts) FROM memory_competence WHERE agent_id = ?",
                                Integer.class,
                                agentId))
                .isEqualTo(threads * per);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT sum(verified_success + verified_failure) FROM memory_competence WHERE agent_id = ?",
                                Integer.class,
                                agentId))
                .isEqualTo(threads * per);
        // at most 3 real classes (hot may or may not be one of them) plus the overflow row
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM memory_competence WHERE agent_id = ? AND task_class <> '_overflow'",
                                Integer.class,
                                agentId))
                .isLessThanOrEqualTo(3);
        jdbc.update("DELETE FROM memory_competence");
    }

    // ------------------------------------------------------------------ partial indexes

    @Test
    void partialIndexesExistWithTheirPredicates() {
        List<String> defs =
                jdbc.queryForList(
                        "SELECT indexdef FROM pg_indexes WHERE schemaname = ? AND tablename = 'memory_item'",
                        String.class,
                        SCHEMA);
        assertThat(defs)
                .anySatisfy(
                        d ->
                                assertThat(d)
                                        .contains("idx_memory_item_recall")
                                        .contains("agent_id, kind")
                                        .contains("status")
                                        .contains("'ACTIVE'")
                                        .contains("trust")
                                        .contains("'TRUSTED'"));
        assertThat(defs)
                .anySatisfy(
                        d ->
                                assertThat(d)
                                        .contains("idx_memory_item_review")
                                        .contains("created_at DESC")
                                        .contains("'PENDING'"));
        assertThat(defs)
                .anySatisfy(
                        d ->
                                assertThat(d)
                                        .contains("idx_memory_item_run")
                                        .contains("learn_run_id IS NOT NULL"));
        assertThat(defs)
                .anySatisfy(
                        d -> assertThat(d).contains("idx_memory_item_subjects").contains("gin"));
    }

    @Test
    void theRecallAndReviewQueriesCanUseTheirPartialIndexes() {
        for (int i = 0; i < 30; i++) {
            raw(
                    "EPISODIC",
                    i % 2 == 0 ? "TRUSTED" : "QUARANTINED",
                    i % 2 == 0 ? "ACTIVE" : "PENDING",
                    null,
                    null,
                    1);
        }
        jdbc.execute("ANALYZE memory_item");
        String recall =
                plan(
                        "SELECT id FROM memory_item WHERE agent_id = '"
                                + agentId
                                + "' AND kind = 'EPISODIC'"
                                + " AND status = 'ACTIVE' AND trust = 'TRUSTED'");
        assertThat(recall).contains("idx_memory_item_recall");
        String review =
                plan(
                        "SELECT id FROM memory_item WHERE agent_id = '"
                                + agentId
                                + "' AND status = 'PENDING'"
                                + " ORDER BY created_at DESC");
        assertThat(review).contains("idx_memory_item_review");
    }

    private String plan(String sql) {
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

    private MemoryWriteProposal proposal(String text) {
        return new MemoryWriteProposal(
                agentId,
                "user-1",
                MemoryKind.EPISODIC,
                text,
                MemoryProvenanceRecord.TRUSTED,
                null,
                false);
    }

    @Test
    void proposalApprovalAndRecallRoundTrip() {
        MemoryProposalReceipt r =
                sink.propose(proposal("refund failed at step 3 because the PSP timed out"))
                        .orElseThrow();
        assertThat(r.status()).isEqualTo(MemoryProposalStatus.PENDING);
        assertThat(
                        jdbc.queryForMap(
                                "SELECT trust, status, approved_by, kind, version FROM memory_item WHERE id = ?",
                                r.id()))
                .containsEntry("trust", "QUARANTINED")
                .containsEntry("status", "PENDING")
                .containsEntry("approved_by", null);

        MemoryRecallRequest req =
                new MemoryRecallRequest(
                        agentId,
                        "user-1",
                        "refund failed at step 3 because the PSP timed out",
                        Set.of(),
                        8,
                        800);
        assertThat(store.recall(req).items()).as("pending is not recalled").isEmpty();

        assertThat(review.approve(agentId, r.id(), MemoryActor.human("alice")).ok()).isTrue();
        assertThat(
                        jdbc.queryForMap(
                                "SELECT trust, status, approved_by FROM memory_item WHERE id = ?",
                                r.id()))
                .containsEntry("trust", "TRUSTED")
                .containsEntry("status", "ACTIVE")
                .containsEntry("approved_by", "alice");
        var recalled = store.recall(req);
        assertThat(recalled.degradedReason()).isNull();
        assertThat(recalled.items()).hasSize(1);
        assertThat(recalled.items().get(0).id()).isEqualTo(r.id());
        assertThat(recalled.items().get(0).score()).isGreaterThan(0.5);

        // another subject's episodes are not returned; the same agent's item for the right subject
        // is
        assertThat(
                        store.recall(
                                        new MemoryRecallRequest(
                                                agentId,
                                                "user-2",
                                                req.queryText(),
                                                Set.of(),
                                                8,
                                                800))
                                .items())
                .isEmpty();

        // revert takes effect on the next query
        assertThat(review.revert(agentId, r.id(), MemoryActor.human("alice")).ok()).isTrue();
        assertThat(store.recall(req).items()).isEmpty();

        List<String> events =
                jdbc.queryForList(
                        "SELECT event FROM memory_event WHERE item_id = ? ORDER BY at, id",
                        String.class,
                        r.id());
        assertThat(events).containsExactlyInAnyOrder("CREATED", "APPROVED", "REVERTED");
    }

    @Test
    void expiredRowsAreNotRecalled() {
        UUID id = sink.propose(proposal("old episode")).orElseThrow().id();
        review.approve(agentId, id, MemoryActor.human("alice"));
        jdbc.update(
                "UPDATE memory_item SET expires_at = now() - interval '1 minute' WHERE id = ?", id);
        assertThat(
                        store.recall(
                                        new MemoryRecallRequest(
                                                agentId, "user-1", "old episode", Set.of(), 8, 800))
                                .items())
                .isEmpty();
    }

    @Test
    void derivedItemsStoreLineageTaintAndGeneration() {
        UUID tainted =
                sink.propose(
                                new MemoryWriteProposal(
                                        agentId,
                                        "user-1",
                                        MemoryKind.EPISODIC,
                                        "from web",
                                        new MemoryProvenanceRecord(
                                                MemoryTrustLevel.TRUSTED, List.of("web"), null),
                                        null,
                                        false))
                        .orElseThrow()
                        .id();
        review.approve(agentId, tainted, MemoryActor.human("alice"));
        MemoryProposalReceipt d =
                sink.propose(
                                new MemoryWriteProposal(
                                        agentId,
                                        "user-1",
                                        MemoryKind.PROCEDURAL,
                                        "steps",
                                        null,
                                        null,
                                        true,
                                        List.of(MemorySourceRef.item(tainted))))
                        .orElseThrow();
        assertThat(d.status()).isEqualTo(MemoryProposalStatus.QUARANTINED);
        assertThat(
                        jdbc.queryForMap(
                                "SELECT generation, model_generated, writer, source_taint, status, trust FROM memory_item WHERE id = ?",
                                d.id()))
                .containsEntry("generation", 1)
                .containsEntry("model_generated", true)
                .containsEntry("writer", "DISTILLER")
                .containsEntry("source_taint", "[\"web\"]")
                .containsEntry("status", "PENDING")
                .containsEntry("trust", "QUARANTINED");
        assertThat(
                        jdbc.queryForList(
                                "SELECT source_key FROM memory_item_source WHERE item_id = ?",
                                String.class,
                                d.id()))
                .containsExactly(tainted.toString());
        // an approved procedural item with taint satisfies both CHECKs because the approver is
        // recorded
        assertThat(review.approve(agentId, d.id(), MemoryActor.human("bob")).ok()).isTrue();
        // the model-generated derived item cannot feed a further distillation until confirmed: it
        // was approved, so it can
        assertThat(
                        sink.proposeDetailed(
                                        new MemoryWriteProposal(
                                                agentId,
                                                "user-1",
                                                MemoryKind.SELF_MODEL,
                                                "meta",
                                                null,
                                                null,
                                                true,
                                                List.of(MemorySourceRef.item(d.id()))))
                                .accepted())
                .isTrue();
    }

    @Test
    void editCreatesAVersionAndSupersedesOnApprovalOnTheRealTables() {
        UUID id = sink.propose(proposal("v1")).orElseThrow().id();
        review.approve(agentId, id, MemoryActor.human("alice"));
        UUID v2 = review.edit(agentId, id, "v2", MemoryActor.human("alice")).itemId();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM memory_item WHERE id = ?", String.class, id))
                .isEqualTo("ACTIVE");
        review.approve(agentId, v2, MemoryActor.human("alice"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM memory_item WHERE id = ?", String.class, id))
                .isEqualTo("SUPERSEDED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT supersedes_id FROM memory_item WHERE id = ?",
                                UUID.class,
                                v2))
                .isEqualTo(id);
    }

    @Test
    void eventsNeverContainMemoryText() {
        sink.propose(proposal("the customer Priya wants refunds in INR"));
        String all =
                String.join(
                        " ",
                        jdbc.queryForList(
                                "SELECT coalesce(reason,'') || coalesce(actor_ref,'') FROM memory_event",
                                String.class));
        assertThat(all).doesNotContain("Priya").doesNotContain("INR");
    }

    @Test
    void anotherTenantNeitherSeesNorApprovesTheItem() {
        MemoryProposalReceipt r = sink.propose(proposal("tenant a secret")).orElseThrow();
        review.approve(agentId, r.id(), MemoryActor.human("alice"));
        MemoryRecallRequest req =
                new MemoryRecallRequest(agentId, "user-1", "tenant a secret", Set.of(), 8, 800);
        assertThat(store.recall(req).items()).hasSize(1);

        TenantContext.set(TenantContext.of(SCHEMA_B, "it-user-b", Set.of()));
        assertThat(store.recall(req).items()).isEmpty();
        assertThat(review.approve(agentId, r.id(), MemoryActor.human("mallory")).refusal())
                .isEqualTo("NOT_FOUND");
        assertThat(review.delete(agentId, r.id(), MemoryActor.human("mallory")).refusal())
                .isEqualTo("NOT_FOUND");

        TenantContext.set(TenantContext.of(SCHEMA, "it-user", Set.of()));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM memory_item WHERE id = ?",
                                String.class,
                                r.id()))
                .isEqualTo("ACTIVE");
    }

    @Test
    void threadWithoutTenantStoresAndRecallsNothing() {
        TenantContext.clear();
        assertThat(sink.proposeDetailed(proposal("x")).refusal()).isEqualTo("NO_TENANT");
        assertThat(
                        store.recall(
                                        new MemoryRecallRequest(
                                                agentId, "user-1", "x", Set.of(), 8, 800))
                                .degradedReason())
                .isEqualTo("no-tenant");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM memory_item", Integer.class)).isZero();
    }

    @Test
    void statusEnumMatchesTheCheckConstraintValues() {
        String def =
                jdbc.queryForObject(
                        "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'memory_item_status_chk'"
                                + " AND conrelid = 'memory_item'::regclass",
                        String.class);
        for (MemoryItemStatus s : MemoryItemStatus.values()) {
            assertThat(def).contains("'" + s.name() + "'");
        }
        assertThat(MemoryEvent.Type.values()).isNotEmpty();
    }
}
