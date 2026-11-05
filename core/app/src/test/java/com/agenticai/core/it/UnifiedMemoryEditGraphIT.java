package com.agenticai.core.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.memory.AgentMemoryService;
import com.agenticai.core.agents.memory.MemoryExtractionChatClients;
import com.agenticai.core.agents.memory.TenantMemoryService;
import com.agenticai.core.agents.memory.spi.MemoryKind;
import com.agenticai.core.agents.memory.spi.MemoryProposalSink;
import com.agenticai.core.agents.memory.spi.MemoryProvenanceRecord;
import com.agenticai.core.agents.memory.spi.MemoryWriteProposal;
import com.agenticai.core.agents.memorystore.MemoryActor;
import com.agenticai.core.agents.memorystore.MemoryEvent;
import com.agenticai.core.agents.memorystore.MemoryItem;
import com.agenticai.core.agents.memorystore.MemoryItemReviewService;
import com.agenticai.core.agents.memorystore.MemoryItemStatus;
import com.agenticai.core.agents.memorystore.StoreMemoryProposalSink;
import com.agenticai.core.graphmemory.GraphFact;
import com.agenticai.core.graphmemory.GraphMemoryService;
import com.agenticai.core.security.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * Unified agent memory (ADR-0028), the review-service edit/pin scenario and the approve-to-graph
 * scenarios (G6) on the same real {@code CoreApplication} and real Postgres as {@link
 * UnifiedMemoryJourneyIT}, which holds the rest of the story. Split out of it to keep both classes
 * small; this class builds its own tenant and agent.
 *
 * <p>Run: {@code -Dvegaduta.it.pg.url=jdbc:postgresql://host:port/postgres} (and Redis, see {@link
 * UnifiedMemoryBootBase}).
 */
@TestPropertySource(
        properties = {
            "app.memory.unified.enabled=true",
            "app.memory.unified.kinds=episodic,procedural,self-model",
            "app.memory.episodic.enabled=true",
            "app.memory.competence.enabled=true",
            "app.memory.consolidation.enabled=true",
            "app.memory.consolidation.auto-apply=true"
        })
@Import({
    UnifiedMemoryBootBase.FakeEmbedderConfiguration.class,
    UnifiedMemoryEditGraphIT.StubExtractionConfiguration.class
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class UnifiedMemoryEditGraphIT extends UnifiedMemoryBootBase {

    static final String A = "tenant_it_umeg_a";
    static final MemoryActor REVIEWER = MemoryActor.human("reviewer-1");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        register(registry, "umeg_on");
    }

    @BeforeAll
    static void needsPostgres() {
        requirePostgres();
    }

    @Autowired MemoryProposalSink sink;
    @Autowired MemoryItemReviewService review;
    @Autowired JdbcTemplate jdbc;
    @Autowired AgentMemoryService agentMemory;
    @Autowired TenantMemoryService tenantMemory;
    @Autowired GraphMemoryService graph;
    @Autowired MeterRegistry meters;

    /**
     * Switch of the stubbed extraction provider below: true = no usable provider (the approval must
     * still work).
     */
    static final AtomicBoolean EXTRACTION_DOWN = new AtomicBoolean();

    /**
     * The only fake on the approve path: a provider whose model answers one fixed triple (the
     * sidecar/LLM is absent).
     */
    @TestConfiguration
    static class StubExtractionConfiguration {
        @Bean
        @Primary
        MemoryExtractionChatClients stubExtractionClients() {
            return () -> {
                if (EXTRACTION_DOWN.get()) {
                    throw new IllegalStateException("no provider");
                }
                ChatClient client =
                        org.mockito.Mockito.mock(
                                ChatClient.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
                org.mockito.Mockito.when(
                                client.prompt()
                                        .system(org.mockito.ArgumentMatchers.anyString())
                                        .user(org.mockito.ArgumentMatchers.anyString())
                                        .call()
                                        .content())
                        .thenReturn(
                                "[{\"subject\":\"Priya\",\"relation\":\"MANAGER_OF_BY\",\"object\":\"Raj\"}]");
                return client;
            };
        }
    }

    private static UUID agentA;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @BeforeEach
    void ensureWorld() {
        if (agentA != null) {
            return;
        }
        tenantSchema(A);
        as(A, "reviewer-1");
        agentA = newAgent("journey-a");
    }

    // ------------------------------------------------------------------ helpers

    private static void as(String schema, String user) {
        TenantContext.set(TenantContext.of(schema, user, Set.of()));
    }

    private int count(String schema, String table, String where, Object... args) {
        Integer n =
                jdbc.queryForObject(
                        "SELECT count(*) FROM "
                                + schema
                                + "."
                                + table
                                + (where.isEmpty() ? "" : " WHERE " + where),
                        Integer.class,
                        args);
        return n == null ? 0 : n;
    }

    private static String vector(String text) {
        float[] v = embed(text);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            sb.append(i == 0 ? "" : ",").append(v[i]);
        }
        return sb.append(']').toString();
    }

    // ------------------------------------------------------------------ 11 edit and pin (WP6b)

    @Test
    @Order(11)
    void editAndPinPersistOnARealDatabaseAndLeaveTextFreeEvents() {
        as(A, "gina");
        UUID id =
                ((StoreMemoryProposalSink) sink)
                        .proposeDetailed(
                                new MemoryWriteProposal(
                                        agentA,
                                        "gina",
                                        MemoryKind.EPISODIC,
                                        "Episode (chat): gina likes tea",
                                        MemoryProvenanceRecord.TRUSTED,
                                        null,
                                        false))
                        .receipt()
                        .id();
        assertThat(review.pin(agentA, id, REVIEWER).refusal())
                .as("only an ACTIVE item can be pinned")
                .isEqualTo("NOT_ACTIVE");
        assertThat(review.approve(agentA, id, REVIEWER).ok()).isTrue();
        assertThat(review.pin(agentA, id, MemoryActor.system("consolidation")).refusal())
                .isEqualTo("NOT_HUMAN");
        assertThat(review.pin(agentA, id, REVIEWER).ok()).isTrue();
        assertThat(review.get(agentA, id).orElseThrow().pinned()).isTrue();
        assertThat(count(A, "memory_item", "id = ? AND pinned", id)).isEqualTo(1);

        UUID next = review.edit(agentA, id, "Episode (chat): gina likes coffee", REVIEWER).itemId();
        MemoryItem v2 = review.get(agentA, next).orElseThrow();
        assertThat(v2.status()).isEqualTo(MemoryItemStatus.PENDING);
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.supersedesId()).isEqualTo(id);
        assertThat(v2.pinned()).as("an edit does not inherit the pin").isFalse();
        assertThat(review.approve(agentA, next, REVIEWER).ok()).isTrue();
        assertThat(review.get(agentA, id).orElseThrow().status())
                .isEqualTo(MemoryItemStatus.SUPERSEDED);

        assertThat(review.unpin(agentA, id, REVIEWER).ok()).isTrue();
        assertThat(count(A, "memory_item", "id = ? AND NOT pinned", id)).isEqualTo(1);
        assertThat(review.events(agentA, id))
                .extracting(MemoryEvent::event)
                .contains(MemoryEvent.Type.PINNED, MemoryEvent.Type.UNPINNED);
        assertThat(
                        count(
                                A,
                                "memory_event",
                                "item_id = ? AND event IN ('PINNED','UNPINNED') AND reason NOT LIKE '%gina%'",
                                id))
                .isEqualTo(2);
    }

    // ------------------------------------------------------------------ 12 approve -> graph (G6)

    private UUID quarantinedAgentFact(String subject, String content) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO "
                        + A
                        + ".agent_memory (id, agent_id, subject_key, content, embedding, trust, created_at,"
                        + " last_used_at, use_count) VALUES (?, ?, ?, ?, ?::public.vector, 'QUARANTINED', now(), now(), 0)",
                id,
                agentA,
                subject,
                content,
                vector(content));
        return id;
    }

    @Test
    @Order(12)
    void approvingAQuarantinedAgentFactWritesItToTheGraph() {
        as(A, "erin");
        EXTRACTION_DOWN.set(false);
        UUID id = quarantinedAgentFact("erin", "Priya's manager is Raj");
        assertThat(graph.recallRelated(agentA, "erin", "Priya", 1))
                .as("quarantined facts never reach the graph")
                .isEmpty();

        assertThat(agentMemory.approve(agentA, id, "erin")).isTrue();

        assertThat(count(A, "agent_memory", "id = ? AND trust = 'TRUSTED'", id)).isEqualTo(1);
        assertThat(graph.recallRelated(agentA, "erin", "Priya", 1))
                .extracting(GraphFact::asSentence)
                .anyMatch(f -> f.contains("Raj"));
    }

    @Test
    @Order(13)
    void approvingAQuarantinedSharedFactWritesItToTheSharedTier() {
        as(A, "erin");
        EXTRACTION_DOWN.set(false);
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO "
                        + A
                        + ".tenant_memory (id, subject_key, content, embedding, created_by_agent_id, trust,"
                        + " created_at, last_used_at, use_count) VALUES (?, 'erin', ?, ?::public.vector, ?, 'QUARANTINED', now(), now(), 0)",
                id,
                "Priya's manager is Raj",
                vector("Priya's manager is Raj"),
                agentA);
        assertThat(graph.recallSharedRelated("erin", "Priya", 1)).isEmpty();

        assertThat(tenantMemory.approveFact(id, null)).as("subject-agnostic approve").isTrue();

        assertThat(count(A, "tenant_memory", "id = ? AND trust = 'TRUSTED'", id)).isEqualTo(1);
        assertThat(graph.recallSharedRelated("erin", "Priya", 1))
                .extracting(GraphFact::asSentence)
                .anyMatch(f -> f.contains("Raj"));
    }

    @Test
    @Order(14)
    void aGraphSideFailureNeverFailsTheApprovalAndIsCounted() {
        as(A, "frank");
        EXTRACTION_DOWN.set(true);
        try {
            double before = failedCount();
            UUID id = quarantinedAgentFact("frank", "Frank reports to Gita");
            assertThat(agentMemory.approve(agentA, id, "frank"))
                    .as("the approval itself succeeds")
                    .isTrue();
            assertThat(count(A, "agent_memory", "id = ? AND trust = 'TRUSTED'", id)).isEqualTo(1);
            assertThat(graph.recallRelated(agentA, "frank", "Frank", 1)).isEmpty();
            assertThat(failedCount()).isEqualTo(before + 1);
        } finally {
            EXTRACTION_DOWN.set(false);
        }
    }

    private double failedCount() {
        var c = meters.find("memory.graph.approve.sync").tag("outcome", "failed").counter();
        return c == null ? 0 : c.count();
    }
}
