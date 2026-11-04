package com.agenticai.core.agents.memorystore;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.memory.spi.MemoryKind;
import com.agenticai.core.agents.memory.spi.MemoryProposalReceipt;
import com.agenticai.core.agents.memory.spi.MemoryProposalStatus;
import com.agenticai.core.agents.memory.spi.MemoryProvenanceRecord;
import com.agenticai.core.agents.memory.spi.MemorySourceRef;
import com.agenticai.core.agents.memory.spi.MemoryTrustLevel;
import com.agenticai.core.agents.memory.spi.MemoryWriteProposal;
import com.agenticai.core.security.TenantContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StoreMemoryProposalSinkServiceTest {

    private StoreFixture f;

    @BeforeEach
    void setUp() {
        f = new StoreFixture();
    }

    @AfterEach
    void tearDown() {
        f.close();
    }

    @Test
    void everyKindIsStoredQuarantinedAndPendingNeverActive() {
        for (MemoryKind kind : List.of(MemoryKind.EPISODIC, MemoryKind.PROCEDURAL, MemoryKind.SELF_MODEL)) {
            MemoryProposalReceipt r = f.sink.propose(f.proposal(kind, "text " + kind)).orElseThrow();
            MemoryItem row = f.storage.items.get(r.id());
            assertThat(row.status()).isEqualTo(MemoryItemStatus.PENDING);
            assertThat(row.trust()).isEqualTo(MemoryTrustLevel.QUARANTINED);
            assertThat(row.approvedBy()).isNull();
            assertThat(r.status()).isEqualTo(MemoryProposalStatus.PENDING);
        }
    }

    @Test
    void aTaintedProposalGetsAQuarantinedVerdictButTheSameStoredState() {
        MemoryWriteProposal tainted =
                new MemoryWriteProposal(
                        f.agent,
                        "u",
                        MemoryKind.EPISODIC,
                        "x",
                        new MemoryProvenanceRecord(MemoryTrustLevel.TRUSTED, List.of("web"), null),
                        null,
                        false);
        MemoryProposalReceipt r = f.sink.propose(tainted).orElseThrow();
        assertThat(r.status()).isEqualTo(MemoryProposalStatus.QUARANTINED);
        assertThat(f.storage.items.get(r.id()).sourceTaint()).containsExactly("web");
        assertThat(f.storage.items.get(r.id()).status()).isEqualTo(MemoryItemStatus.PENDING);
    }

    @Test
    void episodesExpireAndAreTiedToTheSubject() {
        MemoryItem row = f.storage.items.get(f.sink.propose(f.proposal(MemoryKind.EPISODIC, "e")).orElseThrow().id());
        assertThat(row.expiresAt()).isEqualTo(f.clock.instant().plusSeconds(90L * 86400));
        assertThat(row.subjectKeys()).containsExactly("user-1");
        MemoryItem proc = f.storage.items.get(f.sink.propose(f.proposal(MemoryKind.PROCEDURAL, "p")).orElseThrow().id());
        assertThat(proc.expiresAt()).isNull();
    }

    @Test
    void offMeansNothingIsStored() {
        f.props.setEnabled(false);
        assertThat(f.sink.propose(f.proposal(MemoryKind.EPISODIC, "x"))).isEmpty();
        assertThat(f.storage.items).isEmpty();
        f.props.setEnabled(true);
        f.overrideAllows = false;
        assertThat(f.sink.proposeDetailed(f.proposal(MemoryKind.EPISODIC, "x")).refusal()).isEqualTo("DISABLED");
        assertThat(f.storage.items).isEmpty();
    }

    @Test
    void semanticAndPlatformDisabledKindsAreRefused() {
        assertThat(f.sink.proposeDetailed(f.proposal(MemoryKind.SEMANTIC, "x")).refusal())
                .isEqualTo("KIND_NOT_STORED");
        f.props.setKinds(java.util.EnumSet.of(MemoryKind.EPISODIC));
        assertThat(f.sink.proposeDetailed(f.proposal(MemoryKind.PROCEDURAL, "x")).refusal())
                .isEqualTo("KIND_DISABLED");
    }

    @Test
    void noTenantFailsClosed() {
        TenantContext.clear();
        assertThat(f.sink.proposeDetailed(f.proposal(MemoryKind.EPISODIC, "x")).refusal()).isEqualTo("NO_TENANT");
        assertThat(f.storage.items).isEmpty();
    }

    @Test
    void screenAndSizeAreEnforcedAndRefusalsAreLoggedWithoutText() {
        String secret = "x".repeat(f.props.getMaxItemChars() + 1);
        assertThat(f.sink.proposeDetailed(f.proposal(MemoryKind.EPISODIC, secret)).refusal())
                .isEqualTo("SCREEN_TOO_LONG");
        assertThat(f.sink.proposeDetailed(f.proposal(MemoryKind.EPISODIC, "a\u0000b")).refusal())
                .isEqualTo("SCREEN_CONTROL_CHAR");
        assertThat(f.storage.events).hasSize(2).allSatisfy(e -> {
            assertThat(e.event()).isEqualTo(MemoryEvent.Type.REFUSED);
            assertThat(e.reason()).doesNotContain("xxxx");
        });
    }

    @Test
    void boundsRefuseInsteadOfDropping() {
        f.props.setMaxPendingPerAgent(2);
        assertThat(f.sink.propose(f.proposal(MemoryKind.EPISODIC, "1"))).isPresent();
        assertThat(f.sink.propose(f.proposal(MemoryKind.EPISODIC, "2"))).isPresent();
        assertThat(f.sink.proposeDetailed(f.proposal(MemoryKind.EPISODIC, "3")).refusal())
                .isEqualTo("PENDING_QUEUE_FULL");
        assertThat(f.storage.items).hasSize(2);

        f.props.setMaxPendingPerAgent(100);
        f.props.setMaxWritesPerHourPerAgent(2);
        assertThat(f.sink.proposeDetailed(f.proposal(MemoryKind.EPISODIC, "4")).refusal()).isEqualTo("RATE_LIMIT");
        f.props.setMaxWritesPerHourPerAgent(60);
        f.props.setMaxItemsPerAgent(2);
        assertThat(f.sink.proposeDetailed(f.proposal(MemoryKind.EPISODIC, "5")).refusal()).isEqualTo("AGENT_FULL");
    }

    @Test
    void noEmbedderOrAFailingOneStoresNothing() {
        f.embedder = t -> {
            throw new IllegalStateException("sidecar down");
        };
        assertThat(f.sink.proposeDetailed(f.proposal(MemoryKind.EPISODIC, "x")).refusal()).isEqualTo("ERROR");
        assertThat(f.storage.items).isEmpty();
    }

    // ------------------------------------------------------------------ lineage

    private MemoryWriteProposal derived(MemoryKind kind, UUID... sources) {
        return new MemoryWriteProposal(
                f.agent,
                "user-1",
                kind,
                "distilled",
                MemoryProvenanceRecord.TRUSTED,
                null,
                true,
                java.util.Arrays.stream(sources).map(MemorySourceRef::item).toList());
    }

    @Test
    void derivedItemInheritsLineageGenerationAndQuarantineFromItsSources() {
        UUID clean = f.active(MemoryKind.EPISODIC, "clean episode");
        MemoryWriteProposal taintedEpisode =
                new MemoryWriteProposal(
                        f.agent,
                        "user-2",
                        MemoryKind.EPISODIC,
                        "tainted episode",
                        new MemoryProvenanceRecord(MemoryTrustLevel.TRUSTED, List.of("web"), null),
                        null,
                        false);
        UUID tainted = f.sink.propose(taintedEpisode).orElseThrow().id();
        f.review.approve(f.agent, tainted, MemoryActor.human("alice"));

        MemoryProposalReceipt r = f.sink.propose(derived(MemoryKind.PROCEDURAL, clean, tainted)).orElseThrow();
        MemoryItem row = f.storage.items.get(r.id());
        assertThat(r.status()).isEqualTo(MemoryProposalStatus.QUARANTINED);
        assertThat(row.sourceTaint()).containsExactly("web");
        assertThat(row.generation()).isEqualTo(1);
        assertThat(row.modelGenerated()).isTrue();
        assertThat(row.writer()).isEqualTo(MemoryWriter.DISTILLER);
        assertThat(row.subjectKeys()).containsExactlyInAnyOrder("user-1", "user-2");
        assertThat(f.storage.lineage(row.id()))
                .extracting(MemoryItemSource::sourceKey)
                .containsExactlyInAnyOrder(clean.toString(), tainted.toString());
        assertThat(row.status()).isEqualTo(MemoryItemStatus.PENDING);
    }

    @Test
    void aCleanDerivationIsPendingAndGenerationOne() {
        UUID clean = f.active(MemoryKind.EPISODIC, "clean episode");
        MemoryProposalReceipt r = f.sink.propose(derived(MemoryKind.PROCEDURAL, clean)).orElseThrow();
        assertThat(r.status()).isEqualTo(MemoryProposalStatus.PENDING);
        assertThat(f.storage.items.get(r.id()).generation()).isEqualTo(1);
    }

    @Test
    void aCallerCannotClaimBetterTrustThanAMemoryItemSourceHas() {
        UUID pending = f.sink.propose(f.proposal(MemoryKind.EPISODIC, "not approved")).orElseThrow().id();
        MemorySourceRef lie = new MemorySourceRef(MemorySourceRef.MEMORY_ITEM, pending.toString(), MemoryTrustLevel.TRUSTED, List.of());
        MemoryWriteProposal p =
                new MemoryWriteProposal(f.agent, "u", MemoryKind.PROCEDURAL, "d", null, null, true, List.of(lie));
        assertThat(f.sink.propose(p).orElseThrow().status()).isEqualTo(MemoryProposalStatus.QUARANTINED);
    }

    @Test
    void modelGeneratedSourcesAreRefusedUntilAHumanConfirmedThem() {
        UUID clean = f.active(MemoryKind.EPISODIC, "clean episode");
        UUID distilled = f.sink.propose(derived(MemoryKind.PROCEDURAL, clean)).orElseThrow().id(); // generation 1, PENDING
        assertThat(f.sink.proposeDetailed(derived(MemoryKind.SELF_MODEL, distilled)).refusal())
                .isEqualTo(MemoryTrustPolicy.MODEL_GENERATED_SOURCE);
        f.review.approve(f.agent, distilled, MemoryActor.human("alice"));
        MemoryProposalReceipt second = f.sink.propose(derived(MemoryKind.SELF_MODEL, distilled)).orElseThrow();
        assertThat(f.storage.items.get(second.id()).generation()).isEqualTo(2);
        // generation 2 is at the cap: nothing may be derived from it, even confirmed
        f.review.approve(f.agent, second.id(), MemoryActor.human("alice"));
        assertThat(f.sink.proposeDetailed(derived(MemoryKind.SELF_MODEL, second.id())).refusal())
                .isEqualTo(MemoryTrustPolicy.GENERATION_CAP);
    }

    @Test
    void unknownOrOtherAgentSourcesAreRefused() {
        assertThat(f.sink.proposeDetailed(derived(MemoryKind.PROCEDURAL, UUID.randomUUID())).refusal())
                .isEqualTo("SOURCE_UNKNOWN");
        UUID other = UUID.randomUUID();
        MemoryItem foreign =
                MemoryItem.pending(UUID.randomUUID(), other, MemoryKind.EPISODIC, "x", MemoryWriter.AGENT, null, null,
                        null, List.of(), false, 0, List.of(), 1, null, f.clock.instant(), null);
        f.storage.put(foreign, StoreFixture.vector("x"));
        assertThat(f.sink.proposeDetailed(derived(MemoryKind.PROCEDURAL, foreign.id())).refusal())
                .isEqualTo("SOURCE_UNKNOWN");
    }

    @Test
    void createdEventsCarryIdsAndCodesNeverText() {
        String text = "the customer wants refunds in INR";
        UUID id = f.sink.propose(f.proposal(MemoryKind.EPISODIC, text)).orElseThrow().id();
        List<MemoryEvent> events = f.storage.events(id);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).event()).isEqualTo(MemoryEvent.Type.CREATED);
        assertThat(events.get(0).toString()).doesNotContain("customer").doesNotContain("INR");
    }
}
