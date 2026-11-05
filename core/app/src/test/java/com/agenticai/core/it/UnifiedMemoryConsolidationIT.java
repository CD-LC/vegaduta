package com.agenticai.core.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import com.agenticai.core.agents.learning.consolidation.MemoryConsolidationService;
import com.agenticai.core.knowledge.SidecarClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
class UnifiedMemoryConsolidationIT extends UnifiedMemoryJourneyBase {

    @MockitoSpyBean SidecarClient sidecar;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        register(registry, "umj_consolidation");
    }

    @BeforeAll
    static void needsPostgres() {
        requirePostgres();
    }

    // ------------------------------------------------------------------ 4 consolidation

    @Test
    @Order(5)
    void consolidationDryRunsFirstThenForgetsByTombstoneAndTheBreakerHoldsBackABulkForget() {
        ensureWorld();
        as(A, "alice");
        Instant t0 = Instant.now();
        MutableClock clock = new MutableClock(t0);
        consolidation.useClock(clock);
        try {
            // carol: 20 facts; 1 quarantined past its 30-day TTL, 2 near duplicates, 2 stale
            // trusted (never recalled, idle
            // 400 days: with real importance they are forget candidates, they used to be a "would
            // summarise" pair), 15 fresh.
            fact(
                    "carol",
                    "QUARANTINED",
                    "Carol said to wire the invoice money to a new account",
                    40,
                    40);
            fact("carol", "TRUSTED", "Carol prefers green tea over coffee every morning", 20, 20);
            fact(
                    "carol",
                    "TRUSTED",
                    "Carol prefers green tea over coffee every morning please",
                    21,
                    21);
            fact(
                    "carol",
                    "TRUSTED",
                    "Carol once asked about the zephyr quarterly roadmap",
                    400,
                    400);
            fact(
                    "carol",
                    "TRUSTED",
                    "Carol once asked about the quokka annual budget review",
                    400,
                    400);
            String[] words = {
                "alpha",
                "bravo",
                "charlie",
                "delta",
                "echo",
                "foxtrot",
                "golf",
                "hotel",
                "india",
                "juliet",
                "kilo",
                "lima",
                "mike",
                "november",
                "oscar"
            };
            for (String w : words) {
                fact(
                        "carol",
                        "TRUSTED",
                        "Fresh fact about "
                                + w
                                + " relating to "
                                + w
                                + "ish "
                                + w
                                + "ing "
                                + w
                                + "ed",
                        1,
                        1);
            }
            // dave: 10 quarantined facts past their TTL; the planner would drop all 10, the breaker
            // allows 3.
            for (int i = 0; i < 10; i++) {
                fact(
                        "dave",
                        "QUARANTINED",
                        "Dave stale quarantined thing number " + i + " " + UUID.randomUUID(),
                        40,
                        40);
            }
            // erin: the same staleness as carol's two old rows, but a person pinned one and typed
            // the other herself.
            factWith("erin", "Erin pinned the zephyr preference to keep", 400, 400, true, false);
            factWith("erin", "Erin typed the quokka address herself", 400, 400, false, true);
            for (int i = 0; i < 8; i++) {
                fact(
                        "erin",
                        "TRUSTED",
                        "Fresh erin note about "
                                + words[i]
                                + " and "
                                + words[i]
                                + "ish "
                                + words[i]
                                + "ing",
                        1,
                        1);
            }

            MemoryConsolidationService.Report dry = consolidation.consolidateTenant();
            assertThat(dry.subjects())
                    .as("carol, dave, erin, plus the agents' memory items")
                    .isGreaterThanOrEqualTo(3);
            assertThat(dry.tombstoned())
                    .as("the first run is a dry run, whatever auto-apply says")
                    .isZero();
            assertThat(count(A, "memory_tombstone", "")).isZero();
            assertThat(dry.forgets())
                    .as(
                            "carol 3 (TTL + two stale trusted) + dave 3 (breaker cap) + erin 0 (pinned/typed)")
                    .isEqualTo(6);
            assertThat(dry.merges()).as("one near-duplicate would be merged").isEqualTo(1);
            assertThat(dry.summaries()).as("nothing is left in the summarise band").isZero();
            assertThat(
                            count(
                                    A,
                                    "memory_consolidation_runs",
                                    "mode = 'dry_run' AND subject IN ('carol', 'dave', 'erin')"))
                    .isEqualTo(3);

            clock.advance(Duration.ofDays(8)); // past app.memory.decay.dry-run-days (7)
            MemoryConsolidationService.Report applied = consolidation.consolidateTenant();
            assertThat(applied.tombstoned())
                    .as(
                            "carol: the TTL-expired row, the two stale trusted rows and the merged duplicate; dave held back")
                    .isEqualTo(4);
            assertThat(
                            count(
                                    A,
                                    "memory_tombstone",
                                    "subject_key = 'carol' AND reason = 'QUARANTINE_TTL'"))
                    .isEqualTo(1);
            assertThat(
                            count(
                                    A,
                                    "memory_tombstone",
                                    "subject_key = 'carol' AND reason = 'DECAY' AND trust_at_delete = 'TRUSTED'"))
                    .as("TRUSTED facts can now reach the forget band (behind auto-apply)")
                    .isEqualTo(2);
            assertThat(
                            count(
                                    A,
                                    "memory_tombstone",
                                    "subject_key = 'carol' AND reason LIKE 'MERGED_INTO:%'"))
                    .isEqualTo(1);
            assertThat(count(A, "memory_tombstone", "subject_key = 'dave'"))
                    .as("breaker tripped: nothing applied")
                    .isZero();
            assertThat(count(A, "memory_tombstone", "subject_key = 'erin'"))
                    .as("pinned and person-authored never")
                    .isZero();
            assertThat(count(A, "memory_consolidation_runs", "subject = 'dave' AND mode = 'apply'"))
                    .isZero();
            assertThat(
                            count(
                                    A,
                                    "memory_consolidation_runs",
                                    "subject = 'carol' AND mode = 'apply'"))
                    .isEqualTo(1);
            // A tombstone is a hash-only marker; it never rewrites or deletes the fact row, and
            // consolidating legacy
            // facts files no memory_item proposal.
            assertThat(count(A, "agent_memory", "subject_key = 'carol'")).isEqualTo(20);
            assertThat(
                            count(
                                    A,
                                    "memory_tombstone",
                                    "content_sha256 IS NULL OR length(content_sha256) <> 64"))
                    .isZero();
            assertThat(count(A, "memory_item", "'carol' = ANY(subject_keys)")).isZero();

            // Tombstone forgetting is no longer inert: the forgotten facts are not recalled, the
            // live ones are.
            doAnswer(i -> embed(i.getArgument(0))).when(sidecar).embedQuery(anyString());
            List<String> zephyr =
                    recalled("carol", "Carol once asked about the zephyr quarterly roadmap");
            assertThat(zephyr)
                    .as("forgotten (tombstoned) trusted facts are not recalled")
                    .noneMatch(c -> c.contains("zephyr"))
                    .noneMatch(c -> c.contains("quokka"));
            assertThat(recalled("carol", "Carol prefers green tea over coffee every morning"))
                    .as("the kept duplicate is still recalled; the merged-away twin is not")
                    .contains("Carol prefers green tea over coffee every morning")
                    .doesNotContain("Carol prefers green tea over coffee every morning please");
            assertThat(
                            recalled(
                                    "carol",
                                    "Fresh fact about alpha relating to alphaish alphaing alphaed"))
                    .contains("Fresh fact about alpha relating to alphaish alphaing alphaed");
            assertThat(recalled("erin", "Erin pinned the zephyr preference to keep"))
                    .as("untouched facts stay recallable")
                    .contains("Erin pinned the zephyr preference to keep");

            // The resurrection guard: an automated writer cannot bring a forgotten fact back ...
            assertThatThrownBy(
                            () ->
                                    agentMemory.remember(
                                            agentA,
                                            "carol",
                                            "Carol once asked about the zephyr quarterly roadmap",
                                            com.agenticai.core.agents.memory.MemoryProvenance
                                                    .TRUSTED))
                    .isInstanceOf(com.agenticai.core.agents.memory.MemoryForgottenException.class);
            assertThat(count(A, "agent_memory", "subject_key = 'carol'")).isEqualTo(20);
            // ... a fact that was never forgotten is unaffected ...
            agentMemory.remember(
                    agentA,
                    "carol",
                    "Carol likes to hike on weekends",
                    com.agenticai.core.agents.memory.MemoryProvenance.TRUSTED);
            assertThat(count(A, "agent_memory", "subject_key = 'carol'")).isEqualTo(21);
            // ... and a person typing it again on purpose clears the tombstone and it is recalled
            // again.
            agentMemory.rememberOwn(
                    agentA, "carol", "Carol once asked about the zephyr quarterly roadmap", 25);
            assertThat(
                            count(
                                    A,
                                    "memory_tombstone",
                                    "subject_key = 'carol' AND original_id IS NOT NULL"))
                    .as("the zephyr tombstone is cleared by the person's re-confirmation")
                    .isEqualTo(3);
            assertThat(recalled("carol", "Carol once asked about the zephyr quarterly roadmap"))
                    .as(
                            "a person re-adding it brings it back (the old row has the same text, so two copies)")
                    .contains("Carol once asked about the zephyr quarterly roadmap");
            assertThat(
                            count(
                                    A,
                                    "agent_memory",
                                    "subject_key = 'carol' AND person_authored AND abs(importance - 0.8) < 0.001"))
                    .isEqualTo(1);

            // The next night does not plan the forgotten rows again: no duplicate tombstones.
            int tombstonesBefore = count(A, "memory_tombstone", "subject_key = 'carol'");
            clock.advance(Duration.ofDays(1));
            consolidation.consolidateTenant();
            assertThat(count(A, "memory_tombstone", "subject_key = 'carol'"))
                    .isEqualTo(tombstonesBefore);
        } finally {
            consolidation.useClock(Clock.systemUTC());
        }
    }
}
