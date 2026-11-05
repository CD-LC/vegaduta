package com.agenticai.core.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.agents.memory.SubjectDataEraser;
import com.agenticai.core.agents.memory.spi.MemoryKind;
import com.agenticai.core.agents.memory.spi.MemoryProvenanceRecord;
import com.agenticai.core.agents.memory.spi.MemoryRecallResult;
import com.agenticai.core.agents.memory.spi.MemorySourceRef;
import com.agenticai.core.agents.memory.spi.MemoryTrustLevel;
import com.agenticai.core.agents.memory.spi.MemoryWriteProposal;
import com.agenticai.core.agents.memory.spi.UnifiedMemoryRecall;
import com.agenticai.core.agents.memory.spi.VerifiedOutcome;
import com.agenticai.core.agents.memorystore.MemoryActor;
import com.agenticai.core.agents.memorystore.MemoryEvent;
import com.agenticai.core.agents.memorystore.MemoryItem;
import com.agenticai.core.agents.memorystore.MemoryItemStatus;
import com.agenticai.core.agents.memorystore.StoreMemoryProposalSink;
import com.agenticai.core.agents.skills.EpisodeRecordingGateway;
import com.agenticai.core.agents.turn.JourneyRecallBridge;
import com.agenticai.core.guardrails.taint.TurnTaint;
import com.agenticai.core.knowledge.SidecarClient;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

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
@TestPropertySource(
        properties = {
            "app.memory.unified.enabled=true",
            "app.memory.unified.kinds=episodic,procedural,self-model",
            "app.memory.episodic.enabled=true",
            "app.memory.competence.enabled=true",
            "app.memory.consolidation.enabled=true",
            "app.memory.consolidation.auto-apply=true"
        })
@Import(UnifiedMemoryBootBase.FakeEmbedderConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class UnifiedMemoryJourneyIT extends UnifiedMemoryJourneyBase {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        register(registry, "umj_on");
    }

    @BeforeAll
    static void needsPostgres() {
        requirePostgres();
    }

    @MockitoSpyBean SidecarClient sidecar;

    // ------------------------------------------------------------------ 0 wiring

    @Test
    @Order(0)
    void everyBeanIsTheRealOneAndTheEmbedderFailsClosedWithoutASidecar() {
        ensureWorld();
        assertThat(sink).isInstanceOf(StoreMemoryProposalSink.class);
        assertThat(EpisodeRecordingGateway.installed())
                .as("the episode recorder installed its hook")
                .isTrue();
        assertThat(props.getKinds())
                .containsExactlyInAnyOrder(
                        MemoryKind.EPISODIC, MemoryKind.PROCEDURAL, MemoryKind.SELF_MODEL);
        // The production embedder is the sidecar one; with no sidecar it must refuse, never invent
        // a vector.
        assertThatThrownBy(() -> realEmbedder.embed("anything"))
                .isInstanceOf(RuntimeException.class);
        assertThat(erasers.orderedStream().map(SubjectDataEraser::store)).contains("memory_items");
    }

    // ------------------------------------------------------------------ 1 clean episode

    @Test
    @Order(1)
    void aCleanTurnFilesAPendingProposalAndRecallStaysEmptyUntilAHumanApproves() {
        ensureWorld();
        as(A, "alice");
        finishTurn(agentA, "alice", new TurnTaint());
        assertThat(await(() -> count(A, "memory_item", "agent_id = ?", agentA), n -> n > 0))
                .isEqualTo(1);

        MemoryItem item = onlyItem("alice");
        aliceEpisode = item.id();
        assertThat(item.kind()).isEqualTo(MemoryKind.EPISODIC);
        assertThat(item.status()).isEqualTo(MemoryItemStatus.PENDING);
        assertThat(item.trust())
                .as("a proposal is never trusted by itself")
                .isEqualTo(MemoryTrustLevel.QUARANTINED);
        assertThat(item.sourceTaint()).isEmpty();
        assertThat(item.approvedBy()).isNull();
        assertThat(item.content()).startsWith("Episode (chat)").doesNotContain("answer");
        assertThat(review.events(agentA, aliceEpisode))
                .extracting(MemoryEvent::event)
                .containsExactly(MemoryEvent.Type.CREATED);

        assertThat(compose(agentA, "alice", "chat episode outcome").items())
                .as("pending is never recalled")
                .isEmpty();
    }

    @Test
    @Order(2)
    void aHumanApprovalMakesItActiveAndTrustedAndRecallRendersItInsideTheBudget() {
        as(A, "alice");
        assertThat(review.approve(agentA, aliceEpisode, MemoryActor.agent("a-model")).refusal())
                .as("only an identified human can promote")
                .isEqualTo("NOT_HUMAN");
        assertThat(review.approve(agentA, aliceEpisode, REVIEWER).ok()).isTrue();

        MemoryItem approved = review.get(agentA, aliceEpisode).orElseThrow();
        assertThat(approved.status()).isEqualTo(MemoryItemStatus.ACTIVE);
        assertThat(approved.trust()).isEqualTo(MemoryTrustLevel.TRUSTED);
        assertThat(approved.approvedBy()).isEqualTo("reviewer-1");
        assertThat(review.events(agentA, aliceEpisode))
                .extracting(MemoryEvent::event)
                .containsExactly(MemoryEvent.Type.CREATED, MemoryEvent.Type.APPROVED);

        MemoryRecallResult result = compose(agentA, "alice", "chat episode outcome unknown");
        assertThat(result.items()).extracting(v -> v.id()).contains(aliceEpisode);
        assertThat(result.tokensUsed()).isLessThanOrEqualTo(800);
        assertThat(result.items())
                .allSatisfy(
                        v ->
                                assertThat(v.provenance().trust())
                                        .isEqualTo(MemoryTrustLevel.TRUSTED));

        JourneyRecallBridge.Recalled turn =
                JourneyRecallBridge.recall(chat, agentA, "alice", "what happened last time?");
        assertThat(turn.block())
                .contains("[BEGIN RECALLED NOTES]", "[END RECALLED NOTES]", "Episode (chat)");
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
        assertThat(compose(agentA, "bob", "chat episode outcome").items())
                .as("quarantined: never recalled")
                .isEmpty();
        assertThat(JourneyRecallBridge.recall(chat, agentA, "bob", "chat episode").unified())
                .isEmpty();

        assertThat(review.approve(agentA, bobTainted, REVIEWER).ok()).isTrue();
        MemoryItem flipped = review.get(agentA, bobTainted).orElseThrow();
        assertThat(flipped.status()).isEqualTo(MemoryItemStatus.ACTIVE);
        assertThat(flipped.trust()).isEqualTo(MemoryTrustLevel.TRUSTED);
        assertThat(flipped.sourceTaint())
                .as("approval keeps the provenance")
                .contains("web:evil.example");
        assertThat(compose(agentA, "bob", "chat episode outcome").items())
                .extracting(v -> v.id())
                .containsExactly(bobTainted);
    }

    // ------------------------------------------------------------------ 3 competence

    @Test
    @Order(4)
    void competenceCountersFeedTheSelfModelLineOnlyFromEightVerifiedOutcomes() {
        as(A, "alice");
        UUID agent = agentA2;
        for (int i = 0; i < 7; i++) {
            competence.record(
                    new VerifiedOutcome(
                            agent, "tools:crm_lookup", VerifiedOutcome.Signal.TOOL_ERROR));
        }
        assertThat(count(A, "memory_competence", "agent_id = ?", agent)).isEqualTo(1);
        assertThat(selfModelLine(agent)).as("n=7 is below the minimum sample").isEmpty();

        competence.record(
                new VerifiedOutcome(agent, "tools:crm_lookup", VerifiedOutcome.Signal.TOOL_ERROR));
        assertThat(selfModelLine(agent))
                .hasValueSatisfying(
                        line ->
                                assertThat(line)
                                        .contains("Unreliable at: tools:crm_lookup (n=8")
                                        .contains("not self-reported"));

        // UNKNOWN is counted apart and is never a success; eight clean successes are still below
        // the 0.7 lower bound.
        for (int i = 0; i < 8; i++) {
            competence.record(
                    new VerifiedOutcome(
                            agent, "tools:search", VerifiedOutcome.Signal.TOOL_SUCCESS));
            competence.record(
                    new VerifiedOutcome(agent, "tools:mail", VerifiedOutcome.Signal.UNKNOWN));
        }
        assertThat(selfModelLine(agent).orElseThrow())
                .doesNotContain("tools:search")
                .doesNotContain("tools:mail");
        for (int i = 0; i < 4; i++) {
            competence.record(
                    new VerifiedOutcome(
                            agent, "tools:search", VerifiedOutcome.Signal.TOOL_SUCCESS));
        }
        assertThat(selfModelLine(agent).orElseThrow()).contains("Good at: tools:search (n=12");

        JourneyRecallBridge.Recalled turn =
                JourneyRecallBridge.recall(chat, agent, "alice", "anything");
        assertThat(turn.block()).contains("[self]", "Unreliable at: tools:crm_lookup");
        assertThatThrownBy(
                        () ->
                                new VerifiedOutcome(
                                        agent,
                                        "free text, not a class",
                                        VerifiedOutcome.Signal.TOOL_SUCCESS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ 5 export, erasure

    @Test
    @Order(6)
    void exportListsEveryStatusAboutTheCallerAndNobodyElse() {
        as(A, "alice");
        UUID pendingExtra =
                ((StoreMemoryProposalSink) sink)
                        .proposeDetailed(
                                new MemoryWriteProposal(
                                        agentA2,
                                        "alice",
                                        MemoryKind.EPISODIC,
                                        "Episode (chat): an extra pending alice episode",
                                        MemoryProvenanceRecord.TRUSTED,
                                        null,
                                        false))
                        .receipt()
                        .id();
        Principal alice = () -> "alice";
        var body = export.export(alice, 100).getBody();
        assertThat(body.subject()).isEqualTo("alice");
        assertThat(body.items())
                .extracting(i -> i.id())
                .contains(aliceEpisode, pendingExtra)
                .doesNotContain(bobTainted);
        assertThat(body.items()).extracting(i -> i.status()).contains("ACTIVE", "PENDING");
        assertThat(export.export(() -> "bob", 100).getBody().items())
                .extracting(i -> i.id())
                .containsExactly(bobTainted);
    }

    @Test
    @Order(7)
    void erasingAnItemRemovesItsDerivedSummaryAndLeavesOnlyContentFreeEvents() {
        as(A, "alice");
        StoreMemoryProposalSink store = (StoreMemoryProposalSink) sink;
        UUID derived =
                store.proposeDetailed(
                                new MemoryWriteProposal(
                                        agentA,
                                        "alice",
                                        MemoryKind.EPISODIC,
                                        "Summary: alice had a chat episode",
                                        MemoryProvenanceRecord.TRUSTED,
                                        null,
                                        false,
                                        List.of(MemorySourceRef.item(aliceEpisode))))
                        .receipt()
                        .id();
        assertThat(review.lineage(agentA, derived))
                .extracting(s -> s.sourceKey())
                .containsExactly(aliceEpisode.toString());
        assertThat(review.approve(agentA, derived, REVIEWER).ok()).isTrue();
        assertThat(compose(agentA, "alice", "Summary alice chat episode").items())
                .extracting(v -> v.id())
                .contains(derived, aliceEpisode);

        assertThat(review.erase(agentA, aliceEpisode, REVIEWER).ok()).isTrue();

        assertThat(review.get(agentA, aliceEpisode)).isEmpty();
        assertThat(review.get(agentA, derived))
                .as("the derived summary cannot keep erased content alive")
                .isEmpty();
        assertThat(count(A, "memory_item_source", "item_id = ?", derived)).isZero();
        assertThat(compose(agentA, "alice", "Summary alice chat episode").items())
                .extracting(v -> v.id())
                .doesNotContain(derived, aliceEpisode);
        for (UUID id : List.of(aliceEpisode, derived)) {
            List<String> events =
                    jdbc.queryForList(
                            "SELECT event || ':' || coalesce(reason, '') FROM "
                                    + A
                                    + ".memory_event WHERE item_id = ?",
                            String.class,
                            id);
            assertThat(events).as("the history is gone, one ERASED tombstone remains").hasSize(1);
            assertThat(events.get(0)).startsWith("ERASED:");
        }
    }

    @Test
    @Order(8)
    void erasingASubjectRemovesEveryMemoryItemAboutThemAcrossAgents() {
        as(A, "alice");
        UUID onAnotherAgent =
                ((StoreMemoryProposalSink) sink)
                        .proposeDetailed(
                                new MemoryWriteProposal(
                                        agentA2,
                                        "alice",
                                        MemoryKind.EPISODIC,
                                        "Episode (chat): alice again on agent two",
                                        MemoryProvenanceRecord.TRUSTED,
                                        null,
                                        false))
                        .receipt()
                        .id();
        assertThat(review.approve(agentA2, onAnotherAgent, REVIEWER).ok()).isTrue();
        assertThat(review.forSubject("alice", 50)).isNotEmpty();

        // What DELETE /api/memory (UserMemoryController) and the contact erasure call, for every
        // registered store.
        int erased =
                erasers.orderedStream()
                        .filter(e -> "memory_items".equals(e.store()))
                        .mapToInt(e -> e.eraseSubject("alice"))
                        .sum();

        assertThat(erased)
                .as("the memory-items store is part of subject erasure")
                .isGreaterThanOrEqualTo(2);
        assertThat(review.forSubject("alice", 50)).isEmpty();
        assertThat(review.get(agentA2, onAnotherAgent)).isEmpty();
        assertThat(export.export(() -> "alice", 100).getBody().items()).isEmpty();
        assertThat(compose(agentA2, "alice", "Episode alice").items())
                .extracting(v -> v.kind())
                .doesNotContain(MemoryKind.EPISODIC);
        assertThat(review.get(agentA, bobTainted))
                .as("another person's item is untouched")
                .isPresent();
    }

    // ------------------------------------------------------------------ 6 isolation, kill switch

    @Test
    @Order(9)
    void anotherTenantSeesNothingAndCannotTouchTheFirstTenantsItems() {
        as(B, "alice");
        assertThat(compose(agentB, "bob", "chat episode outcome").items()).isEmpty();
        assertThat(compose(agentA, "bob", "chat episode outcome").items())
                .as("tenant A's agent id, tenant B's schema")
                .isEmpty();
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
            assertThat(
                            ((StoreMemoryProposalSink) sink)
                                    .proposeDetailed(
                                            new MemoryWriteProposal(
                                                    agentA,
                                                    "bob",
                                                    MemoryKind.EPISODIC,
                                                    "Episode (chat): should not be stored",
                                                    MemoryProvenanceRecord.TRUSTED,
                                                    null,
                                                    false))
                                    .refusal())
                    .isEqualTo("DISABLED");
            assertThat(JourneyRecallBridge.recall(chat, agentA, "bob", "chat episode").unified())
                    .isEmpty();
            assertThat(review.get(agentA, bobTainted))
                    .as("data is retained, a person can still review it")
                    .isPresent();
        } finally {
            props.setEnabled(true);
        }
        assertThat(compose(agentA, "bob", "chat episode outcome").items())
                .extracting(v -> v.id())
                .contains(bobTainted);
    }
}
