package com.agenticai.core.brief;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentAutonomyTier;
import com.agenticai.core.guardrails.GuardrailViolationEvent;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Automatic demotion: a guardrail strike on an envoy agent (event) or a failed eval run (poll) lowers the brief one
 * rung through the real trust ladder, and quality-tooling replays never count as strikes.
 */
class BriefTrustWatcherTest {

    private final EnvoyKit kit = new EnvoyKit();
    private final FakeProofData data = new FakeProofData();
    private final BriefBindingRepository bindings = mock(BriefBindingRepository.class);
    private final TenantSchemaIterator iterator = mock(TenantSchemaIterator.class);
    /** Runs the job inline, as the winning replica would. */
    private final PgAdvisoryJobLock jobLock = runsInline();

    private static PgAdvisoryJobLock runsInline() {
        PgAdvisoryJobLock lock = mock(PgAdvisoryJobLock.class);
        org.mockito.Mockito.when(lock.tryRunExclusive(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
            .thenAnswer(inv -> {
                inv.<Runnable>getArgument(1).run();
                return true;
            });
        return lock;
    }
    private BriefTrustWatcher watcher;
    private ProofLedgerService ledger;
    private UUID id;
    private String agentId;

    @BeforeEach
    void applyAndPromoteToTrusted() {
        ledger = new ProofLedgerService(kit.store, kit.ledger, kit.ports, data, kit.eventRepository, kit.mapper,
            Clock.fixed(EnvoyKit.NOW, ZoneOffset.UTC));
        watcher = new BriefTrustWatcher(bindings, kit.briefRepository, kit.ledger, data, ledger, kit.trust, kit.ports, iterator, jobLock);
        id = kit.savedAndApplied(EnvoyKit.richBrief());
        agentId = kit.entityIdOf(id, "a1");
        kit.proof[0] = new InMemoryProofSource(InMemoryProofSource.strong(EnvoyKit.NOW));
        kit.trust.setRung(id, TrustRung.TRUSTED);
        when(bindings.findByKindAndEntityId(eq("AGENT"), anyString()))
            .thenReturn(List.of(new BriefBindingEntity(id, "a1", "AGENT", agentId)));
        TenantContext.set(TenantContext.of("tenant_a", "chat-user", Set.of()));
    }

    @AfterEach
    void clearContext() {
        watcher.shutdown();
        TenantContext.clear();
    }

    private String rung() {
        return kit.entity(id).getRung();
    }

    private void awaitRung(String expected) throws InterruptedException {
        for (int i = 0; i < 100 && !expected.equals(rung()); i++) {
            Thread.sleep(50);
        }
        assertThat(rung()).isEqualTo(expected);
    }

    private static GuardrailViolationEvent strike(String session) {
        return new GuardrailViolationEvent(UUID.randomUUID(), session, true, "PROFANITY", "profane input", Instant.now());
    }

    // ------------------------------------------------------------------ the event

    @Test
    void aGuardrailStrikeOnAnEnvoyAgentLowersTheBriefOneRungAndLeavesTheEvidence() throws InterruptedException {
        watcher.onGuardrailViolation(strike("chat-session-1"));

        awaitRung("UNDER_WATCH");
        assertThat(kit.ports.tiers.get(agentId)).isEqualTo(AgentAutonomyTier.SUPERVISED);
        BriefTrustEventEntity demoted = kit.events.get(kit.events.size() - 1);
        assertThat(demoted.getKind()).isEqualTo("DEMOTED");
        assertThat(demoted.getFromRung()).isEqualTo("TRUSTED");
        assertThat(demoted.getToRung()).isEqualTo("UNDER_WATCH");
        assertThat(demoted.getActor()).isEqualTo("system");
        assertThat(demoted.getEvidenceJson()).contains("Automatic demotion").contains("PROFANITY");
        // The strike itself is durable evidence, separate from the demotion.
        assertThat(data.strikeDetails).hasSize(1);
        assertThat(data.strikeDetails.get(0)).contains("path=input").contains("guardrail=PROFANITY");
    }

    @Test
    void twoStrikesQueuedTogetherLowerTheBriefOneRungNotTwo() throws InterruptedException {
        kit.trust.setRung(id, TrustRung.AUTONOMOUS);
        assertThat(rung()).isEqualTo("AUTONOMOUS");

        watcher.onGuardrailViolation(strike("chat-session-1"));
        watcher.onGuardrailViolation(strike("chat-session-2"));
        awaitRung("TRUSTED");
        Thread.sleep(400);

        assertThat(rung()).isEqualTo("TRUSTED");
        assertThat(kit.events.stream().filter(e -> "DEMOTED".equals(e.getKind()))).hasSize(1);
    }

    @Test
    void aBrokenSiblingEntityDoesNotBlockTheProtectiveDemotion() {
        // The agent the brief bound was deleted outside it: its tier cannot be read, the workflow still comes down.
        kit.ports.failOn.add("setAgentTier");

        watcher.demoteAs("tenant_a", id, "test", Map.of());

        assertThat(rung()).isEqualTo("UNDER_WATCH");
        assertThat(kit.events.get(kit.events.size() - 1).getKind()).isEqualTo("DEMOTED");
    }

    @Test
    void theApprovalStepsComeBackWithTheLowerRung() throws InterruptedException {
        watcher.onGuardrailViolation(strike("chat-session-1"));

        awaitRung("UNDER_WATCH");
        // UNDER_WATCH puts an approval before the tool and the output again (TRUSTED had only the irreversible output).
        assertThat(TrustGraphMaterializer.approvalCount(kit.ports.graphs.get(kit.entityIdOf(id, "w1")))).isEqualTo(2);
    }

    @Test
    void evalAndSimulationReplaysAreNotStrikes() throws InterruptedException {
        watcher.onGuardrailViolation(strike("eval:run-1:3"));
        watcher.onGuardrailViolation(strike("sim:run-9"));
        Thread.sleep(300);

        assertThat(rung()).isEqualTo("TRUSTED");
        assertThat(data.strikeDetails).isEmpty();
        assertThat(BriefTrustWatcher.isQualityReplay("eval:x")).isTrue();
        assertThat(BriefTrustWatcher.isQualityReplay("sim:x")).isTrue();
        assertThat(BriefTrustWatcher.isQualityReplay("job:x")).isFalse();
        assertThat(BriefTrustWatcher.isQualityReplay(null)).isFalse();
    }

    @Test
    void aStrikeOnAnAgentNoBriefOwnsChangesNothing() throws InterruptedException {
        when(bindings.findByKindAndEntityId(eq("AGENT"), anyString())).thenReturn(List.of());

        watcher.onGuardrailViolation(strike("chat-session-1"));
        Thread.sleep(200);

        assertThat(rung()).isEqualTo("TRUSTED");
        assertThat(data.strikeDetails).isEmpty();
    }

    @Test
    void aStrikeWithNoTenantContextIsIgnored() throws InterruptedException {
        TenantContext.clear();

        watcher.onGuardrailViolation(strike("chat-session-1"));
        Thread.sleep(200);

        assertThat(rung()).isEqualTo("TRUSTED");
        assertThat(data.strikeDetails).isEmpty();
    }

    @Test
    void aBriefAlreadyAtTheBottomRungKeepsTheStrikeAsEvidenceButHasNothingToLower() throws InterruptedException {
        kit.trust.setRung(id, TrustRung.UNDER_WATCH);
        int events = kit.events.size();

        watcher.onGuardrailViolation(strike("chat-session-1"));
        Thread.sleep(300);

        assertThat(data.strikeDetails).hasSize(1);
        assertThat(kit.events).hasSize(events);
        assertThat(rung()).isEqualTo("UNDER_WATCH");
    }

    // ------------------------------------------------------------------ demoteAs

    @Test
    void theDemotionRunsAsTheSystemAndRestoresTheCallersContext() {
        Optional<TenantContext> before = TenantContext.current();

        watcher.demoteAs("tenant_a", id, "test", Map.of("k", "v"));

        assertThat(rung()).isEqualTo("UNDER_WATCH");
        assertThat(TenantContext.current()).isEqualTo(before);
    }

    @Test
    void aDemotionThatCannotCompleteIsLoggedNotThrown() {
        watcher.demoteAs("tenant_a", UUID.randomUUID(), "unknown brief", Map.of());

        assertThat(rung()).isEqualTo("TRUSTED");
    }

    // ------------------------------------------------------------------ the poll

    @Test
    void aStrikeTheEventPathMissedIsActedOnByThePoll() {
        data.recordStrike(id, agentId, "path=output");

        Optional<?> change = watcher.check(kit.entity(id));

        assertThat(change).isPresent();
        assertThat(rung()).isEqualTo("UNDER_WATCH");
        assertThat(kit.events.get(kit.events.size() - 1).getEvidenceJson()).contains("guardrail strike");
    }

    @Test
    void aFailedEvalRunSinceTheRungBeganLowersItAndSaysWhy() {
        data.runs = List.of(new ProofData.EvalRunPoint("r-bad", Instant.now().plusSeconds(60), 0.42, 0.71, false, "FAILED"));

        Optional<?> change = watcher.check(kit.entity(id));

        assertThat(change).isPresent();
        assertThat(rung()).isEqualTo("UNDER_WATCH");
        assertThat(kit.events.get(kit.events.size() - 1).getEvidenceJson())
            .contains("An eval run scored 0.42, below the quality gate of 0.71.");
    }

    @Test
    void aFailedRunFromBeforeTheRungBeganIsNotABreach() {
        data.runs = List.of(new ProofData.EvalRunPoint("r-old", Instant.now().minus(Duration.ofDays(3)), 0.42, 0.71, false, "FAILED"));

        assertThat(watcher.check(kit.entity(id))).isEmpty();
        assertThat(rung()).isEqualTo("TRUSTED");
    }

    @Test
    void passingAndInconclusiveRunsAreNotABreach() {
        data.runs = List.of(
            new ProofData.EvalRunPoint("r1", Instant.now().plusSeconds(60), 0.90, 0.71, true, "PASSED"),
            new ProofData.EvalRunPoint("r2", Instant.now().plusSeconds(61), 0.10, 0.71, false, "INCONCLUSIVE"));

        assertThat(watcher.check(kit.entity(id))).isEmpty();
        assertThat(rung()).isEqualTo("TRUSTED");
    }

    @Test
    void nothingIsCheckedWhereNoGateIsArmedOrThePlanHasNoEvalRuns() {
        data.runs = List.of(new ProofData.EvalRunPoint("r-bad", Instant.now().plusSeconds(60), 0.42, 0.71, false, "FAILED"));
        kit.ports.evalAllowed = false;
        assertThat(watcher.check(kit.entity(id))).isEmpty();
        assertThat(rung()).isEqualTo("TRUSTED");

        kit.ports.evalAllowed = true;
        kit.trust.setRung(id, TrustRung.UNDER_WATCH);
        assertThat(watcher.check(kit.entity(id))).isEmpty();
    }

    @Test
    void thePollCoversEveryTenantAndLowersEveryBreachedBrief() {
        doAnswer(call -> {
            call.<Consumer<String>>getArgument(0).accept("tenant_a");
            return null;
        }).when(iterator).forEachActiveTenantSchema(org.mockito.ArgumentMatchers.any());
        when(kit.briefRepository.findAllByOrderByUpdatedAtDesc()).thenReturn(List.copyOf(kit.briefs.values()));
        data.runs = List.of(new ProofData.EvalRunPoint("r-bad", Instant.now().plusSeconds(60), 0.42, 0.71, false, "FAILED"));

        watcher.poll();

        assertThat(rung()).isEqualTo("UNDER_WATCH");
    }

    /** JAVA-023: a replica that loses the advisory job lock skips the tick entirely. */
    @Test
    void thePollIsSkippedWhenAnotherReplicaHoldsTheJobLock() {
        PgAdvisoryJobLock busy = mock(PgAdvisoryJobLock.class);
        when(busy.tryRunExclusive(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(false);
        BriefTrustWatcher otherReplica = new BriefTrustWatcher(
            bindings, kit.briefRepository, kit.ledger, data, ledger, kit.trust, kit.ports, iterator, busy);

        otherReplica.poll();

        verify(busy).tryRunExclusive(eq("brief-trust-watcher"), org.mockito.ArgumentMatchers.any());
        verify(iterator, never())
            .forEachActiveTenantSchema(org.mockito.ArgumentMatchers.any());
    }
}
