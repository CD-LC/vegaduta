package com.agenticai.core.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import com.agenticai.core.agents.learning.consolidation.MemoryConsolidationService;
import com.agenticai.core.agents.memory.TenantMemoryEntry;
import com.agenticai.core.agents.memory.TenantMemoryService;
import com.agenticai.core.agents.memory.importer.MemoryImportDtos.ImportFact;
import com.agenticai.core.agents.memory.importer.MemoryImportDtos.ImportRequest;
import com.agenticai.core.agents.memory.importer.MemoryImportService;
import com.agenticai.core.agents.memory.spi.MemoryKind;
import com.agenticai.core.agents.memory.spi.MemoryProvenanceRecord;
import com.agenticai.core.agents.memory.spi.MemoryRecallRequest;
import com.agenticai.core.agents.memory.spi.MemoryWriteProposal;
import com.agenticai.core.agents.memory.spi.UnifiedMemoryRecall;
import com.agenticai.core.agents.memorystore.MemoryActor;
import com.agenticai.core.agents.memorystore.MemoryItemReviewService;
import com.agenticai.core.agents.memorystore.StoreMemoryProposalSink;
import com.agenticai.core.knowledge.SidecarClient;
import com.agenticai.core.security.TenantContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Consolidation of the unified store and enforcement of tombstones (ADR-0028 WP5c), end to end on a
 * real Postgres with the real tenant Flyway: merges and summaries of {@code memory_item} rows are
 * filed as PENDING proposals with lineage (also with the circuit breaker tripped), only a person's
 * approval supersedes the sources, forgotten items are hidden from recall and cannot be
 * re-proposed, and the legacy shared tier gets the same recall exclusion and resurrection guard
 * (importer). Items are seeded as ACTIVE rows (human-approved, aged by SQL); the clock of the
 * consolidation moves forward one scenario at a time. Needs {@code -Dvegaduta.it.pg.url} like the
 * other unified-memory ITs.
 */
@TestPropertySource(
        properties = {
            "app.memory.unified.enabled=true",
            "app.memory.unified.kinds=episodic,procedural,self-model",
            "app.memory.consolidation.enabled=true",
            "app.memory.consolidation.auto-apply=true"
        })
@Import(UnifiedMemoryBootBase.FakeEmbedderConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class UnifiedMemoryConsolidationIT extends UnifiedMemoryBootBase {

    static final String A = "tenant_it_umc_a";
    static final MemoryActor REVIEWER = MemoryActor.human("reviewer-1");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        register(registry, "umc_on");
    }

    @BeforeAll
    static void needsPostgres() {
        requirePostgres();
    }

    @Autowired MemoryConsolidationService consolidation;
    @Autowired MemoryItemReviewService review;
    @Autowired StoreMemoryProposalSink sink;
    @Autowired UnifiedMemoryRecall recall;
    @Autowired TenantMemoryService tenantMemory;
    @Autowired MemoryImportService importer;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean SidecarClient sidecar;

    private static final UnifiedMemoryJourneyIT.MutableClock CLOCK =
            new UnifiedMemoryJourneyIT.MutableClock(Instant.now());
    private static boolean worldReady;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
        consolidation.useClock(Clock.system(ZoneOffset.UTC));
    }

    private void world() {
        if (!worldReady) {
            tenantSchema(A);
            worldReady = true;
        }
        TenantContext.set(TenantContext.of(A, "reviewer-1", Set.of()));
        consolidation.useClock(CLOCK);
    }

    private UUID agent(String name) {
        return newAgent(name);
    }

    private int count(String table, String where, Object... args) {
        Integer n =
                jdbc.queryForObject(
                        "SELECT count(*) FROM "
                                + A
                                + "."
                                + table
                                + (where.isEmpty() ? "" : " WHERE " + where),
                        Integer.class,
                        args);
        return n == null ? 0 : n;
    }

    private String str(String sql, Object... args) {
        return jdbc.queryForObject(sql.replace("{A}", A), String.class, args);
    }

    /**
     * An ACTIVE, TRUSTED, human-approved item aged by SQL (what a person's approval leaves behind).
     */
    private UUID item(UUID agent, MemoryKind kind, String text, int ageDays, double importance) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO "
                        + A
                        + ".memory_item (id, agent_id, kind, content, embedding, trust, status, writer,"
                        + " source_type, model_generated, generation, importance, created_at, approved_by, approved_at)"
                        + " VALUES (?, ?, ?, ?, ?::public.vector, 'TRUSTED', 'ACTIVE', 'AGENT', 'UNKNOWN', false, 0, ?,"
                        + " now() - make_interval(days => ?), 'seed', now() - make_interval(days => ?))",
                id,
                agent,
                kind.name(),
                text,
                vector(text),
                importance,
                ageDays,
                ageDays);
        return id;
    }

    private void fresh(UUID agent, MemoryKind kind, int n) {
        for (int i = 0; i < n; i++) {
            item(
                    agent,
                    kind,
                    "fresh " + kind.name().toLowerCase() + " note " + UUID.randomUUID(),
                    1,
                    0.5);
        }
    }

    private static String vector(String text) {
        float[] v = embed(text);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            sb.append(i == 0 ? "" : ",").append(v[i]);
        }
        return sb.append(']').toString();
    }

    private int runColumn(UUID agent, String column) {
        Integer n =
                jdbc.queryForObject(
                        "SELECT "
                                + column
                                + " FROM "
                                + A
                                + ".memory_consolidation_runs WHERE subject = ?"
                                + " ORDER BY run_date DESC LIMIT 1",
                        Integer.class,
                        "item:" + agent);
        return n == null ? 0 : n;
    }

    private List<UUID> pending(UUID agent, String sourceType) {
        return jdbc.queryForList(
                "SELECT id FROM "
                        + A
                        + ".memory_item WHERE agent_id = ? AND status = 'PENDING' AND source_type = ?",
                UUID.class,
                agent,
                sourceType);
    }

    private String status(UUID id) {
        return jdbc.queryForObject(
                "SELECT status FROM " + A + ".memory_item WHERE id = ?", String.class, id);
    }

    // ------------------------------------------------------------------ merge proposal, approval
    // supersedes

    @Test
    @Order(1)
    void aMergeIsFiledAsAPendingProposalWithLineageAndOnlyAPersonsApprovalSupersedesTheSources() {
        world();
        UUID agent = agent("umc-merge");
        UUID keep =
                item(
                        agent,
                        MemoryKind.PROCEDURAL,
                        "alice checks espresso machine settings",
                        40,
                        0.5);
        UUID dup =
                item(
                        agent,
                        MemoryKind.PROCEDURAL,
                        "alice checks espresso machine settings twice daily",
                        41,
                        0.5);
        fresh(agent, MemoryKind.PROCEDURAL, 8);

        MemoryConsolidationService.Report r = consolidation.consolidateTenant();

        assertThat(r.proposals()).isGreaterThanOrEqualTo(1);
        assertThat(runColumn(agent, "merges")).isEqualTo(1);
        assertThat(runColumn(agent, "proposals")).isEqualTo(1);
        List<UUID> proposals = pending(agent, "CONSOL_MERGE");
        assertThat(proposals)
                .as("the merge is a proposal for a person, never an ACTIVE row")
                .hasSize(1);
        UUID proposal = proposals.get(0);
        assertThat(
                        str(
                                "SELECT trust || '/' || status || '/' || writer FROM {A}.memory_item WHERE id = ?::uuid",
                                proposal.toString()))
                .isEqualTo("QUARANTINED/PENDING/DISTILLER");
        assertThat(
                        jdbc.queryForList(
                                "SELECT source_key FROM "
                                        + A
                                        + ".memory_item_source WHERE item_id = ?",
                                String.class,
                                proposal))
                .containsExactlyInAnyOrder(keep.toString(), dup.toString());
        assertThat(status(keep)).as("the sweep changed nothing").isEqualTo("ACTIVE");
        assertThat(status(dup)).isEqualTo("ACTIVE");
        assertThat(count("memory_tombstone", "subject_key = ?", "item:" + agent)).isZero();

        // The same night and the next night: nothing is filed twice while a person has not decided.
        assertThat(consolidation.consolidateTenant().proposals()).isGreaterThanOrEqualTo(0);
        CLOCK.advance(Duration.ofDays(1));
        consolidation.consolidateTenant();
        assertThat(pending(agent, "CONSOL_MERGE")).hasSize(1);

        assertThat(review.approve(agent, proposal, REVIEWER).ok()).isTrue();

        assertThat(status(proposal)).isEqualTo("ACTIVE");
        assertThat(status(keep))
                .as("approval, by a human, supersedes the sources")
                .isEqualTo("SUPERSEDED");
        assertThat(status(dup)).isEqualTo("SUPERSEDED");
        assertThat(
                        jdbc.queryForList(
                                "SELECT actor_ref FROM "
                                        + A
                                        + ".memory_event WHERE item_id = ? AND event = 'EDITED'",
                                String.class,
                                keep))
                .containsExactly("reviewer-1");
        List<UUID> recalled =
                recall
                        .compose(
                                new MemoryRecallRequest(
                                        agent,
                                        "alice",
                                        "alice checks espresso machine settings",
                                        Set.of(),
                                        0,
                                        800))
                        .items()
                        .stream()
                        .map(v -> v.id())
                        .toList();
        assertThat(recalled).contains(proposal).doesNotContain(keep, dup);
        assertThat(count("memory_item_source", "item_id = ?", proposal))
                .as("lineage survives")
                .isEqualTo(2);
    }

    // ------------------------------------------------------------------ summary proposal,
    // rejection leaves sources

    @Test
    @Order(2)
    void staleItemsAreFiledAsASummaryProposalAndRejectingItLeavesTheSourcesActive() {
        world();
        UUID agent = agent("umc-summary");
        UUID one =
                item(
                        agent,
                        MemoryKind.EPISODIC,
                        "episode about the quarterly roadmap meeting",
                        50,
                        0.5);
        UUID two =
                item(
                        agent,
                        MemoryKind.EPISODIC,
                        "episode about the annual budget workshop",
                        52,
                        0.5);
        fresh(agent, MemoryKind.EPISODIC, 10);

        consolidation.consolidateTenant();

        assertThat(runColumn(agent, "summaries")).isEqualTo(1);
        assertThat(runColumn(agent, "proposals")).isEqualTo(1);
        UUID proposal = pending(agent, "CONSOL_SUMMARY").get(0);
        assertThat(
                        str(
                                "SELECT content FROM {A}.memory_item WHERE id = ?::uuid",
                                proposal.toString()))
                .startsWith("Summary of 2 notes: ");
        assertThat(
                        str(
                                "SELECT trust || '/' || status || '/' || generation FROM {A}.memory_item WHERE id = ?::uuid",
                                proposal.toString()))
                .isEqualTo("QUARANTINED/PENDING/1");
        assertThat(count("memory_item_source", "item_id = ?", proposal)).isEqualTo(2);

        assertThat(review.reject(agent, proposal, REVIEWER).ok()).isTrue();
        assertThat(status(one)).isEqualTo("ACTIVE");
        assertThat(status(two)).isEqualTo("ACTIVE");
    }

    // ------------------------------------------------------------------ breaker trips: proposals
    // filed, nothing applied

    @Test
    @Order(3)
    void whenTheBreakerTripsTheMergesThatFitAreStillFiledAndNothingIsApplied() {
        world();
        CLOCK.advance(
                Duration.ofDays(
                        10)); // past the dry-run window: the plan may auto-apply unless the breaker
        // trips
        UUID agent = agent("umc-breaker");
        for (int i = 0; i < 4; i++) {
            item(agent, MemoryKind.PROCEDURAL, "forgettable zx" + i + " qv" + i, 300, 0.01);
        }
        String[][] pairs = {
            {"alice likes espresso coffee", "alice likes espresso coffee very much"},
            {"bob prefers hindi movies", "bob prefers hindi movies on weekends"},
            {"carol drives red scooter", "carol drives red scooter daily"}
        };
        for (String[] p : pairs) {
            item(agent, MemoryKind.PROCEDURAL, p[0], 40, 0.5);
            item(agent, MemoryKind.PROCEDURAL, p[1], 41, 0.5);
        }
        fresh(agent, MemoryKind.PROCEDURAL, 10);

        consolidation.consolidateTenant();

        assertThat(runColumn(agent, "forgets")).isEqualTo(4);
        assertThat(runColumn(agent, "tombstoned"))
                .as("the breaker tripped: nothing applied")
                .isZero();
        assertThat(
                        count(
                                "memory_consolidation_runs",
                                "subject = ? AND mode = 'dry_run'",
                                "item:" + agent))
                .isEqualTo(1);
        assertThat(runColumn(agent, "proposals")).isEqualTo(2);
        assertThat(pending(agent, "CONSOL_MERGE")).hasSize(2);
        assertThat(count("memory_tombstone", "subject_key = ?", "item:" + agent)).isZero();
    }

    // ------------------------------------------------------------------ forgotten item: hidden
    // from recall, cannot be re-proposed

    @Test
    @Order(4)
    void aForgottenItemIsTombstonedHiddenFromRecallAndCannotBeProposedAgain() {
        world();
        CLOCK.advance(Duration.ofDays(1));
        UUID agent = agent("umc-forget");
        UUID forgettable =
                item(
                        agent,
                        MemoryKind.PROCEDURAL,
                        "obsolete runbook step zephyr quokka",
                        300,
                        0.01);
        UUID live = item(agent, MemoryKind.PROCEDURAL, "current runbook step walnut", 3, 0.5);
        fresh(agent, MemoryKind.PROCEDURAL, 8);
        assertThat(
                        recall.compose(
                                        new MemoryRecallRequest(
                                                agent,
                                                "alice",
                                                "obsolete runbook step zephyr quokka",
                                                Set.of(),
                                                0,
                                                800))
                                .items())
                .extracting(v -> v.id())
                .as("recallable before the sweep")
                .contains(forgettable);

        consolidation.consolidateTenant();

        assertThat(runColumn(agent, "tombstoned")).isEqualTo(1);
        assertThat(
                        count(
                                "memory_tombstone",
                                "subject_key = ? AND memory_kind = 'item' AND reason = 'DECAY'",
                                "item:" + agent))
                .isEqualTo(1);
        assertThat(status(forgettable))
                .as("the row is not deleted or changed, only hidden")
                .isEqualTo("ACTIVE");
        assertThat(
                        recall.compose(
                                        new MemoryRecallRequest(
                                                agent,
                                                "alice",
                                                "obsolete runbook step zephyr quokka",
                                                Set.of(),
                                                0,
                                                800))
                                .items())
                .extracting(v -> v.id())
                .doesNotContain(forgettable)
                .contains(live);

        StoreMemoryProposalSink.Outcome again =
                sink.proposeDetailed(
                        new MemoryWriteProposal(
                                agent,
                                "alice",
                                MemoryKind.PROCEDURAL,
                                "obsolete runbook step zephyr quokka",
                                MemoryProvenanceRecord.TRUSTED,
                                null,
                                false));
        assertThat(again.accepted()).as("resurrection guard on the unified store").isFalse();
        assertThat(again.refusal()).isEqualTo("FORGOTTEN");

        // The next night does not plan the forgotten row again.
        CLOCK.advance(Duration.ofDays(1));
        consolidation.consolidateTenant();
        assertThat(count("memory_tombstone", "subject_key = ?", "item:" + agent)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ shared tier: recall
    // exclusion + importer guard

    @Test
    @Order(5)
    void aForgottenSharedFactIsNotRecalledAndTheImporterRefusesItUnlessThePersonReconfirms() {
        world();
        doAnswer(i -> embed(i.getArgument(0))).when(sidecar).embedQuery(anyString());
        String text = "Frank likes jazz records";
        jdbc.update(
                "INSERT INTO "
                        + A
                        + ".tenant_memory (id, subject_key, content, embedding, trust) VALUES (?, 'frank', ?,"
                        + " ?::public.vector, 'TRUSTED')",
                UUID.randomUUID(),
                text,
                vector(text));
        assertThat(tenantMemory.recall("frank", text, 10))
                .extracting(TenantMemoryEntry::content)
                .contains(text);

        jdbc.update(
                "INSERT INTO "
                        + A
                        + ".memory_tombstone (id, subject_key, memory_kind, content_sha256, reason, actor, deleted_at)"
                        + " VALUES (?, 'frank', 'shared', encode(sha256(convert_to(?, 'UTF8')), 'hex'), 'DECAY', 'system:memory-consolidation', now())",
                UUID.randomUUID(),
                text);

        assertThat(tenantMemory.recall("frank", text, 10))
                .as("a tombstone makes the fact forgotten")
                .extracting(TenantMemoryEntry::content)
                .doesNotContain(text);

        var blocked =
                importer.importFacts(
                        "frank",
                        new ImportRequest(
                                List.of(new ImportFact(text, "shared", null)), "chatgpt"));
        assertThat(blocked.saved()).isZero();
        assertThat(blocked.skipped()).extracting(s -> s.reason()).containsExactly("forgotten");

        var confirmed =
                importer.importFacts(
                        "frank",
                        new ImportRequest(
                                List.of(new ImportFact(text, "shared", null, true)), "chatgpt"));
        assertThat(confirmed.saved()).isEqualTo(1);
        assertThat(count("memory_tombstone", "subject_key = 'frank'"))
                .as("the person's re-confirmation clears it")
                .isZero();
        assertThat(tenantMemory.recall("frank", text, 10))
                .extracting(TenantMemoryEntry::content)
                .contains(text);
    }
}
