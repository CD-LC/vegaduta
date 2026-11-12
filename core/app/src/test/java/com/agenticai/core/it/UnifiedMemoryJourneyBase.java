package com.agenticai.core.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.learning.consolidation.MemoryConsolidationService;
import com.agenticai.core.agents.memory.AgentMemoryEntry;
import com.agenticai.core.agents.memory.AgentMemoryService;
import com.agenticai.core.agents.memory.SidecarMemoryEmbedder;
import com.agenticai.core.agents.memory.SubjectDataEraser;
import com.agenticai.core.agents.memory.spi.CompetenceRecorder;
import com.agenticai.core.agents.memory.spi.MemoryKind;
import com.agenticai.core.agents.memory.spi.MemoryProposalSink;
import com.agenticai.core.agents.memory.spi.MemoryRecallRequest;
import com.agenticai.core.agents.memory.spi.MemoryRecallResult;
import com.agenticai.core.agents.memory.spi.UnifiedMemoryRecall;
import com.agenticai.core.agents.memorystore.MemoryActor;
import com.agenticai.core.agents.memorystore.MemoryItem;
import com.agenticai.core.agents.memorystore.MemoryItemExportController;
import com.agenticai.core.agents.memorystore.MemoryItemReviewService;
import com.agenticai.core.agents.memorystore.MemoryUnifiedProperties;
import com.agenticai.core.agents.skills.EpisodeRecordingGateway;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.guardrails.taint.TurnTaint;
import com.agenticai.core.security.TenantContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.publisher.Flux;

/**
 * Unified agent memory (ADR-0028) end to end with every flag ON: the real {@code CoreApplication}
 * (all beans real: proposal sink, review service, recall composer, self-model source, competence
 * tracker, episode recorder, consolidation, erasure, export) on a real Postgres with the real
 * tenant Flyway. The only fakes are the embedder (deterministic bag-of-words) and the consolidation
 * clock. The scenarios share one booted context and run in order; the story is documented in each
 * test. Flags OFF is the sibling {@link UnifiedMemoryFlagsOffIT}.
 *
 * <p>Run: {@code -Dvegaduta.it.pg.url=jdbc:postgresql://host:port/postgres} (and Redis, see {@link
 * UnifiedMemoryBootBase}).
 */

/**
 * Shared fixture for the unified-memory journey ITs ({@link UnifiedMemoryJourneyIT}, {@link
 * UnifiedMemoryConsolidationIT}): the autowired beans, the two-tenant world and every assertion
 * helper both classes use, so each journey file stays under the file-size cap.
 */
abstract class UnifiedMemoryJourneyBase extends UnifiedMemoryBootBase {

    static final String A = "tenant_it_umj_a";
    static final String B = "tenant_it_umj_b";
    static final MemoryActor REVIEWER = MemoryActor.human("reviewer-1");

    @Autowired MemoryProposalSink sink;
    @Autowired MemoryItemReviewService review;
    @Autowired UnifiedMemoryRecall recall;
    @Autowired CompetenceRecorder competence;
    @Autowired MemoryItemExportController export;
    @Autowired MemoryUnifiedProperties props;
    @Autowired MemoryConsolidationService consolidation;
    @Autowired AgentChatService chat;
    @Autowired SidecarMemoryEmbedder realEmbedder;
    @Autowired JdbcTemplate jdbc;
    @Autowired AgentMemoryService agentMemory;
    @Autowired org.springframework.beans.factory.ObjectProvider<SubjectDataEraser> erasers;

    static UUID agentA;
    static UUID agentA2;
    static UUID agentB;
    static UUID aliceEpisode;
    static UUID bobTainted;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    // ------------------------------------------------------------------ helpers

    static void as(String schema, String user) {
        TenantContext.set(TenantContext.of(schema, user, Set.of()));
    }

    void ensureWorld() {
        if (agentA != null) {
            return;
        }
        tenantSchema(A);
        tenantSchema(B);
        as(A, "reviewer-1");
        agentA = newAgent("journey-a");
        agentA2 = newAgent("journey-a2");
        as(B, "reviewer-1");
        agentB = newAgent("journey-b");
    }

    int count(String schema, String table, String where, Object... args) {
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

    static <T> T await(Supplier<T> probe, java.util.function.Predicate<T> done) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        T value = probe.get();
        while (!done.test(value) && Instant.now().isBefore(deadline)) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            value = probe.get();
        }
        return value;
    }

    /**
     * Runs a finished chat turn through the real episode hook (the same call the chat service wraps
     * its reply in).
     */
    void finishTurn(UUID agent, String user, TurnTaint taint) {
        TenantContext ctx = TenantContext.of(A, user, Set.of());
        Flux<String> turn = Flux.just("an answer the episode never stores");
        EpisodeRecordingGateway.observe(
                        turn,
                        agent,
                        "session-" + UUID.randomUUID(),
                        Instant.now(),
                        Optional.of(ctx),
                        taint)
                .blockLast();
    }

    MemoryItem onlyItem(String subject) {
        List<MemoryItem> items = review.forSubject(subject, 50);
        assertThat(items).as("items about %s", subject).hasSize(1);
        return items.get(0);
    }

    MemoryRecallResult compose(UUID agent, String subject, String query) {
        return recall.compose(new MemoryRecallRequest(agent, subject, query, Set.of(), 0, 800));
    }

    Optional<String> selfModelLine(UUID agent) {
        return compose(agent, "alice", "anything").items().stream()
                .filter(v -> v.kind() == MemoryKind.SELF_MODEL)
                .map(v -> v.text())
                .findFirst();
    }

    List<String> recalled(String subject, String query) {
        return agentMemory.recall(agentA, subject, query, 20).stream()
                .map(AgentMemoryEntry::content)
                .toList();
    }

    void factWith(
            String subject,
            String content,
            int createdDaysAgo,
            int usedDaysAgo,
            boolean pinned,
            boolean person) {
        jdbc.update(
                "INSERT INTO "
                        + A
                        + ".agent_memory (id, agent_id, subject_key, content, embedding, trust, created_at,"
                        + " last_used_at, use_count, pinned, person_authored) VALUES (?, ?, ?, ?, ?::public.vector, 'TRUSTED',"
                        + " now() - make_interval(days => ?), now() - make_interval(days => ?), 0, ?, ?)",
                UUID.randomUUID(),
                agentA,
                subject,
                content,
                vector(content),
                createdDaysAgo,
                usedDaysAgo,
                pinned,
                person);
    }

    /** One legacy fact row (the fact store the consolidation reads), aged by the given days. */
    void fact(String subject, String trust, String content, int createdDaysAgo, int usedDaysAgo) {
        jdbc.update(
                "INSERT INTO "
                        + A
                        + ".agent_memory (id, agent_id, subject_key, content, embedding, trust, created_at,"
                        + " last_used_at, use_count) VALUES (?, ?, ?, ?, ?::public.vector, ?, now() - make_interval(days => ?),"
                        + " now() - make_interval(days => ?), 0)",
                UUID.randomUUID(),
                agentA,
                subject,
                content,
                vector(content),
                trust,
                createdDaysAgo,
                usedDaysAgo);
    }

    static String vector(String text) {
        float[] v = embed(text);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            sb.append(i == 0 ? "" : ",").append(v[i]);
        }
        return sb.append(']').toString();
    }

    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
