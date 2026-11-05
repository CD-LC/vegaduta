package com.agenticai.core.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import com.agenticai.core.agents.learning.consolidation.MemoryConsolidationService;
import com.agenticai.core.agents.memory.AgentMemoryEntry;
import com.agenticai.core.agents.memory.AgentMemoryService;
import com.agenticai.core.agents.memory.MemoryExtractionChatClients;
import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.agents.memory.SidecarMemoryEmbedder;
import com.agenticai.core.agents.memory.SubjectDataEraser;
import com.agenticai.core.agents.memory.TenantMemoryService;
import com.agenticai.core.agents.memory.spi.CompetenceRecorder;
import com.agenticai.core.agents.memory.spi.MemoryKind;
import com.agenticai.core.agents.memory.spi.MemoryProposalSink;
import com.agenticai.core.agents.memory.spi.MemoryProvenanceRecord;
import com.agenticai.core.agents.memory.spi.MemoryRecallRequest;
import com.agenticai.core.agents.memory.spi.MemoryRecallResult;
import com.agenticai.core.agents.memory.spi.MemorySourceRef;
import com.agenticai.core.agents.memory.spi.MemoryTrustLevel;
import com.agenticai.core.agents.memory.spi.MemoryWriteProposal;
import com.agenticai.core.agents.memory.spi.UnifiedMemoryRecall;
import com.agenticai.core.agents.memory.spi.VerifiedOutcome;
import com.agenticai.core.agents.memorystore.MemoryActor;
import com.agenticai.core.agents.memorystore.MemoryEvent;
import com.agenticai.core.agents.memorystore.MemoryItem;
import com.agenticai.core.agents.memorystore.MemoryItemExportController;
import com.agenticai.core.agents.memorystore.MemoryItemReviewService;
import com.agenticai.core.agents.memorystore.MemoryItemStatus;
import com.agenticai.core.agents.memorystore.MemoryUnifiedProperties;
import com.agenticai.core.agents.memorystore.StoreMemoryProposalSink;
import com.agenticai.core.agents.skills.EpisodeRecordingGateway;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.JourneyRecallBridge;
import com.agenticai.core.graphmemory.GraphFact;
import com.agenticai.core.graphmemory.GraphMemoryService;
import com.agenticai.core.guardrails.taint.TurnTaint;
import com.agenticai.core.knowledge.SidecarClient;
import com.agenticai.core.security.TenantContext;
import java.security.Principal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Flux;

/**
 * Unified agent memory (ADR-0028) end to end with every flag ON: the real {@code CoreApplication} (all beans real:
 * proposal sink, review service, recall composer, self-model source, competence tracker, episode recorder,
 * consolidation, erasure, export) on a real Postgres with the real tenant Flyway. The only fakes are the embedder
 * (deterministic bag-of-words) and the consolidation clock. The scenarios share one booted context and run in order;
 * the story is documented in each test. Flags OFF is the sibling {@link UnifiedMemoryFlagsOffIT}.
 *
 * <p>Run: {@code -Dvegaduta.it.pg.url=jdbc:postgresql://host:port/postgres} (and Redis, see {@link UnifiedMemoryBootBase}).
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
@Import({UnifiedMemoryBootBase.FakeEmbedderConfiguration.class, UnifiedMemoryJourneyIT.StubExtractionConfiguration.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class UnifiedMemoryJourneyIT extends UnifiedMemoryBootBase {

    static final String A = "tenant_it_umj_a";
    static final String B = "tenant_it_umj_b";
    static final MemoryActor REVIEWER = MemoryActor.human("reviewer-1");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        register(registry, "umj_on");
    }

    @BeforeAll
    static void needsPostgres() {
        requirePostgres();
    }

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
    @Autowired TenantMemoryService tenantMemory;
    @Autowired GraphMemoryService graph;
    @Autowired MeterRegistry meters;
    @MockitoSpyBean SidecarClient sidecar;
    @Autowired org.springframework.beans.factory.ObjectProvider<SubjectDataEraser> erasers;

    /** Switch of the stubbed extraction provider below: true = no usable provider (the approval must still work). */
    static final AtomicBoolean EXTRACTION_DOWN = new AtomicBoolean();

    /** The only fake on the approve path: a provider whose model answers one fixed triple (the sidecar/LLM is absent). */
    @TestConfiguration
    static class StubExtractionConfiguration {
        @Bean
        @Primary
        MemoryExtractionChatClients stubExtractionClients() {
            return () -> {
                if (EXTRACTION_DOWN.get()) {
                    throw new IllegalStateException("no provider");
                }
                ChatClient client = org.mockito.Mockito.mock(ChatClient.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
                org.mockito.Mockito.when(client.prompt().system(org.mockito.ArgumentMatchers.anyString())
                                .user(org.mockito.ArgumentMatchers.anyString()).call().content())
                        .thenReturn("[{\"subject\":\"Priya\",\"relation\":\"MANAGER_OF_BY\",\"object\":\"Raj\"}]");
                return client;
            };
        }
    }

    private static UUID agentA;
    private static UUID agentA2;
    private static UUID agentB;
    private static UUID aliceEpisode;
    private static UUID bobTainted;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    // ------------------------------------------------------------------ helpers

    private static void as(String schema, String user) {
        TenantContext.set(TenantContext.of(schema, user, Set.of()));
    }

    private void ensureWorld() {
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

    private int count(String schema, String table, String where, Object... args) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM " + schema + "." + table + (where.isEmpty() ? "" : " WHERE " + where),
                Integer.class, args);
        return n == null ? 0 : n;
    }

    private static <T> T await(Supplier<T> probe, java.util.function.Predicate<T> done) {
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

    /** Runs a finished chat turn through the real episode hook (the same call the chat service wraps its reply in). */
    private void finishTurn(UUID agent, String user, TurnTaint taint) {
        TenantContext ctx = TenantContext.of(A, user, Set.of());
        Flux<String> turn = Flux.just("an answer the episode never stores");
        EpisodeRecordingGateway.observe(turn, agent, "session-" + UUID.randomUUID(), Instant.now(), Optional.of(ctx), taint)
                .blockLast();
    }

    private MemoryItem onlyItem(String subject) {
        List<MemoryItem> items = review.forSubject(subject, 50);
        assertThat(items).as("items about %s", subject).hasSize(1);
        return items.get(0);
    }

    private MemoryRecallResult compose(UUID agent, String subject, String query) {
        return recall.compose(new MemoryRecallRequest(agent, subject, query, Set.of(), 0, 800));
    }

    // ------------------------------------------------------------------ 0 wiring

    @Test
    @Order(0)
    void everyBeanIsTheRealOneAndTheEmbedderFailsClosedWithoutASidecar() {
        ensureWorld();
        assertThat(sink).isInstanceOf(StoreMemoryProposalSink.class);
        assertThat(EpisodeRecordingGateway.installed()).as("the episode recorder installed its hook").isTrue();
        assertThat(props.getKinds()).containsExactlyInAnyOrder(MemoryKind.EPISODIC, MemoryKind.PROCEDURAL, MemoryKind.SELF_MODEL);
        // The production embedder is the sidecar one; with no sidecar it must refuse, never invent a vector.
        assertThatThrownBy(() -> realEmbedder.embed("anything")).isInstanceOf(RuntimeException.class);
        assertThat(erasers.orderedStream().map(SubjectDataEraser::store)).contains("memory_items");
    }

    // ------------------------------------------------------------------ 1 clean episode

    @Test
    @Order(1)
    void aCleanTurnFilesAPendingProposalAndRecallStaysEmptyUntilAHumanApproves() {
        ensureWorld();
        as(A, "alice");
        finishTurn(agentA, "alice", new TurnTaint());
        assertThat(await(() -> count(A, "memory_item", "agent_id = ?", agentA), n -> n > 0)).isEqualTo(1);

        MemoryItem item = onlyItem("alice");
        aliceEpisode = item.id();
        assertThat(item.kind()).isEqualTo(MemoryKind.EPISODIC);
        assertThat(item.status()).isEqualTo(MemoryItemStatus.PENDING);
        assertThat(item.trust()).as("a proposal is never trusted by itself").isEqualTo(MemoryTrustLevel.QUARANTINED);
        assertThat(item.sourceTaint()).isEmpty();
        assertThat(item.approvedBy()).isNull();
        assertThat(item.content()).startsWith("Episode (chat)").doesNotContain("answer");
        assertThat(review.events(agentA, aliceEpisode)).extracting(MemoryEvent::event).containsExactly(MemoryEvent.Type.CREATED);

        assertThat(compose(agentA, "alice", "chat episode outcome").items()).as("pending is never recalled").isEmpty();
    }

    @Test
    @Order(2)
    void aHumanApprovalMakesItActiveAndTrustedAndRecallRendersItInsideTheBudget() {
        as(A, "alice");
        assertThat(review.approve(agentA, aliceEpisode, MemoryActor.agent("a-model")).refusal())
                .as("only an identified human can promote").isEqualTo("NOT_HUMAN");
        assertThat(review.approve(agentA, aliceEpisode, REVIEWER).ok()).isTrue();

        MemoryItem approved = review.get(agentA, aliceEpisode).orElseThrow();
        assertThat(approved.status()).isEqualTo(MemoryItemStatus.ACTIVE);
        assertThat(approved.trust()).isEqualTo(MemoryTrustLevel.TRUSTED);
        assertThat(approved.approvedBy()).isEqualTo("reviewer-1");
        assertThat(review.events(agentA, aliceEpisode)).extracting(MemoryEvent::event)
                .containsExactly(MemoryEvent.Type.CREATED, MemoryEvent.Type.APPROVED);

        MemoryRecallResult result = compose(agentA, "alice", "chat episode outcome unknown");
        assertThat(result.items()).extracting(v -> v.id()).contains(aliceEpisode);
        assertThat(result.tokensUsed()).isLessThanOrEqualTo(800);
        assertThat(result.items()).allSatisfy(v -> assertThat(v.provenance().trust()).isEqualTo(MemoryTrustLevel.TRUSTED));

        JourneyRecallBridge.Recalled turn = JourneyRecallBridge.recall(chat, agentA, "alice", "what happened last time?");
        assertThat(turn.block()).contains("[BEGIN RECALLED NOTES]", "[END RECALLED NOTES]", "Episode (chat)");
        assertThat(turn.unified()).extracting(u -> u.id()).contains(aliceEpisode);
        assertThat(SessionMemoryService.estimateTokens(turn.block())).isLessThanOrEqualTo(800);

        // Agent- and subject-scoping: another agent, or another person, never sees alice's episode.
        assertThat(compose(agentA2, "alice", "chat episode outcome").items()).isEmpty();
        assertThat(compose(agentA, "mallory", "chat episode outcome").items()).isEmpty();
    }

    // ------------------------------------------------------------------ 2 tainted episode

    @Test
    @Order(3)
    void aTaintedTurnIsQuarantinedNeverRecalledAndApprovalFlipsItKeepingTheTaint() {
        as(A, "bob");
        TurnTaint taint = new TurnTaint();
        taint.mark("web:evil.example");
        finishTurn(agentA, "bob", taint);
        assertThat(await(() -> review.forSubject("bob", 5), l -> !l.isEmpty())).hasSize(1);

        MemoryItem item = onlyItem("bob");
        bobTainted = item.id();
        assertThat(item.status()).isEqualTo(MemoryItemStatus.PENDING);
        assertThat(item.trust()).isEqualTo(MemoryTrustLevel.QUARANTINED);
        assertThat(item.sourceTaint()).contains("web:evil.example");
        assertThat(compose(agentA, "bob", "chat episode outcome").items()).as("quarantined: never recalled").isEmpty();
        assertThat(JourneyRecallBridge.recall(chat, agentA, "bob", "chat episode").unified()).isEmpty();

        assertThat(review.approve(agentA, bobTainted, REVIEWER).ok()).isTrue();
        MemoryItem flipped = review.get(agentA, bobTainted).orElseThrow();
        assertThat(flipped.status()).isEqualTo(MemoryItemStatus.ACTIVE);
        assertThat(flipped.trust()).isEqualTo(MemoryTrustLevel.TRUSTED);
        assertThat(flipped.sourceTaint()).as("approval keeps the provenance").contains("web:evil.example");
        assertThat(compose(agentA, "bob", "chat episode outcome").items()).extracting(v -> v.id()).containsExactly(bobTainted);
    }

    // ------------------------------------------------------------------ 3 competence

    @Test
    @Order(4)
    void competenceCountersFeedTheSelfModelLineOnlyFromEightVerifiedOutcomes() {
        as(A, "alice");
        UUID agent = agentA2;
        for (int i = 0; i < 7; i++) {
            competence.record(new VerifiedOutcome(agent, "tools:crm_lookup", VerifiedOutcome.Signal.TOOL_ERROR));
        }
        assertThat(count(A, "memory_competence", "agent_id = ?", agent)).isEqualTo(1);
        assertThat(selfModelLine(agent)).as("n=7 is below the minimum sample").isEmpty();

        competence.record(new VerifiedOutcome(agent, "tools:crm_lookup", VerifiedOutcome.Signal.TOOL_ERROR));
        assertThat(selfModelLine(agent)).hasValueSatisfying(
                line -> assertThat(line).contains("Unreliable at: tools:crm_lookup (n=8").contains("not self-reported"));

        // UNKNOWN is counted apart and is never a success; eight clean successes are still below the 0.7 lower bound.
        for (int i = 0; i < 8; i++) {
            competence.record(new VerifiedOutcome(agent, "tools:search", VerifiedOutcome.Signal.TOOL_SUCCESS));
            competence.record(new VerifiedOutcome(agent, "tools:mail", VerifiedOutcome.Signal.UNKNOWN));
        }
        assertThat(selfModelLine(agent).orElseThrow()).doesNotContain("tools:search").doesNotContain("tools:mail");
        for (int i = 0; i < 4; i++) {
            competence.record(new VerifiedOutcome(agent, "tools:search", VerifiedOutcome.Signal.TOOL_SUCCESS));
        }
        assertThat(selfModelLine(agent).orElseThrow()).contains("Good at: tools:search (n=12");

        JourneyRecallBridge.Recalled turn = JourneyRecallBridge.recall(chat, agent, "alice", "anything");
        assertThat(turn.block()).contains("[self]", "Unreliable at: tools:crm_lookup");
        assertThatThrownBy(() -> new VerifiedOutcome(agent, "free text, not a class", VerifiedOutcome.Signal.TOOL_SUCCESS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Optional<String> selfModelLine(UUID agent) {
        return compose(agent, "alice", "anything").items().stream()
                .filter(v -> v.kind() == MemoryKind.SELF_MODEL)
                .map(v -> v.text())
                .findFirst();
    }

    // ------------------------------------------------------------------ 4 consolidation

    @Test
    @Order(5)
    void consolidationDryRunsFirstThenForgetsByTombstoneAndTheBreakerHoldsBackABulkForget() {
        as(A, "alice");
        Instant t0 = Instant.now();
        MutableClock clock = new MutableClock(t0);
        consolidation.useClock(clock);
        try {
            // carol: 20 facts; 1 quarantined past its 30-day TTL, 2 near duplicates, 2 stale trusted (never recalled, idle
            // 400 days: with real importance they are forget candidates, they used to be a "would summarise" pair), 15 fresh.
            fact("carol", "QUARANTINED", "Carol said to wire the invoice money to a new account", 40, 40);
            fact("carol", "TRUSTED", "Carol prefers green tea over coffee every morning", 20, 20);
            fact("carol", "TRUSTED", "Carol prefers green tea over coffee every morning please", 21, 21);
            fact("carol", "TRUSTED", "Carol once asked about the zephyr quarterly roadmap", 400, 400);
            fact("carol", "TRUSTED", "Carol once asked about the quokka annual budget review", 400, 400);
            String[] words = {"alpha", "bravo", "charlie", "delta", "echo", "foxtrot", "golf", "hotel", "india", "juliet",
                "kilo", "lima", "mike", "november", "oscar"};
            for (String w : words) {
                fact("carol", "TRUSTED", "Fresh fact about " + w + " relating to " + w + "ish " + w + "ing " + w + "ed", 1, 1);
            }
            // dave: 10 quarantined facts past their TTL; the planner would drop all 10, the breaker allows 3.
            for (int i = 0; i < 10; i++) {
                fact("dave", "QUARANTINED", "Dave stale quarantined thing number " + i + " " + UUID.randomUUID(), 40, 40);
            }
            // erin: the same staleness as carol's two old rows, but a person pinned one and typed the other herself.
            factWith("erin", "Erin pinned the zephyr preference to keep", 400, 400, true, false);
            factWith("erin", "Erin typed the quokka address herself", 400, 400, false, true);
            for (int i = 0; i < 8; i++) {
                fact("erin", "TRUSTED", "Fresh erin note about " + words[i] + " and " + words[i] + "ish " + words[i] + "ing", 1, 1);
            }

            MemoryConsolidationService.Report dry = consolidation.consolidateTenant();
            assertThat(dry.subjects()).as("carol, dave, erin, plus the agents' memory items").isGreaterThanOrEqualTo(3);
            assertThat(dry.tombstoned()).as("the first run is a dry run, whatever auto-apply says").isZero();
            assertThat(count(A, "memory_tombstone", "")).isZero();
            assertThat(dry.forgets()).as("carol 3 (TTL + two stale trusted) + dave 3 (breaker cap) + erin 0 (pinned/typed)")
                    .isEqualTo(6);
            assertThat(dry.merges()).as("one near-duplicate would be merged").isEqualTo(1);
            assertThat(dry.summaries()).as("nothing is left in the summarise band").isZero();
            assertThat(count(A, "memory_consolidation_runs", "mode = 'dry_run' AND subject IN ('carol', 'dave', 'erin')"))
                    .isEqualTo(3);

            clock.advance(Duration.ofDays(8)); // past app.memory.decay.dry-run-days (7)
            MemoryConsolidationService.Report applied = consolidation.consolidateTenant();
            assertThat(applied.tombstoned())
                    .as("carol: the TTL-expired row, the two stale trusted rows and the merged duplicate; dave held back")
                    .isEqualTo(4);
            assertThat(count(A, "memory_tombstone", "subject_key = 'carol' AND reason = 'QUARANTINE_TTL'")).isEqualTo(1);
            assertThat(count(A, "memory_tombstone", "subject_key = 'carol' AND reason = 'DECAY' AND trust_at_delete = 'TRUSTED'"))
                    .as("TRUSTED facts can now reach the forget band (behind auto-apply)").isEqualTo(2);
            assertThat(count(A, "memory_tombstone", "subject_key = 'carol' AND reason LIKE 'MERGED_INTO:%'")).isEqualTo(1);
            assertThat(count(A, "memory_tombstone", "subject_key = 'dave'")).as("breaker tripped: nothing applied").isZero();
            assertThat(count(A, "memory_tombstone", "subject_key = 'erin'")).as("pinned and person-authored never").isZero();
            assertThat(count(A, "memory_consolidation_runs", "subject = 'dave' AND mode = 'apply'")).isZero();
            assertThat(count(A, "memory_consolidation_runs", "subject = 'carol' AND mode = 'apply'")).isEqualTo(1);
            // A tombstone is a hash-only marker; it never rewrites or deletes the fact row, and consolidating legacy
            // facts files no memory_item proposal.
            assertThat(count(A, "agent_memory", "subject_key = 'carol'")).isEqualTo(20);
            assertThat(count(A, "memory_tombstone", "content_sha256 IS NULL OR length(content_sha256) <> 64")).isZero();
            assertThat(count(A, "memory_item", "'carol' = ANY(subject_keys)")).isZero();

            // Tombstone forgetting is no longer inert: the forgotten facts are not recalled, the live ones are.
            doAnswer(i -> embed(i.getArgument(0))).when(sidecar).embedQuery(anyString());
            List<String> zephyr = recalled("carol", "Carol once asked about the zephyr quarterly roadmap");
            assertThat(zephyr).as("forgotten (tombstoned) trusted facts are not recalled").noneMatch(c -> c.contains("zephyr"))
                    .noneMatch(c -> c.contains("quokka"));
            assertThat(recalled("carol", "Carol prefers green tea over coffee every morning"))
                    .as("the kept duplicate is still recalled; the merged-away twin is not")
                    .contains("Carol prefers green tea over coffee every morning")
                    .doesNotContain("Carol prefers green tea over coffee every morning please");
            assertThat(recalled("carol", "Fresh fact about alpha relating to alphaish alphaing alphaed"))
                    .contains("Fresh fact about alpha relating to alphaish alphaing alphaed");
            assertThat(recalled("erin", "Erin pinned the zephyr preference to keep")).as("untouched facts stay recallable")
                    .contains("Erin pinned the zephyr preference to keep");

            // The resurrection guard: an automated writer cannot bring a forgotten fact back ...
            assertThatThrownBy(() -> agentMemory.remember(agentA, "carol", "Carol once asked about the zephyr quarterly roadmap",
                            com.agenticai.core.agents.memory.MemoryProvenance.TRUSTED))
                    .isInstanceOf(com.agenticai.core.agents.memory.MemoryForgottenException.class);
            assertThat(count(A, "agent_memory", "subject_key = 'carol'")).isEqualTo(20);
            // ... a fact that was never forgotten is unaffected ...
            agentMemory.remember(agentA, "carol", "Carol likes to hike on weekends", com.agenticai.core.agents.memory.MemoryProvenance.TRUSTED);
            assertThat(count(A, "agent_memory", "subject_key = 'carol'")).isEqualTo(21);
            // ... and a person typing it again on purpose clears the tombstone and it is recalled again.
            agentMemory.rememberOwn(agentA, "carol", "Carol once asked about the zephyr quarterly roadmap", 25);
            assertThat(count(A, "memory_tombstone", "subject_key = 'carol' AND original_id IS NOT NULL"))
                    .as("the zephyr tombstone is cleared by the person's re-confirmation").isEqualTo(3);
            assertThat(recalled("carol", "Carol once asked about the zephyr quarterly roadmap"))
                    .as("a person re-adding it brings it back (the old row has the same text, so two copies)")
                    .contains("Carol once asked about the zephyr quarterly roadmap");
            assertThat(count(A, "agent_memory", "subject_key = 'carol' AND person_authored AND abs(importance - 0.8) < 0.001")).isEqualTo(1);

            // The next night does not plan the forgotten rows again: no duplicate tombstones.
            int tombstonesBefore = count(A, "memory_tombstone", "subject_key = 'carol'");
            clock.advance(Duration.ofDays(1));
            consolidation.consolidateTenant();
            assertThat(count(A, "memory_tombstone", "subject_key = 'carol'")).isEqualTo(tombstonesBefore);
        } finally {
            consolidation.useClock(Clock.systemUTC());
        }
    }

    private List<String> recalled(String subject, String query) {
        return agentMemory.recall(agentA, subject, query, 20).stream().map(AgentMemoryEntry::content).toList();
    }

    private void factWith(String subject, String content, int createdDaysAgo, int usedDaysAgo, boolean pinned, boolean person) {
        jdbc.update("INSERT INTO " + A + ".agent_memory (id, agent_id, subject_key, content, embedding, trust, created_at,"
                + " last_used_at, use_count, pinned, person_authored) VALUES (?, ?, ?, ?, ?::public.vector, 'TRUSTED',"
                + " now() - make_interval(days => ?), now() - make_interval(days => ?), 0, ?, ?)",
                UUID.randomUUID(), agentA, subject, content, vector(content), createdDaysAgo, usedDaysAgo, pinned, person);
    }

    /** One legacy fact row (the fact store the consolidation reads), aged by the given days. */
    private void fact(String subject, String trust, String content, int createdDaysAgo, int usedDaysAgo) {
        jdbc.update("INSERT INTO " + A + ".agent_memory (id, agent_id, subject_key, content, embedding, trust, created_at,"
                + " last_used_at, use_count) VALUES (?, ?, ?, ?, ?::public.vector, ?, now() - make_interval(days => ?),"
                + " now() - make_interval(days => ?), 0)",
                UUID.randomUUID(), agentA, subject, content, vector(content), trust, createdDaysAgo, usedDaysAgo);
    }

    private static String vector(String text) {
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

    // ------------------------------------------------------------------ 5 export, erasure

    @Test
    @Order(6)
    void exportListsEveryStatusAboutTheCallerAndNobodyElse() {
        as(A, "alice");
        UUID pendingExtra = ((StoreMemoryProposalSink) sink).proposeDetailed(new MemoryWriteProposal(
                agentA2, "alice", MemoryKind.EPISODIC, "Episode (chat): an extra pending alice episode",
                MemoryProvenanceRecord.TRUSTED, null, false)).receipt().id();
        Principal alice = () -> "alice";
        var body = export.export(alice, 100).getBody();
        assertThat(body.subject()).isEqualTo("alice");
        assertThat(body.items()).extracting(i -> i.id()).contains(aliceEpisode, pendingExtra).doesNotContain(bobTainted);
        assertThat(body.items()).extracting(i -> i.status()).contains("ACTIVE", "PENDING");
        assertThat(export.export(() -> "bob", 100).getBody().items()).extracting(i -> i.id()).containsExactly(bobTainted);
    }

    @Test
    @Order(7)
    void erasingAnItemRemovesItsDerivedSummaryAndLeavesOnlyContentFreeEvents() {
        as(A, "alice");
        StoreMemoryProposalSink store = (StoreMemoryProposalSink) sink;
        UUID derived = store.proposeDetailed(new MemoryWriteProposal(agentA, "alice", MemoryKind.EPISODIC,
                "Summary: alice had a chat episode", MemoryProvenanceRecord.TRUSTED, null, false,
                List.of(MemorySourceRef.item(aliceEpisode)))).receipt().id();
        assertThat(review.lineage(agentA, derived)).extracting(s -> s.sourceKey()).containsExactly(aliceEpisode.toString());
        assertThat(review.approve(agentA, derived, REVIEWER).ok()).isTrue();
        assertThat(compose(agentA, "alice", "Summary alice chat episode").items()).extracting(v -> v.id())
                .contains(derived, aliceEpisode);

        assertThat(review.erase(agentA, aliceEpisode, REVIEWER).ok()).isTrue();

        assertThat(review.get(agentA, aliceEpisode)).isEmpty();
        assertThat(review.get(agentA, derived)).as("the derived summary cannot keep erased content alive").isEmpty();
        assertThat(count(A, "memory_item_source", "item_id = ?", derived)).isZero();
        assertThat(compose(agentA, "alice", "Summary alice chat episode").items()).extracting(v -> v.id())
                .doesNotContain(derived, aliceEpisode);
        for (UUID id : List.of(aliceEpisode, derived)) {
            List<String> events = jdbc.queryForList("SELECT event || ':' || coalesce(reason, '') FROM " + A
                    + ".memory_event WHERE item_id = ?", String.class, id);
            assertThat(events).as("the history is gone, one ERASED tombstone remains").hasSize(1);
            assertThat(events.get(0)).startsWith("ERASED:");
        }
    }

    @Test
    @Order(8)
    void erasingASubjectRemovesEveryMemoryItemAboutThemAcrossAgents() {
        as(A, "alice");
        UUID onAnotherAgent = ((StoreMemoryProposalSink) sink).proposeDetailed(new MemoryWriteProposal(
                agentA2, "alice", MemoryKind.EPISODIC, "Episode (chat): alice again on agent two",
                MemoryProvenanceRecord.TRUSTED, null, false)).receipt().id();
        assertThat(review.approve(agentA2, onAnotherAgent, REVIEWER).ok()).isTrue();
        assertThat(review.forSubject("alice", 50)).isNotEmpty();

        // What DELETE /api/memory (UserMemoryController) and the contact erasure call, for every registered store.
        int erased = erasers.orderedStream().filter(e -> "memory_items".equals(e.store()))
                .mapToInt(e -> e.eraseSubject("alice")).sum();

        assertThat(erased).as("the memory-items store is part of subject erasure").isGreaterThanOrEqualTo(2);
        assertThat(review.forSubject("alice", 50)).isEmpty();
        assertThat(review.get(agentA2, onAnotherAgent)).isEmpty();
        assertThat(export.export(() -> "alice", 100).getBody().items()).isEmpty();
        assertThat(compose(agentA2, "alice", "Episode alice").items()).extracting(v -> v.kind())
                .doesNotContain(MemoryKind.EPISODIC);
        assertThat(review.get(agentA, bobTainted)).as("another person's item is untouched").isPresent();
    }

    // ------------------------------------------------------------------ 6 isolation, kill switch

    @Test
    @Order(9)
    void anotherTenantSeesNothingAndCannotTouchTheFirstTenantsItems() {
        as(B, "alice");
        assertThat(compose(agentB, "bob", "chat episode outcome").items()).isEmpty();
        assertThat(compose(agentA, "bob", "chat episode outcome").items()).as("tenant A's agent id, tenant B's schema").isEmpty();
        assertThat(review.get(agentA, bobTainted)).isEmpty();
        assertThat(review.forSubject("bob", 50)).isEmpty();
        assertThat(review.approve(agentA, bobTainted, REVIEWER).refusal()).isEqualTo("NOT_FOUND");
        assertThat(review.erase(agentA, bobTainted, REVIEWER).refusal()).isEqualTo("NOT_FOUND");
        assertThat(export.export(() -> "bob", 100).getBody().items()).isEmpty();
        assertThat(count(B, "memory_item", "")).isZero();
        assertThat(count(B, "memory_event", "")).isZero();
        assertThat(count(B, "memory_competence", "")).isZero();
        as(A, "alice");
        assertThat(review.get(agentA, bobTainted)).as("tenant A still has it").isPresent();
    }

    @Test
    @Order(10)
    void thePlatformKillSwitchStopsRecallAndProposalsAtOnceButNotReviewOrErasure() {
        as(A, "bob");
        props.setEnabled(false);
        try {
            MemoryRecallResult off = compose(agentA, "bob", "chat episode outcome");
            assertThat(off.items()).isEmpty();
            assertThat(off.degradedReason()).isEqualTo(UnifiedMemoryRecall.DISABLED);
            assertThat(((StoreMemoryProposalSink) sink).proposeDetailed(new MemoryWriteProposal(agentA, "bob",
                    MemoryKind.EPISODIC, "Episode (chat): should not be stored", MemoryProvenanceRecord.TRUSTED, null, false))
                    .refusal()).isEqualTo("DISABLED");
            assertThat(JourneyRecallBridge.recall(chat, agentA, "bob", "chat episode").unified()).isEmpty();
            assertThat(review.get(agentA, bobTainted)).as("data is retained, a person can still review it").isPresent();
        } finally {
            props.setEnabled(true);
        }
        assertThat(compose(agentA, "bob", "chat episode outcome").items()).extracting(v -> v.id()).contains(bobTainted);
    }

    // ------------------------------------------------------------------ 11 edit and pin (WP6b)

    @Test
    @Order(11)
    void editAndPinPersistOnARealDatabaseAndLeaveTextFreeEvents() {
        as(A, "gina");
        UUID id = ((StoreMemoryProposalSink) sink).proposeDetailed(new MemoryWriteProposal(agentA, "gina",
                MemoryKind.EPISODIC, "Episode (chat): gina likes tea", MemoryProvenanceRecord.TRUSTED, null, false))
                .receipt().id();
        assertThat(review.pin(agentA, id, REVIEWER).refusal()).as("only an ACTIVE item can be pinned").isEqualTo("NOT_ACTIVE");
        assertThat(review.approve(agentA, id, REVIEWER).ok()).isTrue();
        assertThat(review.pin(agentA, id, MemoryActor.system("consolidation")).refusal()).isEqualTo("NOT_HUMAN");
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
        assertThat(review.get(agentA, id).orElseThrow().status()).isEqualTo(MemoryItemStatus.SUPERSEDED);

        assertThat(review.unpin(agentA, id, REVIEWER).ok()).isTrue();
        assertThat(count(A, "memory_item", "id = ? AND NOT pinned", id)).isEqualTo(1);
        assertThat(review.events(agentA, id)).extracting(MemoryEvent::event)
                .contains(MemoryEvent.Type.PINNED, MemoryEvent.Type.UNPINNED);
        assertThat(count(A, "memory_event", "item_id = ? AND event IN ('PINNED','UNPINNED') AND reason NOT LIKE '%gina%'", id))
                .isEqualTo(2);
    }

    // ------------------------------------------------------------------ 12 approve -> graph (G6)

    private UUID quarantinedAgentFact(String subject, String content) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO " + A + ".agent_memory (id, agent_id, subject_key, content, embedding, trust, created_at,"
                + " last_used_at, use_count) VALUES (?, ?, ?, ?, ?::public.vector, 'QUARANTINED', now(), now(), 0)",
                id, agentA, subject, content, vector(content));
        return id;
    }

    @Test
    @Order(12)
    void approvingAQuarantinedAgentFactWritesItToTheGraph() {
        as(A, "erin");
        EXTRACTION_DOWN.set(false);
        UUID id = quarantinedAgentFact("erin", "Priya's manager is Raj");
        assertThat(graph.recallRelated(agentA, "erin", "Priya", 1)).as("quarantined facts never reach the graph").isEmpty();

        assertThat(agentMemory.approve(agentA, id, "erin")).isTrue();

        assertThat(count(A, "agent_memory", "id = ? AND trust = 'TRUSTED'", id)).isEqualTo(1);
        assertThat(graph.recallRelated(agentA, "erin", "Priya", 1)).extracting(GraphFact::asSentence)
                .anyMatch(f -> f.contains("Raj"));
    }

    @Test
    @Order(13)
    void approvingAQuarantinedSharedFactWritesItToTheSharedTier() {
        as(A, "erin");
        EXTRACTION_DOWN.set(false);
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO " + A + ".tenant_memory (id, subject_key, content, embedding, created_by_agent_id, trust,"
                + " created_at, last_used_at, use_count) VALUES (?, 'erin', ?, ?::public.vector, ?, 'QUARANTINED', now(), now(), 0)",
                id, "Priya's manager is Raj", vector("Priya's manager is Raj"), agentA);
        assertThat(graph.recallSharedRelated("erin", "Priya", 1)).isEmpty();

        assertThat(tenantMemory.approveFact(id, null)).as("subject-agnostic approve").isTrue();

        assertThat(count(A, "tenant_memory", "id = ? AND trust = 'TRUSTED'", id)).isEqualTo(1);
        assertThat(graph.recallSharedRelated("erin", "Priya", 1)).extracting(GraphFact::asSentence)
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
            assertThat(agentMemory.approve(agentA, id, "frank")).as("the approval itself succeeds").isTrue();
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
